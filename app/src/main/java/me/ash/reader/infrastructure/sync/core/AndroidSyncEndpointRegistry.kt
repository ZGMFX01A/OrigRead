package me.ash.reader.infrastructure.sync.core

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
) {
    suspend fun list(): List<AndroidSyncEndpointConfig> =
        database.syncEndpointDao().listAll().map { it.toConfig() }

    suspend fun save(config: AndroidSyncEndpointConfig, now: Long = System.currentTimeMillis()) {
        require(config.syncSpaceId.isNotBlank()) { "syncSpaceId must not be blank" }
        val normalizedUrl = normalizeBaseUrl(config.baseUrl)
        val existing = database.syncEndpointDao().listAll().firstOrNull { it.endpointId == config.endpointId }
        database.syncEndpointDao().upsert(
            SyncEndpointEntity(
                endpointId = config.endpointId,
                syncSpaceId = config.syncSpaceId,
                baseUrl = normalizedUrl,
                accessToken = config.accessToken,
                transport = config.transport.trim().ifBlank { "CLOUD" }.uppercase(),
                enabled = config.enabled,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
    }

    suspend fun remove(endpointId: String): Boolean = database.syncEndpointDao().delete(endpointId) > 0

    suspend fun syncAll(maxOperations: Int = 500): AndroidSyncRunSummary {
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
            if (endpoint.syncSpaceId in failedReconciliationSpaces) {
                failed += endpoint.endpointId
                return@forEach
            }
            val session =
                AndroidSyncHttpEndpointSession(
                    client = client,
                    baseUrl = endpoint.baseUrl,
                    syncSpaceId = endpoint.syncSpaceId,
                    deviceId = deviceId,
                    accessToken = endpoint.accessToken,
                    signingKeys = signingKeys,
                )
            try {
                coordinator.run(
                    endpoint.syncSpaceId,
                    session,
                    maxOperations = maxOperations,
                    endpointId = endpoint.endpointId,
                    allowStableGc = endpoint.transport.uppercase() in setOf("CLOUD", "SERVER"),
                )
                succeeded++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failed += endpoint.endpointId
            } finally {
                session.close()
            }
        }
        return AndroidSyncRunSummary(endpoints.size, succeeded, failed)
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrElse { error("Invalid Sync endpoint URL") }
        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            "Sync endpoint URL must use http or https"
        }
        require(!uri.host.isNullOrBlank()) { "Sync endpoint URL must include a host" }
        require(uri.userInfo == null) { "Sync endpoint URL must not contain credentials" }
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
