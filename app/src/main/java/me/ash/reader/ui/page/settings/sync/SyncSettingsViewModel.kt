package me.ash.reader.ui.page.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.sync.core.AndroidSyncEndpointConfig
import me.ash.reader.infrastructure.sync.core.AndroidSyncManager
import me.ash.reader.infrastructure.sync.core.SyncManagerState
import me.ash.reader.infrastructure.sync.core.SyncRunHistoryEntity

/**
 * 多端同步设置界面 ViewModel。
 *
 * 遵循 R11-R13 规范：连接 UI 操作与底层 [AndroidSyncManager]，
 * 驱动局域网监听、服务发现、Authenticated Interactive Pairing、受信任设备管理、手动 IP 连接与反熵同步执行。
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val syncManager: AndroidSyncManager,
) : ViewModel(), androidx.lifecycle.DefaultLifecycleObserver {
    val syncState: StateFlow<SyncManagerState> = syncManager.syncState

    private val _endpoints = MutableStateFlow<List<AndroidSyncEndpointConfig>>(emptyList())
    val endpoints: StateFlow<List<AndroidSyncEndpointConfig>> = _endpoints.asStateFlow()

    private val _runHistory = MutableStateFlow<List<SyncRunHistoryEntity>>(emptyList())
    val runHistory: StateFlow<List<SyncRunHistoryEntity>> = _runHistory.asStateFlow()

    init {
        loadEndpoints()
        loadRunHistory()
        refreshTrustedDevices()
    }

    /**
     * 遵循 B01 与 U01.1：当从系统权限设置返回或页面可见时，自动重新探测权限与刷新诊断
     */
    override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
        refreshDiagnostics()
    }

    /**
     * 主动触发网络诊断与权限探测刷新
     */
    fun refreshDiagnostics() {
        viewModelScope.launch {
            syncManager.networkMonitor?.updateDiagnostics()
            loadEndpoints()
            loadRunHistory()
            refreshTrustedDevices()
        }
    }

    /**
     * 加载当前已配置的所有端点。
     */
    fun loadEndpoints() {
        viewModelScope.launch {
            _endpoints.value = syncManager.listEndpoints()
        }
    }

    fun loadRunHistory() {
        viewModelScope.launch {
            _runHistory.value = syncManager.listRunHistory(50)
        }
    }

    /**
     * 刷新已受信任设备列表。
     */
    fun refreshTrustedDevices() {
        viewModelScope.launch {
            syncManager.refreshTrustedDevices()
        }
    }

    /** Explicitly establish this device's local Sync Space through the full Genesis cutover. */
    fun activateGenesis() {
        viewModelScope.launch {
            runCatching { syncManager.activateGenesis() }
            loadEndpoints()
            loadRunHistory()
            refreshTrustedDevices()
        }
    }

    /**
     * 切换局域网监听与 NSD 广播状态。
     */
    fun toggleLan(enabled: Boolean) {
        syncManager.setLanSyncEnabled(enabled)
    }

    /**
     * 触发局域网对等设备扫描。
     */
    fun scanLan() {
        viewModelScope.launch {
            syncManager.discoverLanPeers()
        }
    }

    /**
     * 手动输入 IP / 主机名及端口连接设备。
     */
    fun connectManual(host: String, port: Int, displayName: String? = null) {
        viewModelScope.launch {
            runCatching {
                syncManager.connectManualPeer(host, port, displayName)
            }
        }
    }

    /**
     * 对候选设备发起配对握手。
     */
    fun initiatePairing(host: String, port: Int) {
        viewModelScope.launch {
            runCatching {
                syncManager.initiatePairing(host, port)
            }
        }
    }

    /**
     * 用户点击确认双方 SAS 验证码一致。
     */
    fun confirmPairing(sessionId: String) {
        viewModelScope.launch {
            runCatching {
                syncManager.confirmPairing(sessionId)
            }
            loadEndpoints()
            refreshTrustedDevices()
        }
    }

    /**
     * 用户取消配对。
     */
    fun cancelPairing(sessionId: String, reason: String = "USER_CANCELLED") {
        viewModelScope.launch {
            syncManager.cancelPairing(sessionId, reason)
        }
    }

    /**
     * 撤销受信任设备（仅 OWNER 具备执行权限，走 R10 MEMBER_REVOKE）。
     */
    fun revokeTrustedDevice(deviceId: String) {
        viewModelScope.launch {
            syncManager.revokeTrustedDevice(deviceId)
            loadEndpoints()
        }
    }

    /**
     * 忘记/清除本地受信任设备端点。
     */
    fun forgetTrustedDevice(deviceId: String) {
        viewModelScope.launch {
            syncManager.forgetTrustedDevice(deviceId)
            loadEndpoints()
        }
    }

    /**
     * 手动触发全量同步。
     */
    fun syncNow() {
        viewModelScope.launch {
            runCatching { syncManager.syncNow() }
            loadEndpoints()
            loadRunHistory()
        }
    }

    /**
     * 添加或更新云端同步端点。
     */
    fun saveEndpoint(
        url: String,
        accessToken: String?,
        syncSpaceId: String,
        endpointId: String? = null,
    ) {
        viewModelScope.launch {
            val normalizedUrl = url.trim().let { value ->
                if (value.contains("://")) value else "https://$value"
            }
            val config = if (endpointId == null) {
                AndroidSyncEndpointConfig(
                    syncSpaceId = syncSpaceId,
                    baseUrl = normalizedUrl,
                    accessToken = accessToken?.ifBlank { null },
                    transport = "CLOUD",
                    enabled = true,
                )
            } else {
                AndroidSyncEndpointConfig(
                    endpointId = endpointId,
                    syncSpaceId = syncSpaceId,
                    baseUrl = normalizedUrl,
                    accessToken = accessToken?.ifBlank { null },
                    transport = "CLOUD",
                    enabled = true,
                )
            }
            syncManager.saveEndpoint(config)
            loadEndpoints()
        }
    }

    /**
     * 移除指定端点。
     */
    fun removeEndpoint(endpointId: String) {
        viewModelScope.launch {
            syncManager.removeEndpoint(endpointId)
            loadEndpoints()
            loadRunHistory()
        }
    }
}
