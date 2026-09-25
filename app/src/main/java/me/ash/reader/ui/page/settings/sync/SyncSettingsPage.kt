package me.ash.reader.ui.page.settings.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.page.settings.SettingItem

/**
 * 多端同步设置与管理界面。
 *
 * 遵循 R11-R13 规范：
 * 1. 提供局域网对等发现与 HTTP 监听启停控制；
 * 2. 提供自建云端同步服务器的配置、保存与移除；
 * 3. 提供一键“立即同步”触发反熵运行及状态监控。
 */
@Composable
fun SyncSettingsPage(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.syncState.collectAsState()
    val endpoints by viewModel.endpoints.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    OrigReadScaffold(
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
            )
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(
                        text = stringResource(R.string.origread_sync),
                        desc = stringResource(R.string.origread_sync_desc),
                    )
                }

                // 1. 本机设备与同步空间身份
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "本机身份",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "设备 ID: ${state.deviceId ?: "未初始化"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "同步空间: ${state.syncSpaceId ?: "未绑定"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                // 2. 局域网同步监听开关
                item {
                    SettingItem(
                        title = stringResource(R.string.origread_sync_lan_listener),
                        desc = if (state.isLanEnabled) "监听运行中，端口: ${state.lanPort ?: 0}" else stringResource(R.string.origread_sync_lan_listener_desc),
                        icon = Icons.Outlined.Lan,
                        onClick = { viewModel.toggleLan(!state.isLanEnabled) },
                        action = {
                            OrigReadSwitch(
                                activated = state.isLanEnabled,
                                onClick = { viewModel.toggleLan(!state.isLanEnabled) },
                            )
                        },
                    )
                }

                // 3. 扫描局域网对等设备
                item {
                    SettingItem(
                        title = stringResource(R.string.origread_sync_discover),
                        desc = if (state.isDiscovering) "正在扫描局域网..." else "发现局域网内的其他 OrigRead 节点",
                        icon = Icons.Outlined.Devices,
                        onClick = { viewModel.scanLan() },
                        action = {
                            if (state.isDiscovering) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        },
                    )
                }

                // 显示已发现的对等端列表
                if (state.discoveredPeers.isNotEmpty()) {
                    items(state.discoveredPeers) { peer ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text = peer.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "地址: ${peer.host}:${peer.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }

                // 4. 云端同步服务器端点配置
                item {
                    SettingItem(
                        title = stringResource(R.string.origread_sync_cloud_endpoint),
                        desc = "配置自建 Durable Cloud 同步服务器",
                        icon = Icons.Outlined.Add,
                        onClick = { showAddDialog = true },
                    )
                }

                // 已配置的云端端点列表
                items(endpoints) { endpoint ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = endpoint.baseUrl, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Space: ${endpoint.syncSpaceId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            FeedbackIconButton(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "删除端点",
                                onClick = { viewModel.removeEndpoint(endpoint.endpointId) },
                            )
                        }
                    }
                }

                // 5. 立即同步操作
                item {
                    SettingItem(
                        title = stringResource(R.string.origread_sync_now),
                        desc = when {
                            state.isSyncing -> "正在同步反熵..."
                            state.lastSyncSummary != null -> "最近同步: 尝试 ${state.lastSyncSummary?.attempted}，成功 ${state.lastSyncSummary?.succeeded}"
                            else -> "对所有已启用的端点立即触发同步"
                        },
                        icon = Icons.Outlined.Sync,
                        onClick = { viewModel.syncNow() },
                        action = {
                            if (state.isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        },
                    )
                }

                // 错误提示
                if (!state.lastError.isNullOrBlank()) {
                    item {
                        Text(
                            text = state.lastError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }

            // 添加云端端点弹窗
            if (showAddDialog) {
                var urlText by remember { mutableStateOf("http://") }
                var tokenText by remember { mutableStateOf("") }
                var spaceText by remember { mutableStateOf(state.syncSpaceId.orEmpty()) }

                Dialog(onDismissRequest = { showAddDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "添加云端同步服务器", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = urlText,
                                onValueChange = { urlText = it },
                                label = { Text("服务器 URL (如 http://192.168.1.10:8787)") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = spaceText,
                                onValueChange = { spaceText = it },
                                label = { Text("同步空间 ID") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tokenText,
                                onValueChange = { tokenText = it },
                                label = { Text("Access Token (可选)") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(onClick = { showAddDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (urlText.isNotBlank() && spaceText.isNotBlank()) {
                                            viewModel.saveEndpoint(urlText.trim(), tokenText.trim(), spaceText.trim())
                                            showAddDialog = false
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.confirm))
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
