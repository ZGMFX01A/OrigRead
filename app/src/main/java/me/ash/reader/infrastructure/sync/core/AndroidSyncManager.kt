package me.ash.reader.infrastructure.sync.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.db.AndroidDatabase

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
 */
data class SyncManagerState(
    val isLanEnabled: Boolean = false,
    val lanPort: Int? = null,
    val isDiscovering: Boolean = false,
    val discoveredPeers: List<AndroidSyncDiscoveredPeer> = emptyList(),
    val isSyncing: Boolean = false,
    val lastSyncSummary: AndroidSyncRunSummary? = null,
    val lastError: String? = null,
    val deviceId: String? = null,
    val syncSpaceId: String? = null,
)

/**
 * Android 多端同步生命周期与前台操作管理器。
 *
 * 遵循 R11-R13 规范：
 * 1. 统一管理前台局域网 HTTP Socket 监听与 mDNS/NSD 服务广播的启停生命周期；
 * 2. 调度局域网对等端发现与自建云端同步服务器的配置管理；
 * 3. 作为用户操作入口（如设置中心手动立即同步、扫描配对）的核心调度者。
 */
@Singleton
class AndroidSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AndroidDatabase,
    private val lanListener: AndroidSyncLanSocketListener,
    private val registry: AndroidSyncEndpointRegistry,
    private val keyStore: SyncDeviceSigningKeyStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var advertisementProvider: AndroidNsdAdvertisementProvider? = null
    internal var discoveryProvider: AndroidNsdDiscoveryProvider? = null

    private fun getAdvertisement(): AndroidNsdAdvertisementProvider {
        return advertisementProvider ?: AndroidNsdAdvertisementProvider(context).also { advertisementProvider = it }
    }

    private fun getDiscovery(): AndroidNsdDiscoveryProvider {
        return discoveryProvider ?: AndroidNsdDiscoveryProvider(context).also { discoveryProvider = it }
    }

    private val _syncState = MutableStateFlow(SyncManagerState())
    val syncState: StateFlow<SyncManagerState> = _syncState.asStateFlow()

    private val isSyncingFlag = AtomicBoolean(false)

    init {
        scope.launch {
            loadDeviceIdentity()
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
     * 切换局域网同步监听与广播状态。
     *
     * @param enabled true 开启监听与广播；false 停止并释放资源
     */
    fun setLanSyncEnabled(enabled: Boolean): kotlinx.coroutines.Job = scope.launch {
        try {
            if (enabled) {
                loadDeviceIdentity()
                val deviceId =
                    requireNotNull(_syncState.value.deviceId) {
                        "Sync Device Identity is not initialized"
                    }
                val port = lanListener.start(0)
                val spaceId = _syncState.value.syncSpaceId.orEmpty()
                val registered = runCatching {
                    getAdvertisement().register(
                        port = port,
                        deviceId = deviceId,
                        displayName = "OrigRead-Android-$port",
                        syncSpaceIds = if (spaceId.isNotBlank()) listOf(spaceId) else emptyList(),
                        tls = false,
                        fingerprint = null,
                    )
                }.getOrDefault(false)
                _syncState.update {
                    it.copy(
                        isLanEnabled = true,
                        lanPort = port,
                        lastError = if (registered) null else "NSD advertisement registration failed",
                    )
                }
            } else {
                runCatching { getAdvertisement().unregister() }
                lanListener.stop()
                _syncState.update {
                    it.copy(
                        isLanEnabled = false,
                        lanPort = null,
                    )
                }
            }
        } catch (t: Throwable) {
            _syncState.update { it.copy(lastError = t.message ?: "Failed to toggle LAN") }
        }
    }

    /**
     * 执行局域网设备扫描。
     *
     * @param timeoutMs 发现超时时间（毫秒）
     * @return 发现的对等设备列表
     */
    suspend fun discoverLanPeers(timeoutMs: Long = 2_000L): List<AndroidSyncDiscoveredPeer> {
        _syncState.update { it.copy(isDiscovering = true) }
        val result = try {
            getDiscovery().discover(timeoutMs)
        } catch (error: Throwable) {
            AndroidSyncDiscoveryResult(peers = emptyList(), diagnosticMessage = error.message)
        } finally {
            _syncState.update { it.copy(isDiscovering = false) }
        }

        _syncState.update {
            it.copy(
                discoveredPeers = result.peers,
                lastError = result.diagnosticMessage,
            )
        }
        return result.peers
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
        _syncState.update { it.copy(isSyncing = true, lastError = null) }
        try {
            val summary = registry.syncAll()
            _syncState.update {
                it.copy(
                    lastSyncSummary = summary,
                    lastError = if (summary.completed) null else "Sync partially failed: ${summary.failedEndpointIds}",
                )
            }
            return summary
        } catch (error: Throwable) {
            val msg = error.message ?: "Sync execution failed"
            _syncState.update { it.copy(lastError = msg) }
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
