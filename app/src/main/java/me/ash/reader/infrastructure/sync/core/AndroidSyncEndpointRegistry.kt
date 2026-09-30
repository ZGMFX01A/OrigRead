package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import me.ash.reader.infrastructure.db.AndroidDatabase
import okhttp3.OkHttpClient

data class AndroidSyncEndpointConfig(
    val endpointId: String = UUID.randomUUID().toString(),
    val syncSpaceId: String,
    val baseUrl: String,
    val accessToken: String? = null,
    val transport: String = "CLOUD",
    val enabled: Boolean = true,
)

data class AndroidSyncRunSummary(
    val attempted: Int,
    val succeeded: Int,
    val failedEndpointIds: List<String>,
) {
    val completed: Boolean get() = failedEndpointIds.isEmpty()
}

/** Single Android entry point for foreground, manual and WorkManager-triggered sync. */
@Singleton
class AndroidSyncEndpointRegistry @Inject constructor(
    private val database: AndroidDatabase,
    private val client: OkHttpClient,
    private val coordinator: AndroidSyncSessionCoordinator,
    private val signingKeys: SyncDeviceSigningKeyStore,
    private val feedScopedConfigReconciler: SyncFeedScopedConfigReconciler,
    private val networkMonitor: LanNetworkMonitor,
) {
    suspend fun list(): List<AndroidSyncEndpointConfig> =
        database.syncEndpointDao().listAll().map { it.toConfig() }

    suspend fun listRunHistory(syncSpaceId: String, limit: Int = 100): List<SyncRunHistoryEntity> =
        database.syncRunHistoryDao().listForSpace(syncSpaceId, limit.coerceIn(1, 500))

    suspend fun save(config: AndroidSyncEndpointConfig, now: Long = System.currentTimeMillis()) {
        require(config.syncSpaceId.isNotBlank()) { "syncSpaceId must not be blank" }
        val transport = config.transport.trim().ifBlank { "CLOUD" }.uppercase()
        val normalizedUrl = normalizeBaseUrl(config.baseUrl, transport)
        val existing = database.syncEndpointDao().listAll().firstOrNull { it.endpointId == config.endpointId }
        database.withTransaction {
            if (
                existing != null &&
                (existing.syncSpaceId != config.syncSpaceId ||
                    existing.baseUrl != normalizedUrl ||
                    existing.transport != transport)
            ) {
                database.syncPeerCursorDao().deleteCursor(config.endpointId)
            }
            database.syncEndpointDao().upsert(
                SyncEndpointEntity(
                    endpointId = config.endpointId,
                    syncSpaceId = config.syncSpaceId,
                    baseUrl = normalizedUrl,
                    accessToken = config.accessToken,
                    transport = transport,
                    enabled = config.enabled,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
        }
    }

    suspend fun remove(endpointId: String): Boolean =
        database.withTransaction {
            database.syncPeerCursorDao().deleteCursor(endpointId)
            database.syncEndpointDao().delete(endpointId) > 0
        }

    suspend fun syncAll(
        maxOperations: Int = 500,
        onProgress: suspend (SyncSessionProgress) -> Unit = {},
    ): AndroidSyncRunSummary {
        val endpoints = database.syncEndpointDao().listEnabled()
        if (endpoints.isEmpty()) return AndroidSyncRunSummary(0, 0, emptyList())
        val deviceId = database.syncRuntimeDao().findDeviceIdentity()?.deviceId
            ?: return AndroidSyncRunSummary(endpoints.size, 0, endpoints.map(SyncEndpointEntity::endpointId))
        val failed = mutableListOf<String>()
        var succeeded = 0
        val failedReconciliationSpaces = mutableSetOf<String>()
        for (syncSpaceId in endpoints.map(SyncEndpointEntity::syncSpaceId).distinct()) {
            try {
                feedScopedConfigReconciler.reconcile(syncSpaceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failedReconciliationSpaces += syncSpaceId
            }
        }
        endpoints.forEach { endpoint ->
            val historyDao = database.syncRunHistoryDao()
            val previousRun = historyDao.findLatestForEndpoint(endpoint.endpointId)
            val retryAttempt = if (previousRun?.status == "FAILED") previousRun.retryAttempt + 1 else 0
            val startedAt = System.currentTimeMillis()
            val initialRemoteDeviceId =
                endpoint.endpointId.removePrefix("lan:")
                    .takeIf {
                        endpoint.transport.equals("LAN", ignoreCase = true) &&
                            it != endpoint.endpointId &&
                            it.isNotBlank()
                    }
            var runHistory =
                SyncRunHistoryEntity(
                    runId = UUID.randomUUID().toString(),
                    syncSpaceId = endpoint.syncSpaceId,
                    endpointId = endpoint.endpointId,
                    remoteDeviceId = initialRemoteDeviceId,
                    transport = endpoint.transport.uppercase(),
                    stage = "CONNECTING",
                    status = "RUNNING",
                    startedAt = startedAt,
                    finishedAt = null,
                    pushedOperations = 0,
                    pulledOperations = 0,
                    appliedOperations = 0,
                    rejectedOperations = 0,
                    blobBytesSent = 0L,
                    blobBytesReceived = 0L,
                    retryAttempt = retryAttempt,
                    errorCode = null,
                    errorMessage = null,
                )
            historyDao.upsert(runHistory)

            suspend fun failRun(error: Throwable, stage: String = runHistory.stage) {
                runHistory =
                    runHistory.copy(
                        stage = stage,
                        status = "FAILED",
                        finishedAt = System.currentTimeMillis(),
                        errorCode = syncRunErrorCode(error),
                        errorMessage = (error.message ?: error::class.java.simpleName).take(1_000),
                    )
                historyDao.upsert(runHistory)
            }

            if (endpoint.syncSpaceId in failedReconciliationSpaces) {
                failed += endpoint.endpointId
                failRun(IllegalStateException("CONFIG_RECONCILE_FAILED"), "PREPARING")
                return@forEach
            }
            var tlsPeer: AndroidSyncPinnedLanClient? = null
            val session = try {
                val transport = endpoint.transport.trim().uppercase()
                val secureBaseUrl = normalizeBaseUrl(endpoint.baseUrl, transport)
                if (secureBaseUrl != endpoint.baseUrl) {
                    save(endpoint.toConfig().copy(baseUrl = secureBaseUrl))
                }
                val sessionClient = if (transport == "LAN") {
                    val endpointHost = runCatching { java.net.URI(secureBaseUrl).host }.getOrNull()
                    val lanClient = networkMonitor.bindLanClient(client, endpointHost)
                    val peerDeviceId = endpoint.endpointId.removePrefix("lan:").takeIf { it != endpoint.endpointId && it.isNotBlank() }
                        ?: error("LAN endpoint has no paired device identity")
                    val peer = database.syncTrustedDeviceDao().find(endpoint.syncSpaceId, peerDeviceId)
                        ?: error("LAN endpoint has no durable trust record; pair this device again")
                    check(peer.trustState == "TRUSTED") { "LAN peer trust is revoked; pair this device again before syncing" }
                    tlsPeer = SyncLanTlsTransport.connect(
                        baseClient = lanClient,
                        secureBaseUrl = secureBaseUrl,
                        expectedPublicKeySpkiBase64 = peer.staticPublicKey,
                        expectedDeviceId = peer.deviceId,
                    )
                    database.syncTrustedDeviceDao().updateLastSeen(peer.syncSpaceId, peer.deviceId, System.currentTimeMillis())
                    tlsPeer.client
                } else {
                    client
                }
                AndroidSyncHttpEndpointSession(
                    client = sessionClient,
                    baseUrl = secureBaseUrl,
                    syncSpaceId = endpoint.syncSpaceId,
                    deviceId = deviceId,
                    accessToken = endpoint.accessToken,
                    signingKeys = signingKeys,
                )
            } catch (cancelled: CancellationException) {
                tlsPeer?.close()
                failRun(cancelled, "CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                tlsPeer?.close()
                failed += endpoint.endpointId
                failRun(error, "CONNECTING")
                return@forEach
            }
            try {
                val result =
                    coordinator.run(
                        endpoint.syncSpaceId,
                        session,
                        maxOperations = maxOperations,
                        endpointId = endpoint.endpointId,
                        allowStableGc = endpoint.transport.uppercase() in setOf("CLOUD", "SERVER"),
                        onProgress = { progress ->
                            runHistory =
                                runHistory.copy(
                                    remoteDeviceId = progress.remoteDeviceId ?: runHistory.remoteDeviceId,
                                    stage = progress.stage,
                                    pushedOperations = progress.pushedOperations,
                                    pulledOperations = progress.pulledOperations,
                                    appliedOperations = progress.appliedOperations,
                                    rejectedOperations = progress.rejectedOperations,
                                    blobBytesSent = progress.blobBytesSent,
                                    blobBytesReceived = progress.blobBytesReceived,
                                )
                            historyDao.upsert(runHistory)
                            runCatching { onProgress(progress) }
                        },
                    )
                runHistory =
                    runHistory.copy(
                        stage = "COMPLETED",
                        status = "SUCCEEDED",
                        finishedAt = System.currentTimeMillis(),
                        pushedOperations = result.pushedOperationIds.size,
                        pulledOperations = result.pulledOperationIds.size,
                        appliedOperations = result.appliedOperationIds.size,
                        rejectedOperations = result.rejectedOperationIds.size,
                        blobBytesSent = result.blobBytesSent,
                        blobBytesReceived = result.blobBytesReceived,
                        errorCode = null,
                        errorMessage = null,
                    )
                historyDao.upsert(runHistory)
                succeeded++
            } catch (cancelled: CancellationException) {
                failRun(cancelled, "CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                failed += endpoint.endpointId
                failRun(error)
            } finally {
                session.close()
                tlsPeer?.close()
            }
        }
        database.syncRunHistoryDao().pruneKeepingNewest(500)
        return AndroidSyncRunSummary(endpoints.size, succeeded, failed)
    }

    private fun normalizeBaseUrl(value: String, transport: String): String {
        var trimmed = value.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrElse { error("Invalid Sync endpoint URL") }
        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            "Sync endpoint URL must use http or https"
        }
        require(!uri.host.isNullOrBlank()) { "Sync endpoint URL must include a host" }
        require(uri.userInfo == null) { "Sync endpoint URL must not contain credentials" }
        if (transport == "LAN" && uri.scheme.equals("http", ignoreCase = true)) {
            require(uri.port in 1..65_534) {
                "Legacy LAN endpoint must have an explicit bootstrap port below 65535 before TLS migration"
            }
            val authorityStart = trimmed.indexOf("://") + 3
            val authorityEnd = trimmed.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
                .let { if (it < 0) trimmed.length else it }
            val authority = trimmed.substring(authorityStart, authorityEnd)
            val migratedAuthority = authority.replace(Regex(":(\\d+)$")) {
                ":${uri.port + 1}"
            }
            require(migratedAuthority != authority) { "Legacy LAN endpoint port could not be upgraded to TLS" }
            trimmed = "https://$migratedAuthority${trimmed.substring(authorityEnd)}"
        }
        return trimmed
    }

    private fun SyncEndpointEntity.toConfig(): AndroidSyncEndpointConfig =
        AndroidSyncEndpointConfig(
            endpointId = endpointId,
            syncSpaceId = syncSpaceId,
            baseUrl = baseUrl,
            accessToken = accessToken,
            transport = transport,
            enabled = enabled,
        )
}

private fun syncRunErrorCode(error: Throwable): String {
    val prefix = error.message?.substringBefore(':')?.trim().orEmpty()
    if (prefix.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) return prefix
    return error::class.java.simpleName.uppercase().take(64)
}
