package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.infrastructure.ai.normalizeAiModelName

/** 重新生成时的临时选择，不会修改设置页中的默认供应商、默认模型和默认摘要档位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSummaryOptionsSheet(
    providers: List<AiProviderProfile>,
    defaultProviderId: String,
    initialProviderId: String?,
    initialModel: String?,
    initialLength: AiSummaryLength,
    onDismiss: () -> Unit,
    /** LLM edition 才提供；让深度对话保持为摘要之后/高级动作中的第二层能力。 */
    onAskArticle: (() -> Unit)? = null,
    onGenerate: (providerId: String, model: String, length: AiSummaryLength) -> Unit,
) {
    val initialProvider =
        providers.firstOrNull { it.id == initialProviderId }
            ?: providers.firstOrNull { it.id == defaultProviderId }
            ?: providers.firstOrNull()
            ?: return
    var selectedProviderId by rememberSaveable { mutableStateOf(initialProvider.id) }
    var modelDraft by rememberSaveable {
        mutableStateOf(initialModel?.takeIf(String::isNotBlank) ?: initialProvider.defaultModel)
    }
    var selectedLength by rememberSaveable { mutableStateOf(initialLength) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val selectedProvider =
        providers.firstOrNull { it.id == selectedProviderId } ?: initialProvider
    val models =
        (selectedProvider.models + selectedProvider.defaultModel)
            .map(::normalizeAiModelName)
            .filter(String::isNotBlank)
            .distinct()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        stringResource(
                            if (onAskArticle != null) R.string.ai_reading_actions
                            else R.string.ai_summary_regenerate_options
                        ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (onAskArticle != null) {
                    IconButton(onClick = onAskArticle) {
                        Icon(
                            imageVector = Icons.Rounded.Forum,
                            contentDescription = stringResource(R.string.ai_ask_article),
                        )
                    }
                }
            }

            if (onAskArticle != null) {
                Text(
                    text = stringResource(R.string.ai_summary),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                text = stringResource(R.string.ai_summary_choose_provider),
                style = MaterialTheme.typography.titleSmall,
            )
            Box {
                OutlinedButton(
                    onClick = { providerMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (selectedProvider.id == defaultProviderId) {
                            stringResource(
                                R.string.ai_provider_default_item,
                                selectedProvider.name,
                            )
                        } else {
                            selectedProvider.name
                        }
                    )
                }
                DropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (provider.id == defaultProviderId) {
                                        stringResource(
                                            R.string.ai_provider_default_item,
                                            provider.name,
                                        )
                                    } else {
                                        provider.name
                                    }
                                )
                            },
                            onClick = {
                                providerMenuExpanded = false
                                modelMenuExpanded = false
                                selectedProviderId = provider.id
                                modelDraft = provider.defaultModel
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = modelDraft,
                onValueChange = { modelDraft = normalizeAiModelName(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.ai_summary_choose_model)) },
                supportingText = {
                    Text(stringResource(R.string.ai_summary_model_manual_hint))
                },
            )
            if (models.isNotEmpty()) {
                Box {
                    TextButton(onClick = { modelMenuExpanded = true }) {
                        Text(stringResource(R.string.ai_choose_model, models.size))
                    }
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    modelMenuExpanded = false
                                    modelDraft = model
                                },
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.ai_summary_length_this_time),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiSummaryLength.entries.forEach { length ->
                    FilterChip(
                        selected = selectedLength == length,
                        onClick = { selectedLength = length },
                        label = { Text(summaryLengthLabel(length)) },
                    )
                }
            }

            Button(
                onClick = {
                    onGenerate(
                        selectedProvider.id,
                        normalizeAiModelName(modelDraft),
                        selectedLength,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = modelDraft.isNotBlank(),
            ) {
                Text(stringResource(R.string.ai_summary_generate_with))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun summaryLengthLabel(length: AiSummaryLength): String =
    stringResource(
        when (length) {
            AiSummaryLength.BRIEF -> R.string.ai_summary_length_brief
            AiSummaryLength.STANDARD -> R.string.ai_summary_length_standard
            AiSummaryLength.DETAILED -> R.string.ai_summary_length_detailed
        }
    )
