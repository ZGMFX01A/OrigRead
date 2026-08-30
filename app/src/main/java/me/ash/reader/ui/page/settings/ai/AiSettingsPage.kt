package me.ash.reader.ui.page.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.ai.AiCapabilityOverrideMode
import me.ash.reader.infrastructure.ai.AiOutputTokenLimitStyle
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.ui.component.base.Banner
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.ext.collectAsStateValue

/**
 * 展示公共 AI 阅读设置。
 *
 * Standard 版沿用默认结构；LLM 版可传入供应商配置标题并展示兼容能力卡片，
 * 并将专属运行参数、扩展内容追加在供应商相关配置之后。
 */
@Composable
fun AiSettingsPage(
    onBack: () -> Unit,
    additionalSettingsContent: (@Composable () -> Unit)? = null,
    showProviderCapabilityOverrides: Boolean = false,
    providerConfigurationTitle: String? = null,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateValue()
    val profile = state.selectedProvider ?: return
    val testSuccess = stringResource(R.string.ai_test_success)
    val testFailure = stringResource(R.string.ai_test_failed)
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    OrigReadScaffold(
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
            )
        },
        content = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    DisplayText(
                        text = stringResource(R.string.ai_settings),
                        desc = stringResource(R.string.ai_settings_desc),
                    )
                }
                item {
                    OutlinedCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.ai_enable),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.ai_enable_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OrigReadSwitch(activated = state.settings.enabled) {
                                    viewModel.setEnabled(!state.settings.enabled)
                                }
                            }
                            HorizontalDivider()
                            OutlinedTextField(
                                value = state.settings.outputLanguage,
                                onValueChange = viewModel::setOutputLanguage,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.ai_output_language)) },
                                supportingText = {
                                    Text(stringResource(R.string.ai_output_language_desc))
                                },
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.ai_summary_length),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AiSummaryLength.entries.forEach { length ->
                                        FilterChip(
                                            selected = state.settings.summaryLength == length,
                                            onClick = { viewModel.setSummaryLength(length) },
                                            label = { Text(summaryLengthName(length)) },
                                        )
                                    }
                                }
                                Text(
                                    text = summaryLengthDescription(state.settings.summaryLength),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_providers),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            FilledTonalButton(
                                onClick = viewModel::addProvider,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Text(stringResource(R.string.ai_add_provider))
                            }
                            Box {
                                OutlinedButton(
                                    onClick = { providerMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (profile.id == state.settings.defaultProviderId) {
                                            stringResource(
                                                R.string.ai_provider_default_item,
                                                profile.name,
                                            )
                                        } else {
                                            profile.name
                                        }
                                    )
                                }
                                DropdownMenu(
                                    expanded = providerMenuExpanded,
                                    onDismissRequest = { providerMenuExpanded = false },
                                ) {
                                    state.settings.providers.forEach { item ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (item.id == state.settings.defaultProviderId) {
                                                        stringResource(
                                                            R.string.ai_provider_default_item,
                                                            item.name,
                                                        )
                                                    } else {
                                                        item.name
                                                    }
                                                )
                                            },
                                            onClick = {
                                                providerMenuExpanded = false
                                                modelMenuExpanded = false
                                                viewModel.selectProvider(
                                                    providerId = item.id,
                                                    makeDefault = true,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                                if (providerConfigurationTitle != null) {
                                    Text(
                                        text = providerConfigurationTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.ai_provider_enabled),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            text = stringResource(R.string.ai_provider_enabled_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (profile.id == state.settings.defaultProviderId) {
                                        AssistChip(
                                            onClick = {},
                                            label = {
                                                Text(stringResource(R.string.ai_default_provider))
                                            },
                                        )
                                    }
                                    OrigReadSwitch(activated = profile.enabled) {
                                        viewModel.setProviderEnabled(!profile.enabled)
                                    }
                                }
                                if (profile.id != state.settings.defaultProviderId) {
                                    FilledTonalButton(
                                        onClick = viewModel::setSelectedAsDefault,
                                        enabled = profile.enabled,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.ai_set_default_provider))
                                    }
                                }
                                OutlinedTextField(
                                    value = profile.name,
                                    onValueChange = viewModel::setProviderName,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.ai_provider_name)) },
                                )
                                OutlinedTextField(
                                    value = profile.endpoint,
                                    onValueChange = viewModel::setEndpoint,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.ai_endpoint)) },
                                    supportingText = {
                                        Text(stringResource(R.string.ai_endpoint_desc))
                                    },
                                )
                                // 配置顺序遵循真实操作链：先确定 Endpoint 与凭据，再获取/选择模型。
                                OutlinedTextField(
                                    value = state.apiKeyDraft,
                                    onValueChange = viewModel::updateApiKeyDraft,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text(stringResource(R.string.ai_optional_api_key)) },
                                    supportingText = {
                                        Text(stringResource(R.string.ai_optional_api_key_desc))
                                    },
                                )
                                Button(
                                    onClick = viewModel::saveApiKey,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = state.apiKeyDraft.isNotBlank() || state.hasApiKey,
                                ) {
                                    Text(
                                        stringResource(
                                            if (state.hasApiKey && state.apiKeyDraft.isBlank()) {
                                                R.string.ai_remove_key
                                            } else {
                                                R.string.ai_save_key
                                            }
                                        )
                                    )
                                }
                                OutlinedTextField(
                                    value = state.modelDraft,
                                    onValueChange = viewModel::setModel,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.ai_default_model)) },
                                    supportingText = {
                                        Text(stringResource(R.string.ai_default_model_desc))
                                    },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        onClick = viewModel::loadModels,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled =
                                            profile.endpoint.isNotBlank() &&
                                                state.loadingModelsProviderId == null,
                                    ) {
                                        if (state.loadingModelsProviderId == profile.id) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            Text(stringResource(R.string.ai_fetch_models))
                                        }
                                    }
                                }
                                if (profile.models.isNotEmpty()) {
                                    Box {
                                        OutlinedButton(
                                            onClick = { modelMenuExpanded = true },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                                Text(
                                                    stringResource(
                                                        R.string.ai_choose_model,
                                                        profile.models.size,
                                                    )
                                                )
                                        }
                                        DropdownMenu(
                                            expanded = modelMenuExpanded,
                                            onDismissRequest = { modelMenuExpanded = false },
                                        ) {
                                            profile.models.forEach { model ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            if (model == profile.defaultModel) {
                                                                stringResource(
                                                                    R.string.ai_model_default_item,
                                                                    model,
                                                                )
                                                            } else {
                                                                model
                                                            }
                                                        )
                                                    },
                                                    onClick = {
                                                        modelMenuExpanded = false
                                                        viewModel.selectModel(model)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                state.modelLoadError?.takeIf(String::isNotBlank)?.let { error ->
                                    Text(
                                        text = stringResource(R.string.ai_fetch_models_failed, error),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                HorizontalDivider()
                            Button(
                                onClick = { viewModel.testProvider(testSuccess, testFailure) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled =
                                    profile.enabled &&
                                        profile.endpoint.isNotBlank() &&
                                        state.modelDraft.isNotBlank() &&
                                        state.testingProviderId == null,
                            ) {
                                if (state.testingProviderId == profile.id) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(stringResource(R.string.ai_test_provider))
                                }
                            }
                            if (state.settings.providers.size > 1) {
                                TextButton(
                                    onClick = viewModel::removeSelectedProvider,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = null)
                                    Text(stringResource(R.string.ai_delete_provider))
                                }
                            }
                        state.testResult?.let { ProviderTestResult(it) }
                        }
                    }
                }
                if (showProviderCapabilityOverrides) {
                    item {
                        OutlinedCard(
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.ai_provider_capabilities),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.ai_provider_capabilities_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                HorizontalDivider()
                                CapabilityOverrideRow(
                                    title = stringResource(R.string.ai_capability_streaming),
                                    description = stringResource(R.string.ai_capability_streaming_desc),
                                    value = profile.streamingCapabilityOverride,
                                    onValueChange = viewModel::setStreamingCapability,
                                )
                                CapabilityOverrideRow(
                                    title = stringResource(R.string.ai_capability_tool_calling),
                                    description = stringResource(R.string.ai_capability_tool_calling_desc),
                                    value = profile.toolCallingCapabilityOverride,
                                    onValueChange = viewModel::setToolCallingCapability,
                                )
                                CapabilityOverrideRow(
                                    title = stringResource(R.string.ai_capability_reasoning),
                                    description = stringResource(R.string.ai_capability_reasoning_desc),
                                    value = profile.reasoningCapabilityOverride,
                                    onValueChange = viewModel::setReasoningCapability,
                                )
                                OutputTokenLimitStyleRow(
                                    value = profile.outputTokenLimitStyle,
                                    onValueChange = viewModel::setOutputTokenLimitStyle,
                                )
                                CompactTextFieldSettingRow(
                                    title = stringResource(R.string.ai_context_window_tokens),
                                    description = stringResource(R.string.ai_context_window_tokens_desc),
                                    value = profile.contextWindowTokens.toString(),
                                    onValueChange = viewModel::setContextWindowTokens,
                                )
                                HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.ai_strict_stream_termination))
                                        Text(
                                            text = stringResource(R.string.ai_strict_stream_termination_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    OrigReadSwitch(
                                        activated = profile.strictStreamTermination,
                                        onClick = { viewModel.setStrictStreamTermination(!profile.strictStreamTermination) },
                                    )
                                }
                            }
                        }
                    }
                }
                additionalSettingsContent?.let { content ->
                    item { content() }
                }
                item {
                    Banner(
                        title = stringResource(R.string.ai_privacy_title),
                        desc = stringResource(R.string.ai_privacy_desc),
                    )
                }
                item {
                    TextButton(
                        onClick = viewModel::restoreDefaults,
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.restore_defaults))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        },
    )
}

@Composable
private fun summaryLengthDescription(length: AiSummaryLength): String =
    stringResource(
        when (length) {
            AiSummaryLength.BRIEF -> R.string.ai_summary_length_brief_desc
            AiSummaryLength.STANDARD -> R.string.ai_summary_length_standard_desc
            AiSummaryLength.DETAILED -> R.string.ai_summary_length_detailed_desc
        }
    )

@Composable
private fun summaryLengthName(length: AiSummaryLength): String =
    stringResource(
        when (length) {
            AiSummaryLength.BRIEF -> R.string.ai_summary_length_brief
            AiSummaryLength.STANDARD -> R.string.ai_summary_length_standard
            AiSummaryLength.DETAILED -> R.string.ai_summary_length_detailed
        }
    )

@Composable
private fun CapabilityOverrideRow(
    title: String,
    description: String,
    value: AiCapabilityOverrideMode,
    onValueChange: (AiCapabilityOverrideMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CompactDropdownButton(
            label = stringResource(value.labelRes()),
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            AiCapabilityOverrideMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.labelRes())) },
                    onClick = {
                        expanded = false
                        onValueChange(mode)
                    },
                )
            }
        }
    }
}

private fun AiCapabilityOverrideMode.labelRes(): Int =
    when (this) {
        AiCapabilityOverrideMode.AUTO -> R.string.ai_capability_auto
        AiCapabilityOverrideMode.ENABLED -> R.string.ai_capability_supported
        AiCapabilityOverrideMode.DISABLED -> R.string.ai_capability_unsupported
    }

/** Provider 输出 token 字段设置；AUTO 保留官方模型识别，自建服务默认仍发送 max_tokens。 */
@Composable
private fun OutputTokenLimitStyleRow(
    value: AiOutputTokenLimitStyle,
    onValueChange: (AiOutputTokenLimitStyle) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_output_token_limit_style),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.ai_output_token_limit_style_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CompactDropdownButton(
            label = stringResource(value.labelRes()),
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            AiOutputTokenLimitStyle.entries.forEach { style ->
                DropdownMenuItem(
                    text = { Text(stringResource(style.labelRes())) },
                    onClick = {
                        expanded = false
                        onValueChange(style)
                    },
                )
            }
        }
    }
}

/** 将设置说明和当前枚举值并排展示，减少重复按钮造成的纵向留白。 */
@Composable
private fun CompactDropdownButton(
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.widthIn(max = 136.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            content = menuContent,
        )
    }
}

/** 上下文窗口属于单值高级配置，以尾随输入框保留可直接编辑能力。 */
@Composable
private fun CompactTextFieldSettingRow(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(0.72f),
            singleLine = true,
        )
    }
}

private fun AiOutputTokenLimitStyle.labelRes(): Int =
    when (this) {
        AiOutputTokenLimitStyle.AUTO -> R.string.ai_output_token_limit_auto
        AiOutputTokenLimitStyle.MAX_TOKENS -> R.string.ai_output_token_limit_max_tokens
        AiOutputTokenLimitStyle.MAX_COMPLETION_TOKENS -> R.string.ai_output_token_limit_max_completion_tokens
    }

@Composable
private fun ProviderTestResult(result: AiProviderTestResult) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (result.success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            contentDescription = null,
            tint =
                if (result.success) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
        )
        Text(
            text = result.message,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (result.success) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
        )
    }
}

