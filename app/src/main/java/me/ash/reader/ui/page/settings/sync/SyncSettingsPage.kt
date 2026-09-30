package me.ash.reader.ui.page.settings.sync

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.sync.core.SyncStatusPhase
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.page.settings.SettingItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 多端同步设置与管理界面。
 *
 * 遵循 R11-R13 规范：
 * 1. 局域网服务发现、广播与 HTTP 监听启停控制；
 * 2. Authenticated Interactive Pairing 弹窗核对 SAS 验证码与设备指纹；
 * 3. 已受信任设备（Trusted Devices）卡片与 OWNER 撤销控制；
 * 4. 手动 IP / 主机名连接与网络诊断信息面板；
 * 5. 自建云端同步服务器的配置、保存与移除；
 * 6. 一键“立即同步”触发反熵运行及全生命周期状态监控。
 */
@Composable
fun SyncSettingsPage(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.syncState.collectAsState()
    val endpoints by viewModel.endpoints.collectAsState()
    val runHistory by viewModel.runHistory.collectAsState()
    val context = LocalContext.current
    val uiPreferences = remember(context) {
        context.getSharedPreferences("origread_sync_ui", android.content.Context.MODE_PRIVATE)
    }
    var advancedMode by remember {
        mutableStateOf(uiPreferences.getBoolean("advanced_mode", false))
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(viewModel)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(viewModel)
        }
    }

    var showAddCloudDialog by remember { mutableStateOf(false) }
    var showManualConnectDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }
    var editingCloudEndpointId by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var simpleGenesisRequested by remember { mutableStateOf(false) }
    var simpleDiscoveryStarted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            pendingAction = null
            showPermissionRationaleDialog = true
        }
    }

    fun checkLocalNetworkPermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT < 37 || context.applicationInfo.targetSdkVersion < 37) {
            onGranted()
            return
        }
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onGranted()
        } else {
            pendingAction = onGranted
            permissionLauncher.launch(permission)
        }
    }

    fun setAdvancedMode(enabled: Boolean) {
        advancedMode = enabled
        uiPreferences.edit().putBoolean("advanced_mode", enabled).apply()
    }

    androidx.compose.runtime.LaunchedEffect(advancedMode, state.deviceId, state.syncSpaceId) {
        if (!advancedMode && (state.deviceId == null || state.syncSpaceId == null) && !simpleGenesisRequested) {
            simpleGenesisRequested = true
            viewModel.activateGenesis()
        }
        if (!advancedMode && state.deviceId != null && state.syncSpaceId != null && !simpleDiscoveryStarted) {
            checkLocalNetworkPermission {
                simpleDiscoveryStarted = true
                if (!state.isLanRequested) {
                    viewModel.toggleLan(true)
                }
                viewModel.scanLan()
            }
        }
    }

    OrigReadScaffold(
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
            )
        },
        actions = {
            TextButton(onClick = { setAdvancedMode(!advancedMode) }) {
                Text(if (advancedMode) "简洁模式" else "高级模式")
            }
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(
                        text = stringResource(R.string.origread_sync),
                        desc = if (advancedMode) {
                            "高级诊断与同步配置"
                        } else {
                            "自动发现附近设备，也可连接自己的同步服务器"
                        },
                    )
                }

                if (!advancedMode) {
                    if (state.deviceId == null || state.syncSpaceId == null) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (state.lastError.isNullOrBlank()) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (state.lastError.isNullOrBlank()) "正在准备多设备同步" else "多设备同步初始化失败",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            if (state.lastError.isNullOrBlank()) {
                                                "首次使用会自动完成初始化，无需手动配置。"
                                            } else {
                                                "可以直接重试，或进入高级模式查看详细原因。"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    if (!state.lastError.isNullOrBlank()) {
                                        TextButton(onClick = { viewModel.activateGenesis() }) {
                                            Text("重试")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("局域网设备", style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = {
                                    checkLocalNetworkPermission {
                                        if (!state.isLanRequested) viewModel.toggleLan(true)
                                        viewModel.scanLan()
                                    }
                                },
                            ) {
                                Text(if (state.isDiscovering) "搜索中…" else "重新扫描")
                            }
                        }
                    }

                    val activeTrustedDevices = state.trustedDevices.filter { it.trustState == "TRUSTED" }
                    items(activeTrustedDevices, key = { "trusted-${it.deviceId}" }) { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${device.platform} · 已连接",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                OutlinedButton(
                                    onClick = viewModel::syncNow,
                                    enabled = !state.isSyncing,
                                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                                ) {
                                    Text(if (state.isSyncing) "同步中" else "同步")
                                }
                            }
                        }
                    }

                    val nearbyUntrusted = state.discoveredPeers.filterNot { peer ->
                        activeTrustedDevices.any { it.deviceId == peer.deviceId }
                    }
                    items(nearbyUntrusted, key = { "nearby-${it.deviceId}-${it.host}-${it.port}" }) { peer ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(peer.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "附近设备",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Button(
                                    onClick = { viewModel.initiatePairing(peer.host, peer.port) },
                                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                                ) {
                                    Text("连接")
                                }
                            }
                        }
                    }

                    if (activeTrustedDevices.isEmpty() && nearbyUntrusted.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (state.isDiscovering) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Column {
                                        Text(
                                            if (state.isDiscovering) "正在查找附近的 OrigRead 设备" else "暂未发现附近设备",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            "请确保另一台设备与本机处于同一局域网。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        PaddingHeader(title = "服务器同步")
                    }

                    val cloudEndpoint = endpoints.firstOrNull { it.transport == "CLOUD" }
                    if (cloudEndpoint == null) {
                        item {
                            SettingItem(
                                title = "配置同步服务器",
                                desc = "填写域名或静态 IP、端口和密钥即可",
                                icon = Icons.Outlined.Add,
                                onClick = {
                                    editingCloudEndpointId = null
                                    showAddCloudDialog = true
                                },
                            )
                        }
                    } else {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("已连接同步服务器", style = MaterialTheme.typography.titleSmall)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        cloudEndpoint.baseUrl,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = viewModel::syncNow,
                                            enabled = !state.isSyncing,
                                        ) {
                                            Text(if (state.isSyncing) "同步中…" else "立即同步")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                editingCloudEndpointId = cloudEndpoint.endpointId
                                                showAddCloudDialog = true
                                            },
                                        ) {
                                            Text("更改配置")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.phase == SyncStatusPhase.FAILED && !state.lastError.isNullOrBlank()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("同步暂时不可用", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "可进入高级模式查看详细诊断信息。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    TextButton(onClick = { setAdvancedMode(true) }) {
                                        Text("查看详情")
                                    }
                                }
                            }
                        }
                    }
                } else {

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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "本机身份",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = when (state.phase) {
                                        SyncStatusPhase.IDLE -> "就绪"
                                        SyncStatusPhase.DISCOVERING -> "扫描中"
                                        SyncStatusPhase.PAIRING -> "配对中"
                                        SyncStatusPhase.WAITING_CONFIRMATION -> "等待核对"
                                        SyncStatusPhase.AUTHORIZING -> "授权中"
                                        SyncStatusPhase.CONNECTING -> "正在连接"
                                        SyncStatusPhase.NEGOTIATING -> "协商中"
                                        SyncStatusPhase.SYNCING_OPERATIONS -> "同步操作集"
                                        SyncStatusPhase.SYNCING_SNAPSHOT -> "同步快照"
                                        SyncStatusPhase.SYNCING_BLOBS -> "传输媒体"
                                        SyncStatusPhase.APPLYING -> "应用中"
                                        SyncStatusPhase.COMPLETED -> "已完成"
                                        SyncStatusPhase.FAILED -> "异常"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (state.phase == SyncStatusPhase.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.secondary
                                    },
                                )
                            }
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
                            if (state.deviceId == null || state.syncSpaceId == null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = viewModel::activateGenesis,
                                    enabled = state.phase != SyncStatusPhase.SYNCING_SNAPSHOT,
                                ) {
                                    if (state.phase == SyncStatusPhase.SYNCING_SNAPSHOT) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.origread_sync_activate))
                                }
                            }
                            state.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                // 2. 局域网同步监听开关
                item {
                    SettingItem(
                        title = stringResource(R.string.origread_sync_lan_listener),
                        desc = when {
                            state.isLanEnabled -> "监听运行中，端口: ${state.lanPort ?: 0}"
                            state.isLanRequested -> "局域网同步已开启，当前因后台、权限或网络状态暂停监听"
                            else -> stringResource(R.string.origread_sync_lan_listener_desc)
                        },
                        icon = Icons.Outlined.Lan,
                        onClick = {
                            checkLocalNetworkPermission {
                                viewModel.toggleLan(!state.isLanRequested)
                            }
                        },
                        action = {
                            OrigReadSwitch(
                                activated = state.isLanRequested,
                                onClick = {
                                    checkLocalNetworkPermission {
                                        viewModel.toggleLan(!state.isLanRequested)
                                    }
                                },
                            )
                        },
                    )
                }

                // 3. 局域网扫描与附近设备
                item {
                    SettingItem(
                        title = stringResource(R.string.origread_sync_discover),
                        desc = if (state.isDiscovering) "正在扫描局域网..." else "发现局域网内的其他 OrigRead 节点",
                        icon = Icons.Outlined.Devices,
                        onClick = {
                            checkLocalNetworkPermission {
                                viewModel.scanLan()
                            }
                        },
                        action = {
                            if (state.isDiscovering) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        },
                    )
                }

                // 显示扫描到的附近设备
                if (state.discoveredPeers.isNotEmpty()) {
                    items(state.discoveredPeers) { peer ->
                        val isTrusted = state.trustedDevices.any { it.deviceId == peer.deviceId && it.trustState == "TRUSTED" }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = peer.displayName, style = MaterialTheme.typography.titleSmall)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (isTrusted) {
                                            Text(
                                                text = "已信任",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    Text(
                                        text = "地址: ${peer.host}:${peer.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                if (isTrusted) {
                                    OutlinedButton(
                                        onClick = { viewModel.syncNow() },
                                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                                    ) {
                                        Text("立即同步", style = MaterialTheme.typography.labelMedium)
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.initiatePairing(peer.host, peer.port) },
                                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                                    ) {
                                        Text("发起配对", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. 手动连接设备与网络诊断入口
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { showManualConnectDialog = true },
                        ) {
                            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("手动连接设备", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { showDiagnosticsDialog = true },
                        ) {
                            Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("网络诊断", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // 5. 已受信任设备列表 (Trusted Devices)
                if (state.trustedDevices.isNotEmpty()) {
                    item {
                        PaddingHeader(title = "已受信任设备 (Trusted Devices)")
                    }

                    items(state.trustedDevices) { device ->
                        val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        val pairedTime = dateFormat.format(Date(device.pairedAt))
                        val isRevoked = device.trustState == "REVOKED"

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isRevoked) {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = device.displayName, style = MaterialTheme.typography.titleSmall)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "[${device.platform}] ${if (isRevoked) "已撤销" else "ACTIVE"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isRevoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                    Text(
                                        text = "指纹: ${device.fingerprint.take(16)}...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Text(
                                        text = "配对时间: $pairedTime",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }

                                if (!isRevoked) {
                                    OutlinedButton(
                                        onClick = { viewModel.revokeTrustedDevice(device.deviceId) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                                    ) {
                                        Text("撤销", style = MaterialTheme.typography.labelMedium)
                                    }
                                } else {
                                    FeedbackIconButton(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "忘记",
                                        onClick = { viewModel.forgetTrustedDevice(device.deviceId) },
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. 云端同步服务器端点配置
                item {
                    SettingItem(
                        title = stringResource(R.string.origread_sync_cloud_endpoint),
                        desc = "配置自建 Durable Cloud 同步服务器",
                        icon = Icons.Outlined.Add,
                        onClick = {
                            editingCloudEndpointId = null
                            showAddCloudDialog = true
                        },
                    )
                }

                // 已配置的云端端点列表
                items(endpoints.filter { it.transport == "CLOUD" }) { endpoint ->
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

                // 7. 立即同步操作
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

                if (runHistory.isNotEmpty()) {
                    item {
                        PaddingHeader(title = "同步运行历史")
                    }
                    items(runHistory.take(10)) { run ->
                        val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                        val statusText =
                            when (run.status) {
                                "SUCCEEDED" -> "成功"
                                "FAILED" -> "失败"
                                else -> "进行中"
                            }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor =
                                    if (run.status == "FAILED") {
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${run.transport ?: "SYNC"} · $statusText",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = dateFormat.format(Date(run.startedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "阶段: ${run.stage} · 重试: ${run.retryAttempt}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = "操作 ↑${run.pushedOperations} ↓${run.pulledOperations} 应用 ${run.appliedOperations} 拒绝 ${run.rejectedOperations}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Blob ↑${formatSyncBytes(run.blobBytesSent)} ↓${formatSyncBytes(run.blobBytesReceived)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!run.errorMessage.isNullOrBlank()) {
                                    Text(
                                        text = "${run.errorCode ?: "SYNC_FAILED"}: ${run.errorMessage}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                // 错误提示
                if (!state.lastError.isNullOrBlank()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = state.lastError.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                }

                item {
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }

            // 配对确认弹窗 (SAS / Fingerprint Interactive Pairing Dialog)
            state.activePairingSession?.let { session ->
                if (session.status == "WAITING_CONFIRMATION") {
                    Dialog(onDismissRequest = { viewModel.cancelPairing(session.sessionId, "USER_CANCELLED") }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Outlined.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "确认配对安全代码",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "请核对对方屏幕上的验证码与指纹是否一致",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    textAlign = TextAlign.Center,
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // SAS 大字体验证码展示区
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 24.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = session.sasCode,
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            letterSpacing = 4.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = if (advancedMode) {
                                            "对端设备: ${session.remoteDisplayName} (${session.remotePlatform})"
                                        } else {
                                            "正在连接 ${session.remoteDisplayName}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (advancedMode) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "对端指纹: ${session.remoteFingerprint}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "本机指纹: ${session.localFingerprint}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.cancelPairing(session.sessionId, "SAS_MISMATCH") },
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("不一致")
                                    }
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.confirmPairing(session.sessionId) },
                                    ) {
                                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("确认一致")
                                    }
                                }
                            }
                        }
                    }
                }
                if (session.status == "WAITING_PEER") {
                    Dialog(onDismissRequest = { viewModel.cancelPairing(session.sessionId, "USER_CANCELLED") }) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text("等待对方完成配对确认", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = session.failureMessage?.takeIf { it.isNotBlank() }
                                        ?: "本机确认已发送；在双方确认且授权数据成功保存前，不会显示配对完成。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (session.failureMessage.isNullOrBlank()) MaterialTheme.colorScheme.outline
                                    else MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.cancelPairing(session.sessionId, "USER_CANCELLED") },
                                    ) { Text("取消") }
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.confirmPairing(session.sessionId) },
                                    ) { Text("重试确认") }
                                }
                            }
                        }
                    }
                }
            }

            // 手动连接设备弹窗
            if (showManualConnectDialog) {
                var manualHost by remember { mutableStateOf("") }
                var manualPort by remember { mutableStateOf("8787") }
                var manualName by remember { mutableStateOf("") }

                Dialog(onDismissRequest = { showManualConnectDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "手动连接局域网设备", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "当 mDNS 被防火墙或路由隔离时，可直接输入对端 IP / 主机名",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = manualHost,
                                onValueChange = { manualHost = it },
                                label = { Text("IP 地址或主机名 (如 192.168.1.10)") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualPort,
                                onValueChange = { manualPort = it },
                                label = { Text("监听端口 (如 8787)") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualName,
                                onValueChange = { manualName = it },
                                label = { Text("设备备注名称 (可选)") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(onClick = { showManualConnectDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val port = manualPort.toIntOrNull() ?: 8787
                                        if (manualHost.isNotBlank()) {
                                            viewModel.connectManual(manualHost.trim(), port, manualName.trim())
                                            showManualConnectDialog = false
                                        }
                                    },
                                ) {
                                    Text("探测并连接")
                                }
                            }
                        }
                    }
                }
            }

            // 网络诊断弹窗
            if (showDiagnosticsDialog) {
                val diag = state.networkDiagnostics
                Dialog(onDismissRequest = { showDiagnosticsDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "网络环境诊断", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(text = "网络连接类型: ${diag?.networkType ?: "未知"}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "局域网权限 (ACCESS_LOCAL_NETWORK): ${if (diag?.hasLocalNetworkPermission == true) "已授权" else "被拒绝"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (diag?.hasLocalNetworkPermission == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "VPN 状态: ${if (diag?.isVpnActive == true) "激活中 (可能屏蔽组播发现)" else "未开启"}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "主网络接口: ${diag?.selectedInterface ?: "无"}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "本机 IPv4: ${diag?.ipv4Addresses?.joinToString(", ")?.ifBlank { "无" } ?: "无"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            if (diag?.diagnosticMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "诊断建议: ${diag.diagnosticMessage}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                modifier = Modifier.align(Alignment.End),
                                onClick = { showDiagnosticsDialog = false },
                            ) {
                                Text("关闭")
                            }
                        }
                    }
                }
            }

            // 添加云端端点弹窗
            if (showAddCloudDialog) {
                val editingCloudEndpoint = endpoints.firstOrNull { it.endpointId == editingCloudEndpointId }
                var urlText by remember(editingCloudEndpointId) {
                    mutableStateOf(editingCloudEndpoint?.baseUrl ?: "http://")
                }
                var tokenText by remember(editingCloudEndpointId) {
                    mutableStateOf(editingCloudEndpoint?.accessToken.orEmpty())
                }
                var spaceText by remember(editingCloudEndpointId, state.syncSpaceId) {
                    mutableStateOf(editingCloudEndpoint?.syncSpaceId ?: state.syncSpaceId.orEmpty())
                }

                Dialog(onDismissRequest = { showAddCloudDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (advancedMode) "云端同步服务器" else "同步服务器",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = urlText,
                                onValueChange = { urlText = it },
                                label = {
                                    Text(
                                        if (advancedMode) {
                                            "服务器 URL (如 http://192.168.1.10:8787)"
                                        } else {
                                            "服务器地址（域名或 IP:端口）"
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (advancedMode) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = spaceText,
                                    onValueChange = { spaceText = it },
                                    label = { Text("同步空间 ID") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tokenText,
                                onValueChange = { tokenText = it },
                                label = { Text(if (advancedMode) "Access Token (可选)" else "密钥") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(onClick = { showAddCloudDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (urlText.isNotBlank() && spaceText.isNotBlank()) {
                                            viewModel.saveEndpoint(
                                                urlText.trim(),
                                                tokenText.trim(),
                                                spaceText.trim(),
                                                editingCloudEndpointId,
                                            )
                                            showAddCloudDialog = false
                                        }
                                    },
                                ) {
                                    Text(if (advancedMode) stringResource(R.string.confirm) else "保存并连接")
                                }
                            }
                        }
                    }
                }
            }

            // 局域网访问权限拒绝说明弹窗 (U01)
            if (showPermissionRationaleDialog) {
                Dialog(onDismissRequest = { showPermissionRationaleDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "需要局域网访问权限",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Android 17+ 需要局域网访问权限（ACCESS_LOCAL_NETWORK）才能发现和连接局域网中的其他设备以完成多端同步。请在系统设置中授予该权限。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { showPermissionRationaleDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        showPermissionRationaleDialog = false
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    },
                                ) {
                                    Text("前往设置")
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PaddingHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

private fun formatSyncBytes(value: Long): String =
    when {
        value >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
        value >= 1024L -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
        else -> "$value B"
    }
