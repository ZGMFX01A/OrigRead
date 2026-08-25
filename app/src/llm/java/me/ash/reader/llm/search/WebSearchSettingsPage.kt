package me.ash.reader.llm.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch

data class WebSearchSettingsUiState(
    val settings: WebSearchSettings = WebSearchSettings(),
    val keyDrafts: Map<String, String> = emptyMap(),
    val keySavedProviderId: String? = null,
    val healthChecks: Map<String, WebSearchHealthUiState> = emptyMap(),
)

/** 设置页生命周期内的临时测活状态；不持久化瞬时网络故障。 */
data class WebSearchHealthUiState(
    val testing: Boolean = false,
    val success: Boolean? = null,
    val latencyMs: Long? = null,
    val resultCount: Int? = null,
    val error: String? = null,
)

@HiltViewModel
class WebSearchSettingsViewModel @Inject constructor(
    private val repository: WebSearchRepository,
    private val searchService: WebSearchService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WebSearchSettingsUiState(settings = repository.current()))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update { state -> state.copy(settings = settings) }
            }
        }
    }

    fun addProvider(kind: WebSearchProviderKind) {
        repository.addProvider(kind)
    }

    fun removeProvider(providerId: String) {
        repository.removeProvider(providerId)
        _uiState.update {
            it.copy(
                keyDrafts = it.keyDrafts - providerId,
                healthChecks = it.healthChecks - providerId,
            )
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        clearHealth(providerId)
        repository.setProviderEnabled(providerId, enabled)
    }

    fun setProviderEndpoint(providerId: String, endpoint: String) {
        clearHealth(providerId)
        repository.setProviderEndpoint(providerId, endpoint)
    }

    fun setDefaultProvider(providerId: String) = repository.setDefaultProvider(providerId)

    fun setKeyDraft(providerId: String, value: String) {
        _uiState.update {
            it.copy(
                keyDrafts = it.keyDrafts + (providerId to value),
                keySavedProviderId = null,
            )
        }
    }

    fun saveKey(providerId: String) {
        val draft = _uiState.value.keyDrafts[providerId].orEmpty().trim()
        // 已有 Key 时，空输入框只是“未输入新 Key”，不能因为误点保存就静默清除凭据。
        if (draft.isBlank() && repository.hasApiKey(providerId)) return
        clearHealth(providerId)
        repository.setApiKey(providerId, draft)
        _uiState.update {
            it.copy(
                keyDrafts = it.keyDrafts + (providerId to ""),
                keySavedProviderId = providerId,
            )
        }
    }

    fun hasApiKey(providerId: String): Boolean = repository.hasApiKey(providerId)

    /** 使用当前已保存配置执行一次真实最小搜索。 */
    fun testProvider(providerId: String) {
        if (_uiState.value.healthChecks[providerId]?.testing == true) return
        _uiState.update { state ->
            state.copy(
                healthChecks =
                    state.healthChecks +
                        (providerId to WebSearchHealthUiState(testing = true))
            )
        }
        viewModelScope.launch {
            runCatching { searchService.checkHealth(providerId) }
                .onSuccess { result ->
                    _uiState.update { state ->
                        state.copy(
                            healthChecks =
                                state.healthChecks +
                                    (providerId to
                                        WebSearchHealthUiState(
                                            success = true,
                                            latencyMs = result.latencyMs,
                                            resultCount = result.resultCount,
                                        ))
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            healthChecks =
                                state.healthChecks +
                                    (providerId to
                                        WebSearchHealthUiState(
                                            success = false,
                                            error = error.message ?: "Web Search 连接失败",
                                        ))
                        )
                    }
                }
        }
    }

    /** Provider 的可用性只对应测活时那一份配置；配置变化后旧结果必须立即失效。 */
    private fun clearHealth(providerId: String) {
        _uiState.update { state ->
            state.copy(healthChecks = state.healthChecks - providerId)
        }
    }
}

/** P5-A Web Search 独立管理页；普通用户无需进入 MCP 即可配置 Dedicated Search。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchSettingsPage(
    onBack: () -> Unit,
    viewModel: WebSearchSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var addProviderVisible by remember { mutableStateOf(false) }
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
                contentDescription = stringResource(R.string.llm_web_search_add),
                onClick = { addProviderVisible = true },
            )
        },
        content = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    DisplayText(
                        text = stringResource(R.string.llm_web_search_title),
                        desc = stringResource(R.string.llm_web_search_desc),
                    )
                }
                if (uiState.settings.providers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.llm_web_search_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(onClick = { addProviderVisible = true }) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.llm_web_search_add))
                            }
                        }
                    }
                } else {
                    uiState.settings.providers.forEach { provider ->
                        item(key = provider.id) {
                            SearchProviderCard(
                                provider = provider,
                                isDefault = provider.id == uiState.settings.defaultProviderId,
                                hasApiKey = viewModel.hasApiKey(provider.id),
                                keyDraft = uiState.keyDrafts[provider.id].orEmpty(),
                                keySaved = uiState.keySavedProviderId == provider.id,
                                health = uiState.healthChecks[provider.id],
                                onEnabledChange = { viewModel.setProviderEnabled(provider.id, it) },
                                onEndpointChange = { viewModel.setProviderEndpoint(provider.id, it) },
                                onKeyChange = { viewModel.setKeyDraft(provider.id, it) },
                                onSaveKey = { viewModel.saveKey(provider.id) },
                                onTest = { viewModel.testProvider(provider.id) },
                                onSetDefault = { viewModel.setDefaultProvider(provider.id) },
                                onDelete = { pendingDeleteId = provider.id },
                            )
                        }
                    }
                }
            }
        },
    )

    if (addProviderVisible) {
        ModalBottomSheet(
            onDismissRequest = { addProviderVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.llm_web_search_add),
                    style = MaterialTheme.typography.headlineSmall,
                )
                WebSearchProviderKind.entries.forEach { kind ->
                    OutlinedCard(
                        modifier =
                            Modifier.fillMaxWidth().clickable {
                                viewModel.addProvider(kind)
                                addProviderVisible = false
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(Modifier.size(14.dp))
                            Column {
                                Text(kind.defaultDisplayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text =
                                        kind.defaultEndpoint.ifBlank {
                                            stringResource(R.string.llm_web_search_self_hosted_endpoint)
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }

    pendingDeleteId?.let { providerId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.llm_web_search_delete)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeProvider(providerId)
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
private fun SearchProviderCard(
    provider: WebSearchProviderProfile,
    isDefault: Boolean,
    hasApiKey: Boolean,
    keyDraft: String,
    keySaved: Boolean,
    health: WebSearchHealthUiState?,
    onEnabledChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onTest: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
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
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        provider.kind.defaultDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isDefault) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.llm_web_search_default)) })
                }
                OrigReadSwitch(activated = provider.enabled) { onEnabledChange(!provider.enabled) }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.llm_web_search_delete))
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = provider.endpoint,
                onValueChange = onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.llm_web_search_endpoint)) },
            )
            if (provider.kind.requiresApiKey) {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = onKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.llm_web_search_api_key)) },
                    placeholder = { if (hasApiKey) Text("••••••••") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            } else {
                Text(
                    text = stringResource(R.string.llm_web_search_no_api_key_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                health?.testing == true -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.llm_web_search_testing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                health?.success == true -> {
                    Text(
                        text =
                            stringResource(
                                R.string.llm_web_search_test_success,
                                health.latencyMs ?: 0L,
                                health.resultCount ?: 0,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                health?.success == false -> {
                    Text(
                        text =
                            stringResource(
                                R.string.llm_web_search_test_failed,
                                health.error.orEmpty(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (keySaved) {
                    Text(
                        stringResource(R.string.llm_web_search_key_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                Row {
                    TextButton(
                        onClick = onTest,
                        enabled = provider.enabled && health?.testing != true,
                    ) {
                        Text(stringResource(R.string.llm_web_search_test))
                    }
                    if (!isDefault && provider.enabled) {
                        TextButton(onClick = onSetDefault) {
                            Text(stringResource(R.string.llm_web_search_set_default))
                        }
                    }
                    if (provider.kind.requiresApiKey) {
                        TextButton(onClick = onSaveKey) {
                            Text(stringResource(R.string.llm_web_search_save_key))
                        }
                    }
                }
            }
        }
    }
}

