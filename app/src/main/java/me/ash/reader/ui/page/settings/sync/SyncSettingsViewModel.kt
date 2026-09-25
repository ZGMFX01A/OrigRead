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

/**
 * 多端同步设置界面 ViewModel。
 *
 * 遵循 R11-R13 规范：连接 UI 操作与底层 [AndroidSyncManager]，驱动局域网监听、服务发现与反熵同步执行。
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val syncManager: AndroidSyncManager,
) : ViewModel() {
    val syncState: StateFlow<SyncManagerState> = syncManager.syncState

    private val _endpoints = MutableStateFlow<List<AndroidSyncEndpointConfig>>(emptyList())
    val endpoints: StateFlow<List<AndroidSyncEndpointConfig>> = _endpoints.asStateFlow()

    init {
        loadEndpoints()
    }

    /**
     * 加载当前已配置的所有端点。
     */
    fun loadEndpoints() {
        viewModelScope.launch {
            _endpoints.value = syncManager.listEndpoints()
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
     * 手动触发全量同步。
     */
    fun syncNow() {
        viewModelScope.launch {
            runCatching { syncManager.syncNow() }
            loadEndpoints()
        }
    }

    /**
     * 添加或更新云端同步端点。
     */
    fun saveEndpoint(url: String, accessToken: String?, syncSpaceId: String) {
        viewModelScope.launch {
            val config = AndroidSyncEndpointConfig(
                syncSpaceId = syncSpaceId,
                baseUrl = url,
                accessToken = accessToken?.ifBlank { null },
                transport = "CLOUD",
                enabled = true,
            )
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
        }
    }
}
