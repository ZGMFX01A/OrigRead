package me.ash.reader.llm.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold

/**
 * P6.5 Custom Instructions 编辑页。
 *
 * 长期回答偏好使用显式保存，不在每次输入时写磁盘；系统返回与顶部返回统一拦截未保存修改，
 * 避免误触后静默丢失长文本。真正的 Prompt 位置与权限边界仍由 Runtime/Transport 固定控制。
 */
@Composable
fun LlmCustomInstructionsSettingsPage(
    onBack: () -> Unit,
    viewModel: LlmAdvancedSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var draft by rememberSaveable { mutableStateOf(settings.customInstructions) }
    var discardConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val normalizedDraft = draft.trim().take(LlmSettingsRepository.MAX_CUSTOM_INSTRUCTIONS_LENGTH)
    val hasChanges = normalizedDraft != settings.customInstructions

    fun requestBack() {
        if (hasChanges) discardConfirmationVisible = true else onBack()
    }

    BackHandler { requestBack() }

    OrigReadScaffold(
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = ::requestBack,
            )
        },
        content = {
            LazyColumn(
                modifier = Modifier.imePadding().navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    DisplayText(
                        text = stringResource(R.string.llm_custom_instructions_title),
                        desc = stringResource(R.string.llm_custom_instructions_desc),
                    )
                }
                item {
                    OutlinedCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.llm_custom_instructions_priority_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = draft,
                                onValueChange = {
                                    draft =
                                        it.take(
                                            LlmSettingsRepository.MAX_CUSTOM_INSTRUCTIONS_LENGTH
                                        )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 10,
                                maxLines = 20,
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.llm_custom_instructions_editor_label
                                        )
                                    )
                                },
                                placeholder = {
                                    Text(
                                        stringResource(
                                            R.string.llm_custom_instructions_editor_placeholder
                                        )
                                    )
                                },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            R.string.llm_custom_instructions_character_count,
                                            draft.length,
                                            LlmSettingsRepository.MAX_CUSTOM_INSTRUCTIONS_LENGTH,
                                        )
                                    )
                                },
                            )
                            FilledTonalButton(
                                onClick = {
                                    viewModel.setCustomInstructions(draft)
                                    onBack()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = hasChanges,
                            ) {
                                Text(stringResource(R.string.llm_custom_instructions_save))
                            }
                        }
                    }
                }
            }
        },
    )

    if (discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { discardConfirmationVisible = false },
            title = { Text(stringResource(R.string.llm_custom_instructions_discard_title)) },
            text = { Text(stringResource(R.string.llm_custom_instructions_discard_desc)) },
            dismissButton = {
                TextButton(onClick = { discardConfirmationVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardConfirmationVisible = false
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.llm_custom_instructions_discard))
                }
            },
        )
    }
}
