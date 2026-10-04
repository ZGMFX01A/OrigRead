package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.ash.reader.infrastructure.db.AndroidDatabase
import okhttp3.OkHttpClient

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
    @Inject lateinit var genesisSnapshotService: SyncGenesisSnapshotService
    @Inject lateinit var runHistoryRecovery: SyncRunHistoryRecovery
    @Inject lateinit var snapshotOwners: SyncSnapshotSpaceOwner
    @Inject lateinit var snapshotJobs: SyncSnapshotJobs
    private val activeLanSessions = java.util.concurrent.ConcurrentHashMap<AndroidSyncHttpEndpointSession, String>()

    /** 用户关闭 LAN 时通知真实本地执行器，并用仍有效的会话请求远端取消。 */
    suspend fun pauseLanSnapshots() {
        val sessions = activeLanSessions.keys.toList()
        val spaces = database.syncEndpointDao().listAll().filter { it.transport.equals("LAN", true) }
            .map { it.syncSpaceId }.toSet()
        for (space in spaces) {
            snapshotOwners.requestCancel(space)
            snapshotJobs.cancelForSpace(space)
        }
        var failure: Exception? = null
        for (session in sessions) {
            try { session.requestSnapshotPause() }
            catch (error: Exception) {
                // 离线远端不能确认取消，继续通知其余会话，最后显式报告失败。
                if (error is CancellationException) throw error
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
    suspend fun list(): List<AndroidSyncEndpointConfig> =
        database.syncEndpointDao().listAll().map { it.toConfig() }

    suspend fun listRunHistory(syncSpaceId: String, limit: Int = 100): List<SyncRunHistoryEntity> {
        runHistoryRecovery.ensureRecovered()
        return database.syncRunHistoryDao().listForSpace(syncSpaceId, limit.coerceIn(1, 500))
    }

    /** 观察前沿用历史恢复边界，LAN 关闭时也能正确结束上个进程的 RUNNING 记录。 */
    fun observeRunHistory(syncSpaceId: String, limit: Int): Flow<List<SyncRunHistoryEntity>> = flow {
        runHistoryRecovery.ensureRecovered()
        emitAll(database.syncRunHistoryDao().observeForSpace(syncSpaceId, limit.coerceIn(1, 500)))
    }

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

    /** 手动/WorkManager 保留全端点同步入口。 */
    suspend fun syncAll(maxOperations: Int = 500, onProgress: suspend (SyncSessionProgress) -> Unit = {}): AndroidSyncRunSummary =
        syncMatching(maxOperations, null, onProgress)

    /** LAN 生命周期自动重连只同步 LAN，避免触发用户配置的云端任务。 */
    suspend fun syncLan(): AndroidSyncRunSummary = syncMatching(500, "LAN")

    private suspend fun syncMatching(
        maxOperations: Int = 500,
        transportFilter: String? = null,
        onProgress: suspend (SyncSessionProgress) -> Unit = {},
    ): AndroidSyncRunSummary {
        // 历史恢复先于任何新 RUNNING 行，避免界面读取与自动/手动同步互相覆盖。
        runHistoryRecovery.ensureRecovered()
        // 本地维护在网络连接之前运行；端点离线或列表为空也不阻止安全回收。
        val spaces = database.openHelper.writableDatabase.query("SELECT syncSpaceId FROM sync_local_space_binding", emptyArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        for (space in spaces) coordinator.maintainLocal(space)
        val endpoints = database.syncEndpointDao().listEnabled().filter {
            transportFilter == null || it.transport.equals(transportFilter, ignoreCase = true)
        }
        if (endpoints.isEmpty()) return AndroidSyncRunSummary(0, 0, emptyList())
        val deviceId = database.syncRuntimeDao().findDeviceIdentity()?.deviceId
            ?: return AndroidSyncRunSummary(endpoints.size, 0, endpoints.map(SyncEndpointEntity::endpointId))
        val failed = mutableListOf<String>()
        var succeeded = 0
        var moreWork = false
        var authStabilityPending = false
        val preparationErrors = mutableMapOf<String, Throwable>()
        for (syncSpaceId in endpoints.map(SyncEndpointEntity::syncSpaceId).distinct()) {
            try {
                val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
                if (binding?.genesisSessionId?.startsWith("join-") == true) {
                    // 不发送旧空间 Operation；全域固定视图捕获后才允许目标空间反熵。
                    genesisSnapshotService.runPaged(SyncGenesisSnapshotService.PagedRunOptions(binding.localAccountId, syncSpaceId, binding.genesisSessionId))
                }
                feedScopedConfigReconciler.reconcile(syncSpaceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // 基线/配置准备失败必须保留原始原因，禁止发送半初始化空间的数据。
                preparationErrors[syncSpaceId] = failure
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

            if (endpoint.syncSpaceId in preparationErrors) {
                failed += endpoint.endpointId
                failRun(checkNotNull(preparationErrors[endpoint.syncSpaceId]), "PREPARING")
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
            if (endpoint.transport.equals("LAN", true)) activeLanSessions[session] = endpoint.syncSpaceId
            try {
                val result =
                    coordinator.run(
                        endpoint.syncSpaceId,
                        session,
                        maxOperations = maxOperations,
                        endpointId = endpoint.endpointId,
                        automaticMaintenance = true,
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
                        stage = if (result.status == "MORE_WORK") "MORE_WORK" else if (result.maintenanceStatus == "AUTH_STABILITY_PENDING") "AUTH_STABILITY_PENDING" else "COMPLETED",
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
                moreWork = moreWork || result.status == "MORE_WORK"
                authStabilityPending = authStabilityPending || result.maintenanceStatus == "AUTH_STABILITY_PENDING"
                succeeded++
            } catch (cancelled: CancellationException) {
                failRun(cancelled, "CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                failed += endpoint.endpointId
                failRun(error)
            } finally {
                activeLanSessions.remove(session)
                session.close()
                tlsPeer?.close()
            }
        }
        database.syncRunHistoryDao().pruneKeepingNewest(500)
        return AndroidSyncRunSummary(endpoints.size, succeeded, failed, moreWork, authStabilityPending)
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

}

private fun syncRunErrorCode(error: Throwable): String {
    val prefix = error.message?.substringBefore(':')?.trim().orEmpty()
    if (prefix.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) return prefix
    return error::class.java.simpleName.uppercase().take(64)
}
