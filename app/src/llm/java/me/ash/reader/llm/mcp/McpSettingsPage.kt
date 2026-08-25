package me.ash.reader.llm.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.llm.runtime.LlmToolRisk
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class McpServerUiState(
    val profile: McpServerProfile,
    val catalog: McpToolCatalog? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val health: McpHealthUiState? = null,
)

/** MCP Server 的一次主动测活结果；只属于当前设置页，不持久化瞬时网络状态。 */
data class McpHealthUiState(
    val testing: Boolean = false,
    val success: Boolean? = null,
    val latencyMs: Long? = null,
    val protocolVersion: String? = null,
    val toolCount: Int? = null,
    val error: String? = null,
)

data class McpSettingsUiState(
    val servers: List<McpServerUiState> = emptyList(),
)

@HiltViewModel
class McpSettingsViewModel @Inject constructor(
    private val repository: McpServerRepository,
    private val toolRegistry: McpToolRegistry,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            McpSettingsUiState(
                servers =
                    repository.currentServers().map { profile ->
                        McpServerUiState(
                            profile = profile,
                            catalog = repository.cachedCatalog(profile.id),
                        )
                    }
            )
        )
    val uiState = _uiState.asStateFlow()

    init {
        // 先恢复本地 Tool 描述，避免每次进入设置页都强制联网；用户可主动刷新远端 Catalog。
        toolRegistry.restoreCachedTools()
        viewModelScope.launch {
            repository.servers.collect { profiles ->
                _uiState.update { current ->
                    current.copy(
                        servers =
                            profiles.map { profile ->
                                val previous = current.servers.firstOrNull { it.profile.id == profile.id }
                                McpServerUiState(
                                    profile = profile,
                                    catalog = previous?.catalog ?: repository.cachedCatalog(profile.id),
                                    refreshing = previous?.refreshing ?: false,
                                    error = previous?.error,
                                    health = previous?.health,
                                )
                            }
                    )
                }
            }
        }
    }

    fun hasBearerToken(serverId: String): Boolean = repository.hasBearerToken(serverId)

    fun hasCustomHeaders(serverId: String): Boolean = repository.hasCustomHeaders(serverId)

    /**
     * 保存 Server；返回 null 表示成功，返回字符串则由编辑 Sheet 原位展示错误。
     * 空凭据在“编辑已有配置”场景表示保留原密钥，而不是静默清空。
     */
    fun saveServer(
        existing: McpServerProfile?,
        name: String,
        endpoint: String,
        authType: McpAuthType,
        bearerToken: String,
        customHeadersText: String,
    ): String? {
        val normalizedName = name.trim()
        val normalizedEndpoint = endpoint.trim()
        if (normalizedName.isBlank()) return "MCP Server 名称不能为空"
        val parsedUrl = normalizedEndpoint.toHttpUrlOrNull()
            ?: return "Endpoint 必须是有效的 http:// 或 https:// URL"
        if (parsedUrl.scheme !in setOf("http", "https")) {
            return "Endpoint 只支持 HTTP / HTTPS"
        }

        val existingHasBearer = existing?.let { repository.hasBearerToken(it.id) } == true
        val existingHasHeaders = existing?.let { repository.hasCustomHeaders(it.id) } == true
        val parsedHeaders =
            if (customHeadersText.isBlank()) {
                emptyMap()
            } else {
                parseCustomHeaders(customHeadersText).getOrElse { return it.message ?: "Custom Headers 格式错误" }
            }

        when (authType) {
            McpAuthType.NONE -> Unit
            McpAuthType.BEARER -> {
                if (bearerToken.isBlank() && !existingHasBearer) return "Bearer Token 不能为空"
            }
            McpAuthType.CUSTOM_HEADERS -> {
                if (parsedHeaders.isEmpty() && !existingHasHeaders) return "至少填写一个 Custom Header"
            }
        }

        existing?.id?.let(::clearHealth)
        return runCatching {
                val serverId =
                    if (existing == null) {
                        repository.addServer(normalizedName, normalizedEndpoint, authType)
                    } else {
                        repository.updateServer(
                            existing.copy(
                                name = normalizedName,
                                endpoint = normalizedEndpoint,
                                authType = authType,
                            )
                        )
                        existing.id
                    }

                when (authType) {
                    McpAuthType.NONE -> {
                        repository.setBearerToken(serverId, "")
                        repository.setCustomHeaders(serverId, emptyMap())
                    }
                    McpAuthType.BEARER -> {
                        if (bearerToken.isNotBlank()) repository.setBearerToken(serverId, bearerToken)
                        repository.setCustomHeaders(serverId, emptyMap())
                    }
                    McpAuthType.CUSTOM_HEADERS -> {
                        if (parsedHeaders.isNotEmpty()) repository.setCustomHeaders(serverId, parsedHeaders)
                        repository.setBearerToken(serverId, "")
                    }
                }

                repository.clearCatalog(serverId)
                toolRegistry.unloadServer(serverId)
                refreshServer(serverId)
            }
            .exceptionOrNull()
            ?.message
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        clearHealth(serverId)
        repository.setEnabled(serverId, enabled)
        if (enabled) refreshServer(serverId) else toolRegistry.unloadServer(serverId)
    }

    fun refreshServer(serverId: String) {
        val profile = repository.server(serverId) ?: return
        if (!profile.enabled) return
        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.profile.id == serverId) it.copy(refreshing = true, error = null) else it
                    }
            )
        }
        viewModelScope.launch {
            runCatching { toolRegistry.refreshServer(serverId, forceRefresh = true) }
                .onSuccess { catalog ->
                    _uiState.update { state ->
                        state.copy(
                            servers =
                                state.servers.map {
                                    if (it.profile.id == serverId) {
                                        it.copy(catalog = catalog, refreshing = false, error = null)
                                    } else {
                                        it
                                    }
                                }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            servers =
                                state.servers.map {
                                    if (it.profile.id == serverId) {
                                        it.copy(
                                            refreshing = false,
                                            error = error.message ?: "MCP 连接失败",
                                        )
                                    } else {
                                        it
                                    }
                                }
                        )
                    }
                }
        }
    }

    /**
     * 对 Remote MCP Server 做一次真实协议级测活。
     *
     * 复用强制 Tool discovery，同时验证网络、认证、协议协商、tools/list 与响应解析；成功后同步刷新
     * Catalog，避免“测活成功但工具仍是旧缓存”的状态分裂。
     */
    fun testServer(serverId: String) {
        val profile = repository.server(serverId) ?: return
        if (!profile.enabled) return
        if (_uiState.value.servers.firstOrNull { it.profile.id == serverId }?.health?.testing == true) return
        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.profile.id == serverId) {
                            it.copy(health = McpHealthUiState(testing = true))
                        } else {
                            it
                        }
                    }
            )
        }
        viewModelScope.launch {
            val startedAt = System.nanoTime()
            runCatching { toolRegistry.refreshServer(serverId, forceRefresh = true) }
                .onSuccess { catalog ->
                    val latencyMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                    _uiState.update { state ->
                        state.copy(
                            servers =
                                state.servers.map {
                                    if (it.profile.id == serverId) {
                                        it.copy(
                                            catalog = catalog,
                                            error = null,
                                            health =
                                                McpHealthUiState(
                                                    success = true,
                                                    latencyMs = latencyMs,
                                                    protocolVersion = catalog.protocolVersion,
                                                    toolCount = catalog.tools.size,
                                                ),
                                        )
                                    } else {
                                        it
                                    }
                                }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            servers =
                                state.servers.map {
                                    if (it.profile.id == serverId) {
                                        it.copy(
                                            health =
                                                McpHealthUiState(
                                                    success = false,
                                                    error = error.message ?: "MCP 连接失败",
                                                )
                                        )
                                    } else {
                                        it
                                    }
                                }
                        )
                    }
                }
        }
    }

    fun removeServer(serverId: String) {
        toolRegistry.unloadServer(serverId)
        repository.removeServer(serverId)
    }

    /** MCP 测活结果只对当时的 Endpoint / 认证 / 启用状态有效，配置变化后不保留旧结论。 */
    private fun clearHealth(serverId: String) {
        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.profile.id == serverId) it.copy(health = null) else it
                    }
            )
        }
    }

    private fun parseCustomHeaders(raw: String): Result<Map<String, String>> =
        runCatching {
            buildMap {
                raw.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach { line ->
                        val parts = line.split(':', limit = 2)
                        require(parts.size == 2) { "Custom Header 每行格式应为 Name: value" }
                        val name = parts[0].trim()
                        val value = parts[1].trim()
                        require(name.isNotBlank() && value.isNotBlank()) { "Header 名称和值不能为空" }
                        require(name.lowercase() !in RESERVED_HEADERS) { "不能覆盖 MCP 核心 Header：$name" }
                        put(name, value)
                    }
            }
        }

    companion object {
        private val RESERVED_HEADERS =
            setOf(
                "accept",
                "content-type",
                "host",
                "mcp-protocol-version",
                "mcp-method",
                "mcp-name",
                "mcp-session-id",
            )
    }
}

/** P5-B Remote MCP 独立管理页；保持 MCP 与普通 Web Search 配置分离。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsPage(
    onBack: () -> Unit,
    viewModel: McpSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var editorProfile by remember { mutableStateOf<McpServerProfile?>(null) }
    var editorVisible by remember { mutableStateOf(false) }
    var toolsServerId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    OrigReadScaffold(
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
            )
        },
        actions = {
            FeedbackIconButton(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.llm_mcp_add_server),
                onClick = {
                    editorProfile = null
                    editorVisible = true
                },
            )
        },
        content = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    DisplayText(
                        text = stringResource(R.string.llm_mcp_title),
                        desc = stringResource(R.string.llm_mcp_desc),
                    )
                }
                if (uiState.servers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.llm_mcp_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(
                                onClick = {
                                    editorProfile = null
                                    editorVisible = true
                                }
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.llm_mcp_add_server))
                            }
                        }
                    }
                } else {
                    items(uiState.servers, key = { it.profile.id }) { server ->
                        McpServerCard(
                            state = server,
                            onEnabledChange = { viewModel.setEnabled(server.profile.id, it) },
                            onEdit = {
                                editorProfile = server.profile
                                editorVisible = true
                            },
                            onRefresh = { viewModel.refreshServer(server.profile.id) },
                            onTest = { viewModel.testServer(server.profile.id) },
                            onShowTools = { toolsServerId = server.profile.id },
                        )
                    }
                }
            }
        },
    )

    if (editorVisible) {
        val current = editorProfile
        McpServerEditorSheet(
            profile = current,
            hasBearerToken = current?.let { viewModel.hasBearerToken(it.id) } == true,
            hasCustomHeaders = current?.let { viewModel.hasCustomHeaders(it.id) } == true,
            onDismiss = { editorVisible = false },
            onSave = { name, endpoint, authType, token, headers ->
                val error =
                    viewModel.saveServer(
                        existing = current,
                        name = name,
                        endpoint = endpoint,
                        authType = authType,
                        bearerToken = token,
                        customHeadersText = headers,
                    )
                if (error == null) editorVisible = false
                error
            },
            onDelete =
                current?.let { profile ->
                    {
                        editorVisible = false
                        pendingDeleteId = profile.id
                    }
                },
        )
    }

    toolsServerId?.let { serverId ->
        uiState.servers.firstOrNull { it.profile.id == serverId }?.let { server ->
            McpToolCatalogSheet(server = server, onDismiss = { toolsServerId = null })
        }
    }

    pendingDeleteId?.let { serverId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.llm_mcp_delete_server)) },
            text = { Text(stringResource(R.string.llm_mcp_delete_server_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeServer(serverId)
                        pendingDeleteId = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun McpServerCard(
    state: McpServerUiState,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onShowTools: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.profile.endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OrigReadSwitch(activated = state.profile.enabled) {
                    onEnabledChange(!state.profile.enabled)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.llm_mcp_edit_server))
                }
            }
            HorizontalDivider()
            when {
                state.refreshing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.llm_mcp_discovering))
                    }
                }
                state.error != null -> {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.catalog != null -> {
                    Text(
                        text =
                            stringResource(
                                R.string.llm_mcp_connected_status,
                                state.catalog.protocolVersion,
                                state.catalog.tools.size,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.llm_mcp_not_discovered),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                state.health?.testing == true -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.llm_mcp_testing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                state.health?.success == true -> {
                    Text(
                        text =
                            stringResource(
                                R.string.llm_mcp_test_success,
                                state.health.latencyMs ?: 0L,
                                state.health.protocolVersion.orEmpty(),
                                state.health.toolCount ?: 0,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.health?.success == false -> {
                    Text(
                        text =
                            stringResource(
                                R.string.llm_mcp_test_failed,
                                state.health.error.orEmpty(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.catalog?.takeIf { it.tools.isNotEmpty() }?.let { catalog ->
                    TextButton(onClick = onShowTools) {
                        Icon(Icons.Outlined.Extension, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.llm_mcp_view_tools, catalog.tools.size))
                    }
                }
                TextButton(
                    onClick = onTest,
                    enabled = state.profile.enabled && !state.refreshing && state.health?.testing != true,
                ) {
                    Text(stringResource(R.string.llm_mcp_test))
                }
                TextButton(
                    onClick = onRefresh,
                    enabled = state.profile.enabled && !state.refreshing && state.health?.testing != true,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.llm_mcp_refresh))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerEditorSheet(
    profile: McpServerProfile?,
    hasBearerToken: Boolean,
    hasCustomHeaders: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, McpAuthType, String, String) -> String?,
    onDelete: (() -> Unit)?,
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var endpoint by remember(profile?.id) { mutableStateOf(profile?.endpoint.orEmpty()) }
    var authType by remember(profile?.id) { mutableStateOf(profile?.authType ?: McpAuthType.NONE) }
    var bearerToken by remember(profile?.id) { mutableStateOf("") }
    var customHeaders by remember(profile?.id) { mutableStateOf("") }
    var error by remember(profile?.id) { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text =
                    stringResource(
                        if (profile == null) R.string.llm_mcp_add_server else R.string.llm_mcp_edit_server
                    ),
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.llm_mcp_server_name)) },
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.llm_mcp_endpoint)) },
                placeholder = { Text("https://example.com/mcp") },
            )
            Text(
                text = stringResource(R.string.llm_mcp_auth),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McpAuthType.entries.forEach { type ->
                    FilterChip(
                        selected = authType == type,
                        onClick = { authType = type; error = null },
                        label = { Text(mcpAuthLabel(type)) },
                    )
                }
            }
            when (authType) {
                McpAuthType.NONE -> Unit
                McpAuthType.BEARER -> {
                    OutlinedTextField(
                        value = bearerToken,
                        onValueChange = { bearerToken = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Bearer Token") },
                        placeholder = { if (hasBearerToken) Text("••••••••") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (hasBearerToken) {
                        Text(
                            text = stringResource(R.string.llm_mcp_leave_blank_keep_secret),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                McpAuthType.CUSTOM_HEADERS -> {
                    OutlinedTextField(
                        value = customHeaders,
                        onValueChange = { customHeaders = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("Custom Headers") },
                        placeholder = { Text("X-API-Key: value\nX-Workspace: demo") },
                    )
                    Text(
                        text =
                            if (hasCustomHeaders) {
                                stringResource(R.string.llm_mcp_headers_saved_hint)
                            } else {
                                stringResource(R.string.llm_mcp_headers_hint)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FilledTonalButton(
                onClick = {
                    error = onSave(name, endpoint, authType, bearerToken, customHeaders)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.save))
            }
            onDelete?.let { delete ->
                TextButton(onClick = delete, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.llm_mcp_delete_server))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpToolCatalogSheet(
    server: McpServerUiState,
    onDismiss: () -> Unit,
) {
    val catalog = server.catalog ?: return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(server.profile.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.llm_mcp_tools_desc, catalog.protocolVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(catalog.tools, key = { it.name }) { tool ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = tool.title?.takeIf(String::isNotBlank) ?: tool.name,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = mcpRiskLabel(tool.risk),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        if (tool.title != null && tool.title != tool.name) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        tool.description.takeIf(String::isNotBlank)?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun mcpAuthLabel(type: McpAuthType): String =
    when (type) {
        McpAuthType.NONE -> stringResource(R.string.llm_mcp_auth_none)
        McpAuthType.BEARER -> "Bearer"
        McpAuthType.CUSTOM_HEADERS -> "Headers"
    }

@Composable
private fun mcpRiskLabel(risk: LlmToolRisk): String =
    when (risk) {
        LlmToolRisk.READ_ONLY -> stringResource(R.string.llm_mcp_risk_read_only)
        LlmToolRisk.SENSITIVE -> stringResource(R.string.llm_mcp_risk_sensitive)
        LlmToolRisk.WRITE -> stringResource(R.string.llm_mcp_risk_write)
    }

