package me.ash.reader.infrastructure.sync.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.db.AndroidDatabase
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 同步所处的细化生命周期/业务阶段。
 */
enum class SyncStatusPhase {
    IDLE,
    DISCOVERING,
    PAIRING,
    WAITING_CONFIRMATION,
    AUTHORIZING,
    CONNECTING,
    NEGOTIATING,
    SYNCING_OPERATIONS,
    SYNCING_SNAPSHOT,
    SYNCING_BLOBS,
    APPLYING,
    COMPLETED,
    FAILED,
}

/**
 * 同步管理器整体状态数据载体。
 *
 * @property isLanEnabled 局域网同步与广播是否处于激活状态
 * @property lanPort 本地局域网监听端口（激活时非空）
 * @property isDiscovering 当前是否正在执行局域网设备扫描
 * @property discoveredPeers 局域网内扫描到的对等端列表
 * @property isSyncing 当前是否正在进行反熵同步
 * @property lastSyncSummary 最近一次同步运行摘要结果
 * @property lastError 最近一次运行异常错误描述
 * @property deviceId 本机设备身份 ID
 * @property syncSpaceId 本机当前绑定的同步空间 ID
 * @property phase 当前所处的细化同步阶段
 * @property networkDiagnostics 当前局域网诊断信息
 * @property trustedDevices 当前同步空间下已受信任的设备列表
 * @property activePairingSession 当前正在进行的配对会话（若有）
 */
data class SyncManagerState(
    val isLanRequested: Boolean = false,
    val isLanEnabled: Boolean = false,
    val lanPort: Int? = null,
    val isDiscovering: Boolean = false,
    val discoveredPeers: List<AndroidSyncDiscoveredPeer> = emptyList(),
    val isSyncing: Boolean = false,
    val lastSyncSummary: AndroidSyncRunSummary? = null,
    val lastError: String? = null,
    val deviceId: String? = null,
    val syncSpaceId: String? = null,
    val phase: SyncStatusPhase = SyncStatusPhase.IDLE,
    val networkDiagnostics: LanNetworkDiagnostics? = null,
    val trustedDevices: List<SyncTrustedDeviceEntity> = emptyList(),
    val activePairingSession: ActivePairingSession? = null,
)

/**
 * Android 多端同步生命周期与前台操作管理器。
 *
 * 遵循 R11-R13 规范：
 * 1. 统一管理局域网 HTTP Socket 监听与 mDNS/NSD 服务广播的启停生命周期；
 * 2. 调度局域网对等端发现、手动 IP 连接与诊断监控；
 * 3. 驱动 Authenticated Interactive Pairing、Durable Trust 持久化与单 OWNER 授权闭环；
 * 4. 作为用户操作入口（如设置中心手动立即同步、扫描配对）的核心调度者。
 */
@Singleton
class AndroidSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AndroidDatabase,
    private val lanListener: AndroidSyncLanSocketListener,
    private val registry: AndroidSyncEndpointRegistry,
    private val keyStore: SyncDeviceSigningKeyStore,
    val networkMonitor: LanNetworkMonitor? = null,
    private val pairingCoordinator: AndroidSyncPairingCoordinator? = null,
    private val authLedgerService: AndroidSyncAuthLedgerService? = null,
    private val httpClient: OkHttpClient? = null,
) {
    @Inject lateinit var genesisSnapshotService: SyncGenesisSnapshotService
    @Inject lateinit var accountService: AccountService

    constructor(
        context: Context,
        database: AndroidDatabase,
        lanListener: AndroidSyncLanSocketListener,
        registry: AndroidSyncEndpointRegistry,
        keyStore: SyncDeviceSigningKeyStore,
    ) : this(context, database, lanListener, registry, keyStore, null, null, null, null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var advertisementProvider: AndroidNsdAdvertisementProvider? = null

    /**
     * 主动刷新局域网诊断与网络权限探测状态 (B01, U01.1)
     */
    suspend fun refreshNetworkDiagnostics() {
        networkMonitor?.updateDiagnostics()
    }
    internal var discoveryProvider: AndroidNsdDiscoveryProvider? = null

    private fun getAdvertisement(): AndroidNsdAdvertisementProvider {
        return advertisementProvider ?: AndroidNsdAdvertisementProvider(context).also { advertisementProvider = it }
    }

    private fun getDiscovery(): AndroidNsdDiscoveryProvider {
        return discoveryProvider ?: AndroidNsdDiscoveryProvider(context).also { discoveryProvider = it }
    }

    private val lanPreferences =
        context.getSharedPreferences("origread_sync_runtime", Context.MODE_PRIVATE)
    private val initialLanRequested = lanPreferences.getBoolean(PREF_LAN_REQUESTED, false)

    private val _syncState = MutableStateFlow(
        SyncManagerState(isLanRequested = initialLanRequested),
    )
    val syncState: StateFlow<SyncManagerState> = _syncState.asStateFlow()

    private val isSyncingFlag = AtomicBoolean(false)
    private val lanRequested = AtomicBoolean(initialLanRequested)
    private val lanLifecycleMutex = Mutex()
    private var lastLanNetworkSignature: String? = null

    init {
        scope.launch {
            loadDeviceIdentity()
            refreshTrustedDevices()
        }

        // 监听网络环境变化
        networkMonitor?.let { monitor ->
            scope.launch {
                combine(monitor.diagnostics, monitor.appForeground) { diag, foreground -> diag to foreground }
                    .collect { (diag, foreground) ->
                    _syncState.update { current ->
                        val peers = if (diag.isLocalNetworkAvailable) current.discoveredPeers else emptyList()
                        current.copy(
                            networkDiagnostics = diag,
                            discoveredPeers = peers,
                            lastError = if (!diag.isLocalNetworkAvailable && diag.diagnosticMessage != null) {
                                diag.diagnosticMessage
                            } else current.lastError,
                        )
                    }
                    reconcileLanRuntime(diag, foreground)
                }
            }
        }

        // 监听配对会话流
        pairingCoordinator?.let { coordinator ->
            scope.launch {
                coordinator.activeSessionsFlow.collect { sessions ->
                    val failure = sessions.firstOrNull { !it.failureMessage.isNullOrBlank() }?.failureMessage
                    val active = sessions.firstOrNull { it.status in setOf("WAITING_CONFIRMATION", "WAITING_PEER") }
                        ?: sessions.firstOrNull { it.status == "CONFIRMED" }
                    _syncState.update {
                        it.copy(
                            activePairingSession = active,
                            lastError = failure ?: if (active?.status == "CONFIRMED") null else it.lastError,
                            phase = when {
                                active != null && active.status in setOf("WAITING_CONFIRMATION", "WAITING_PEER") -> SyncStatusPhase.WAITING_CONFIRMATION
                                active != null && active.status == "CONFIRMED" -> SyncStatusPhase.COMPLETED
                                it.isSyncing -> it.phase
                                it.isDiscovering -> SyncStatusPhase.DISCOVERING
                                else -> SyncStatusPhase.IDLE
                            }
                        )
                    }
                    if (active?.status == "CONFIRMED") {
                        refreshTrustedDevices()
                    }
                }
            }
        }
    }

    /**
     * 刷新本机设备与空间绑定信息。
     */
    suspend fun loadDeviceIdentity() {
        val device = database.syncRuntimeDao().findDeviceIdentity()
        val binding = database.syncRuntimeDao().findActiveBinding()
        _syncState.update {
            it.copy(
                deviceId = device?.deviceId,
                syncSpaceId = binding?.syncSpaceId,
            )
        }
    }

    /**
     * Explicitly activates Sync for the current Local Account through the full Genesis cutover.
     * This mirrors Desktop activateGenesis() and deliberately stays separate from LAN/pairing.
     */
    suspend fun activateGenesis(): SyncGenesisCutoverResult = withContext(Dispatchers.IO) {
        _syncState.update { it.copy(phase = SyncStatusPhase.SYNCING_SNAPSHOT, lastError = null) }
        try {
            val accountId = accountService.getCurrentAccountId()
            val account = requireNotNull(database.accountDao().queryById(accountId)) {
                "Current account does not exist"
            }
            require(account.type.id == AccountType.Local.id) {
                "Multi-device Sync can only be activated for a Local Account"
            }
            val result = genesisSnapshotService.run(localAccountId = accountId)
            loadDeviceIdentity()
            refreshTrustedDevices()
            _syncState.update { it.copy(phase = SyncStatusPhase.COMPLETED, lastError = null) }
            result
        } catch (failure: Throwable) {
            _syncState.update {
                it.copy(
                    phase = SyncStatusPhase.FAILED,
                    lastError = failure.message ?: failure.javaClass.simpleName,
                )
            }
            throw failure
        }
    }

    /**
     * 刷新已受信任设备列表。
     */
    suspend fun refreshTrustedDevices(): List<SyncTrustedDeviceEntity> {
        val spaceId = _syncState.value.syncSpaceId ?: return emptyList()
        val list = try {
            database.syncTrustedDeviceDao().listBySpace(spaceId)
        } catch (_: Throwable) {
            emptyList()
        }
        _syncState.update { it.copy(trustedDevices = list) }
        return list
    }

    /**
     * 切换局域网同步监听与广播状态。
     *
     * @param enabled true 开启监听与广播；false 停止并释放资源
     */
    fun setLanSyncEnabled(enabled: Boolean): kotlinx.coroutines.Job = scope.launch {
        try {
            // This switch represents user intent rather than the lifetime of the current socket.
            // Persist it so process death/app upgrade cannot silently disable LAN sync. Runtime
            // activation is still gated by foreground, permission and LAN availability below.
            lanPreferences.edit().putBoolean(PREF_LAN_REQUESTED, enabled).apply()
            lanRequested.set(enabled)
            _syncState.update { it.copy(isLanRequested = enabled) }
            if (networkMonitor == null) {
                lanLifecycleMutex.withLock {
                    if (enabled) startLanRuntime(null) else stopLanRuntime(null)
                }
            } else {
                val diag = networkMonitor.updateDiagnostics()
                reconcileLanRuntime(diag, networkMonitor.appForeground.value)
            }
        } catch (t: Throwable) {
            _syncState.update { it.copy(lastError = t.message ?: "Failed to toggle LAN") }
        }
    }

    private suspend fun reconcileLanRuntime(diag: LanNetworkDiagnostics, appForeground: Boolean) {
        lanLifecycleMutex.withLock {
            val requested = lanRequested.get()
            val networkSignature =
                listOf(
                    diag.networkType,
                    diag.selectedInterface.orEmpty(),
                    diag.ipv4Addresses.sorted().joinToString(","),
                    diag.ipv6Addresses.sorted().joinToString(","),
                    diag.isVpnActive.toString(),
                ).joinToString("|")
            val runtimeAllowed = requested && appForeground && diag.isLocalNetworkAvailable
            if (!runtimeAllowed) {
                if (_syncState.value.isLanEnabled) {
                    stopLanRuntime(
                        when {
                            !requested -> null
                            !appForeground -> "LAN listener paused while OrigRead is in the background"
                            else -> diag.diagnosticMessage ?: "LAN listener paused because local network is unavailable"
                        },
                    )
                }
                lastLanNetworkSignature = networkSignature
                return
            }

            val networkChanged =
                _syncState.value.isLanEnabled &&
                    lastLanNetworkSignature != null &&
                    lastLanNetworkSignature != networkSignature
            if (networkChanged) {
                stopLanRuntime(null)
            }
            if (!_syncState.value.isLanEnabled) {
                startLanRuntime(networkSignature)
            } else {
                lastLanNetworkSignature = networkSignature
            }
        }
    }

    private suspend fun startLanRuntime(networkSignature: String?) {
        loadDeviceIdentity()
        val deviceId = requireNotNull(_syncState.value.deviceId) {
            "Sync Device Identity is not initialized"
        }
        val port = lanListener.start(0)
        val registered =
            runCatching {
                getAdvertisement().register(
                    port = port,
                    deviceId = deviceId,
                    displayName = "OrigRead Android",
                    syncSpaceIds = emptyList(),
                    tls = true,
                    fingerprint = null,
                )
            }.getOrDefault(false)
        lastLanNetworkSignature = networkSignature
        _syncState.update {
            it.copy(
                isLanRequested = lanRequested.get(),
                isLanEnabled = true,
                lanPort = port,
                lastError = if (registered) null else "NSD advertisement registration failed",
            )
        }
    }

    private suspend fun stopLanRuntime(reason: String?) {
        runCatching { getAdvertisement().unregister() }
        lanListener.stop()
        _syncState.update {
            it.copy(
                isLanRequested = lanRequested.get(),
                isLanEnabled = false,
                lanPort = null,
                discoveredPeers = emptyList(),
                lastError = reason ?: it.lastError,
            )
        }
    }

    private companion object {
        const val PREF_LAN_REQUESTED = "lan_requested"
    }

    /**
     * 执行局域网设备扫描。
     *
     * @param timeoutMs 发现超时时间（毫秒）
     * @return 发现的对等设备列表
     */
    suspend fun discoverLanPeers(timeoutMs: Long = 2_000L): List<AndroidSyncDiscoveredPeer> {
        _syncState.update { it.copy(isDiscovering = true, phase = SyncStatusPhase.DISCOVERING) }
        val result = try {
            getDiscovery().discover(timeoutMs)
        } catch (error: Throwable) {
            AndroidSyncDiscoveryResult(peers = emptyList(), diagnosticMessage = error.message)
        } finally {
            _syncState.update {
                it.copy(
                    isDiscovering = false,
                    phase = if (it.phase == SyncStatusPhase.DISCOVERING) SyncStatusPhase.IDLE else it.phase
                )
            }
        }

        _syncState.update {
            it.copy(
                discoveredPeers = result.peers,
                lastError = result.diagnosticMessage,
            )
        }

        // A signed public challenge identifies the candidate, then all probing continues over its
        // per-peer pinned TLS channel. Discovery metadata is never used as a trust decision.
        val trusted = _syncState.value.trustedDevices
        val probeErrors = mutableListOf<String>()
        if (result.peers.isNotEmpty()) withContext(Dispatchers.IO) {
            val rawClient = httpClient ?: OkHttpClient()
            for (peer in result.peers) {
                var tlsPeer: AndroidSyncPinnedLanClient? = null
                try {
                    val client = networkMonitor?.bindLanClient(rawClient, peer.host) ?: rawClient
                    tlsPeer = SyncLanTlsTransport.connect(client, SyncPairing.formatLanTlsUrl(peer.host, peer.port))
                    val matched = trusted.firstOrNull { it.deviceId == tlsPeer.identity.deviceId }
                    if (matched != null) {
                        check(matched.trustState == "TRUSTED") { "LAN peer trust has been revoked" }
                        check(matched.staticPublicKey == tlsPeer.identity.publicKeySpkiBase64) {
                            "LAN peer identity key changed; re-pair this device before syncing"
                        }
                    }
                    val healthUrl = "${tlsPeer.baseUrl}/healthz"
                    tlsPeer.client.newCall(Request.Builder().url(healthUrl).get().build()).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        check(response.isSuccessful) { "LAN health check failed: HTTP ${response.code} $body" }
                        val health = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            .parseToJsonElement(body).jsonObject
                        check(health["ok"]?.jsonPrimitive?.content == "true" &&
                            health["protocol"]?.jsonPrimitive?.content == "origread-sync-v1") {
                            "LAN peer returned an invalid health response"
                        }
                    }
                    if (matched != null) {
                        val endpointId = "lan:${matched.deviceId}"
                        val existing = registry.list().firstOrNull { it.endpointId == endpointId }
                        val newUrl = SyncPairing.formatLanTlsUrl(peer.host, peer.port)
                        if (existing == null || existing.baseUrl != newUrl || !existing.enabled) {
                            registry.save(
                                AndroidSyncEndpointConfig(
                                    endpointId = endpointId,
                                    syncSpaceId = matched.syncSpaceId,
                                    baseUrl = newUrl,
                                    transport = "LAN",
                                    enabled = true,
                                )
                            )
                        }
                        database.syncTrustedDeviceDao().updateLastSeen(
                            matched.syncSpaceId,
                            matched.deviceId,
                            System.currentTimeMillis(),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    probeErrors += "${peer.host}:${peer.port}: ${failure.message ?: failure.javaClass.simpleName}"
                } finally {
                    tlsPeer?.close()
                }
            }
        }
        if (probeErrors.isNotEmpty()) {
            _syncState.update { current -> current.copy(lastError = (listOfNotNull(result.diagnosticMessage) + probeErrors).joinToString("; ")) }
        }

        return result.peers
    }

    /**
     * 手动输入 IP / 主机名及端口进行设备探测并加入对等端候选。
     * 遵循 R11-K 规范与 B22：手动地址探测后，若为未信任设备，自动发起交互式配对与 SAS 握手；若已信任则支持直连。
     */
    suspend fun connectManualPeer(host: String, port: Int, displayName: String? = null): AndroidSyncDiscoveredPeer = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65_534) { "LAN bootstrap port must be between 1 and 65534" }

        val rawClient = httpClient ?: OkHttpClient()
        val client = networkMonitor?.bindLanClient(rawClient, host) ?: rawClient
        val tlsPeer = try {
            SyncLanTlsTransport.connect(client, SyncPairing.formatLanTlsUrl(host, port))
        } catch (failure: Throwable) {
            _syncState.update { it.copy(lastError = "PEER_UNREACHABLE: $host:$port — ${failure.message ?: failure.javaClass.simpleName}") }
            throw failure
        }
        val known = _syncState.value.trustedDevices.firstOrNull { it.deviceId == tlsPeer.identity.deviceId }
        if (known?.trustState == "TRUSTED" && known.staticPublicKey != tlsPeer.identity.publicKeySpkiBase64) {
            tlsPeer.close()
            val message = "LAN peer identity key changed; confirm a new pairing before syncing"
            _syncState.update { it.copy(lastError = message) }
            error(message)
        }
        try {
            tlsPeer.client.newCall(Request.Builder().url("${tlsPeer.baseUrl}/healthz").get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "LAN health check failed: HTTP ${response.code} $body" }
                val health = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(body).jsonObject
                check(health["ok"]?.jsonPrimitive?.content == "true" &&
                    health["protocol"]?.jsonPrimitive?.content == "origread-sync-v1") {
                    "LAN peer returned an invalid health response"
                }
            }
        } catch (failure: Throwable) {
            tlsPeer.close()
            _syncState.update { it.copy(lastError = "PEER_UNREACHABLE: $host:$port — ${failure.message ?: failure.javaClass.simpleName}") }
            throw failure
        }

        val name = displayName?.ifBlank { null } ?: "Manual-Peer-$port"
        val devId = tlsPeer.identity.deviceId
        val peer = AndroidSyncDiscoveredPeer(
            endpointId = "manual:$host:$port",
            deviceId = devId,
            displayName = name,
            host = host,
            port = port,
            tls = true,
            syncSpaceIds = emptyList(),
            fingerprint = SyncPairing.deviceFingerprint(tlsPeer.identity.publicKeySpkiBase64),
        )

        _syncState.update { current ->
            val existing = current.discoveredPeers.filterNot {
                it.deviceId == devId || (it.host == host && it.port == port)
            }
            current.copy(
                discoveredPeers = existing + peer,
                lastError = null,
            )
        }

        // 修复 B22: 检查是否已在当前空间受信任；若未信任，自动发起 SAS 配对握手
        val trusted = _syncState.value.trustedDevices
        val currentKnown = trusted.firstOrNull { it.deviceId == devId }
        val isTrusted = currentKnown?.trustState == "TRUSTED" && currentKnown.staticPublicKey == tlsPeer.identity.publicKeySpkiBase64
        if (isTrusted && currentKnown != null) {
            registry.save(
                AndroidSyncEndpointConfig(
                    endpointId = "lan:${currentKnown.deviceId}",
                    syncSpaceId = currentKnown.syncSpaceId,
                    baseUrl = tlsPeer.baseUrl,
                    transport = "LAN",
                    enabled = true,
                )
            )
        }
        tlsPeer.close()
        if (!isTrusted && pairingCoordinator != null) {
            try {
                pairingCoordinator.initiatePairing(host, port)
            } catch (failure: Throwable) {
                _syncState.update { it.copy(lastError = "Pairing failed: ${failure.message ?: failure.javaClass.simpleName}") }
                throw failure
            }
        }

        peer
    }

    /**
     * 发起对对等端的交互式配对（Authenticated Interactive Pairing）。
     */
    suspend fun initiatePairing(host: String, port: Int): ActivePairingSession? {
        val coordinator = pairingCoordinator ?: error("Pairing coordinator is not available")
        _syncState.update { it.copy(phase = SyncStatusPhase.PAIRING, lastError = null) }
        return try {
            val session = coordinator.initiatePairing(host, port)
            _syncState.update {
                it.copy(
                    activePairingSession = session,
                    phase = SyncStatusPhase.WAITING_CONFIRMATION,
                )
            }
            session
        } catch (t: Throwable) {
            _syncState.update {
                it.copy(
                    phase = SyncStatusPhase.FAILED,
                    lastError = "Pairing failed: ${t.message}",
                )
            }
            throw t
        }
    }

    /**
     * 用户点击确认双方显示的代码一致。
     */
    suspend fun confirmPairing(sessionId: String): ActivePairingSession? {
        val coordinator = pairingCoordinator ?: return null
        return try {
            val session = coordinator.confirmSession(sessionId)
            refreshTrustedDevices()
            session
        } catch (t: Throwable) {
            _syncState.update { it.copy(lastError = "Confirm failed: ${t.message}") }
            throw t
        }
    }

    /**
     * 用户取消配对。
     */
    suspend fun cancelPairing(sessionId: String, reason: String = "USER_CANCELLED") {
        pairingCoordinator?.cancelSession(sessionId, reason)
        _syncState.update {
            it.copy(
                activePairingSession = null,
                phase = SyncStatusPhase.IDLE,
            )
        }
    }

    /**
     * 撤销受信任设备（R11-G / R11-H）。
     * 严格遵循 R10 单 OWNER 规则：只有 OWNER 设备有权限创建 MEMBER_REVOKE 并写入 AUTH Ledger。
     */
    suspend fun revokeTrustedDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val spaceId = _syncState.value.syncSpaceId ?: return@withContext false
        val localDevice = database.syncRuntimeDao().findDeviceIdentity() ?: return@withContext false

        var currentEpoch = 0L
        val authSvc = authLedgerService
        if (authSvc != null) {
            // 验证本机是否是 OWNER
            val page = authSvc.getAuthLedger(spaceId)
            val head = page.objects.lastOrNull()
            if (head?.ownerDeviceId != localDevice.deviceId) {
                _syncState.update { it.copy(lastError = "AUTH_FAILED: Only the Sync Space OWNER can revoke members") }
                return@withContext false
            }
            currentEpoch = head.authEpoch

            // 构造合规的 revokeCutoffByActorLane（修复 B11）
            val currentCoverage = database.syncInboxDao().listCoverage(spaceId)
            val cutoff: SyncCoverage = currentCoverage.groupBy { it.replicationLaneId }.mapValues { (_, rows) ->
                rows.associate { it.actorIncarnationId to it.retainedPrefix }.filterValues { it > 0L }
            }.filterValues { it.isNotEmpty() }

            // 签发并写入 MEMBER_REVOKE
            val payloadJson = SyncOperationCanonicalizer.canonicalJson(
                kotlinx.serialization.json.buildJsonObject {
                    put("targetDeviceId", deviceId)
                }.toString()
            )
            val revokeObject = SyncAuthWireCodec.sign(
                SyncAuthProtocolObject(
                    authObjectId = "pending",
                    syncSpaceId = spaceId,
                    authEpoch = head.authEpoch,
                    authSequence = head.authSequence + 1L,
                    objectType = SyncAuthObjectType.MEMBER_REVOKE,
                    authorDeviceId = localDevice.deviceId,
                    ownerDeviceId = head.ownerDeviceId,
                    targetDeviceId = deviceId,
                    revokeCutoffByActorLane = cutoff,
                    payloadJson = payloadJson,
                    payloadHash = "pending",
                    signingDigest = "pending",
                    authorSignature = "pending",
                ),
                keyStore,
            )
            val appended = runCatching {
                authSvc.appendAuthObjects(spaceId, listOf(revokeObject))
                true
            }.onFailure { e ->
                android.util.Log.e("AndroidSyncManager", "Failed to append MEMBER_REVOKE", e)
            }.getOrDefault(false)

            if (!appended) {
                _syncState.update { it.copy(lastError = "Failed to issue MEMBER_REVOKE") }
                return@withContext false
            }
        }

        // 更新本地持久化信任记录状态（修复 B11：使用正确的 authEpoch 纪元号而非系统毫秒时间戳）
        val now = System.currentTimeMillis()
        database.syncTrustedDeviceDao().updateTrustState(
            syncSpaceId = spaceId,
            deviceId = deviceId,
            state = "REVOKED",
            authEpoch = currentEpoch,
            lastSeenAt = now,
        )

        // 从端点注册表中移除对应端点
        val endpoints = registry.list()
        endpoints.filter { it.endpointId.contains(deviceId) }.forEach {
            registry.remove(it.endpointId)
        }

        refreshTrustedDevices()
        true
    }

    /**
     * 忘记/移除本地受信任设备端点配置。
     */
    suspend fun forgetTrustedDevice(deviceId: String) = withContext(Dispatchers.IO) {
        val spaceId = _syncState.value.syncSpaceId ?: return@withContext
        database.syncTrustedDeviceDao().delete(spaceId, deviceId)
        val endpoints = registry.list()
        endpoints.filter { it.endpointId.contains(deviceId) }.forEach {
            registry.remove(it.endpointId)
        }
        refreshTrustedDevices()
    }

    /**
     * 手动触发全量对等端与云端同步反熵。
     *
     * @return 同步执行摘要
     */
    suspend fun syncNow(): AndroidSyncRunSummary {
        if (!isSyncingFlag.compareAndSet(false, true)) {
            return AndroidSyncRunSummary(0, 0, emptyList())
        }
        _syncState.update {
            it.copy(
                isSyncing = true,
                phase = SyncStatusPhase.CONNECTING,
                lastError = null,
            )
        }
        try {
            val summary =
                registry.syncAll { progress ->
                    val phase =
                        when (progress.stage) {
                            "NEGOTIATING" -> SyncStatusPhase.NEGOTIATING
                            "AUTHORIZING" -> SyncStatusPhase.AUTHORIZING
                            "SYNCING_SNAPSHOT" -> SyncStatusPhase.SYNCING_SNAPSHOT
                            "SYNCING_BLOBS" -> SyncStatusPhase.SYNCING_BLOBS
                            "SYNCING_OPERATIONS" -> SyncStatusPhase.SYNCING_OPERATIONS
                            "FINALIZING" -> SyncStatusPhase.APPLYING
                            else -> SyncStatusPhase.CONNECTING
                        }
                    _syncState.update { it.copy(phase = phase) }
                }
            _syncState.update {
                it.copy(
                    lastSyncSummary = summary,
                    phase = if (summary.completed) SyncStatusPhase.COMPLETED else SyncStatusPhase.FAILED,
                    lastError = if (summary.completed) null else "Sync partially failed: ${summary.failedEndpointIds}",
                )
            }
            return summary
        } catch (error: Throwable) {
            val msg = error.message ?: "Sync execution failed"
            _syncState.update {
                it.copy(
                    phase = SyncStatusPhase.FAILED,
                    lastError = msg,
                )
            }
            throw error
        } finally {
            isSyncingFlag.set(false)
            _syncState.update { it.copy(isSyncing = false) }
        }
    }

    /**
     * 保存或更新同步端点配置（如自建云端服务器）。
     */
    suspend fun saveEndpoint(config: AndroidSyncEndpointConfig) {
        registry.save(config)
    }

    /**
     * 获取所有已配置的端点。
     */
    suspend fun listEndpoints(): List<AndroidSyncEndpointConfig> = registry.list()

    suspend fun listRunHistory(limit: Int = 100): List<SyncRunHistoryEntity> {
        val syncSpaceId = _syncState.value.syncSpaceId
            ?: database.syncRuntimeDao().findActiveBinding()?.syncSpaceId
            ?: return emptyList()
        return registry.listRunHistory(syncSpaceId, limit)
    }

    /**
     * 移除指定端点。
     */
    suspend fun removeEndpoint(endpointId: String) {
        registry.remove(endpointId)
    }

    /**
     * 注册对等端公钥与信任。
     */
    fun registerPeer(syncSpaceId: String, deviceId: String, publicKeySpkiBase64: String) {
        lanListener.registerPeer(
            syncSpaceId = syncSpaceId,
            deviceId = deviceId,
            key = SyncPeerKey(publicKeySpkiBase64 = publicKeySpkiBase64, status = "ACTIVE"),
        )
    }
}
