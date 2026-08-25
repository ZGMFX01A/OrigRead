package me.ash.reader.llm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import me.ash.reader.R
import me.ash.reader.llm.runtime.LlmReasoningEffort
import me.ash.reader.ui.component.base.OrigReadSwitch

@HiltViewModel
class LlmAdvancedSettingsViewModel @Inject constructor(
    private val repository: LlmSettingsRepository,
) : ViewModel() {
    val settings = repository.settings

    fun setReasoningEffort(value: LlmReasoningEffort) = repository.setReasoningEffort(value)
    fun setStreamResponses(value: Boolean) = repository.setStreamResponses(value)
    fun setShowReasoning(value: Boolean) = repository.setShowReasoning(value)
    fun setContextMaxTokens(value: Int) = repository.setContextMaxTokens(value)
    fun setSkillsEnabled(value: Boolean) = repository.setSkillsEnabled(value)
    fun setWebSearchEnabled(value: Boolean) = repository.setWebSearchEnabled(value)
    fun setMcpEnabled(value: Boolean) = repository.setMcpEnabled(value)
}

/** LLM edition 专属设置区；Provider/API Key 等基础配置仍由公共 AiSettingsPage 管理。 */
@Composable
fun LlmAdvancedSettingsSection(
    onOpenSkills: (() -> Unit)? = null,
    onOpenQuickMessages: (() -> Unit)? = null,
    onOpenWebSearch: (() -> Unit)? = null,
    onOpenMcp: (() -> Unit)? = null,
    viewModel: LlmAdvancedSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var contextBudgetK by
        rememberSaveable(settings.contextMaxTokens) {
            mutableStateOf((settings.contextMaxTokens / 1_000).toString())
        }
    val contextBudgetKValue = contextBudgetK.toIntOrNull()
    val contextBudgetValid =
        contextBudgetKValue != null &&
            contextBudgetKValue * 1_000 in
                LlmSettingsRepository.MIN_CONTEXT_TOKENS..LlmSettingsRepository.MAX_CONTEXT_TOKENS

    fun commitContextBudget() {
        val value = contextBudgetK.toIntOrNull() ?: return
        val tokens = value.toLong() * 1_000L
        if (tokens in
            LlmSettingsRepository.MIN_CONTEXT_TOKENS.toLong()..
                LlmSettingsRepository.MAX_CONTEXT_TOKENS.toLong()
        ) {
            viewModel.setContextMaxTokens(tokens.toInt())
        }
    }

    OutlinedCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_settings_runtime_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.llm_settings_runtime_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.llm_settings_reasoning_effort),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    LlmReasoningEffort.AUTO,
                    LlmReasoningEffort.LOW,
                    LlmReasoningEffort.MEDIUM,
                    LlmReasoningEffort.HIGH,
                ).forEach { effort ->
                    FilterChip(
                        selected = settings.reasoningEffort == effort,
                        onClick = { viewModel.setReasoningEffort(effort) },
                        // Reasoning Effort 属于模型 API 领域术语，跨语言界面保持英文值，避免自行发明译名。
                        label = { Text(reasoningEffortLabel(effort)) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.llm_settings_reasoning_effort_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.llm_settings_stream),
                desc = stringResource(R.string.llm_settings_stream_desc),
                checked = settings.streamResponses,
                onCheckedChange = viewModel::setStreamResponses,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.llm_settings_show_reasoning),
                desc = stringResource(R.string.llm_settings_show_reasoning_desc),
                checked = settings.showReasoning,
                onCheckedChange = viewModel::setShowReasoning,
            )

            HorizontalDivider()
            Text(
                text = stringResource(R.string.llm_settings_context_budget),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = contextBudgetK,
                onValueChange = { value ->
                    val normalized = value.filter(Char::isDigit).take(4)
                    contextBudgetK = normalized
                    val parsed = normalized.toIntOrNull()
                    val tokens = parsed?.toLong()?.times(1_000L)
                    if (
                        tokens != null &&
                            tokens in
                                LlmSettingsRepository.MIN_CONTEXT_TOKENS.toLong()..
                                    LlmSettingsRepository.MAX_CONTEXT_TOKENS.toLong()
                    ) {
                        viewModel.setContextMaxTokens(tokens.toInt())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = contextBudgetK.isNotBlank() && !contextBudgetValid,
                suffix = { Text("K tokens") },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { commitContextBudget() }),
            )
            Text(
                text = stringResource(R.string.llm_settings_context_budget_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    OutlinedCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_settings_extensions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.llm_settings_skills),
                desc = stringResource(R.string.llm_settings_skills_desc),
                checked = settings.skillsEnabled,
                onCheckedChange = viewModel::setSkillsEnabled,
            )
            if (settings.skillsEnabled && onOpenSkills != null) {
                FilledTonalButton(
                    onClick = onOpenSkills,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.llm_skill_manage))
                }
            }

            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.llm_quick_messages_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.llm_quick_messages_settings_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onOpenQuickMessages != null) {
                    FilledTonalButton(
                        onClick = onOpenQuickMessages,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.llm_quick_messages_manage))
                    }
                }
            }

            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.llm_settings_web_search),
                desc = stringResource(R.string.llm_settings_web_search_desc),
                checked = settings.webSearchEnabled,
                onCheckedChange = viewModel::setWebSearchEnabled,
            )
            if (settings.webSearchEnabled && onOpenWebSearch != null) {
                FilledTonalButton(
                    onClick = onOpenWebSearch,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.llm_settings_web_search_manage))
                }
            }

            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.llm_settings_mcp),
                desc = stringResource(R.string.llm_settings_mcp_desc),
                checked = settings.mcpEnabled,
                onCheckedChange = viewModel::setMcpEnabled,
            )
            if (settings.mcpEnabled && onOpenMcp != null) {
                FilledTonalButton(
                    onClick = onOpenMcp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.llm_settings_mcp_manage))
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OrigReadSwitch(activated = checked) { onCheckedChange(!checked) }
    }
}

private fun reasoningEffortLabel(effort: LlmReasoningEffort): String =
    when (effort) {
        LlmReasoningEffort.AUTO -> "Auto"
        LlmReasoningEffort.MINIMAL -> "Minimal"
        LlmReasoningEffort.LOW -> "Low"
        LlmReasoningEffort.MEDIUM -> "Medium"
        LlmReasoningEffort.HIGH -> "High"
        LlmReasoningEffort.MAXIMUM -> "Maximum"
    }
