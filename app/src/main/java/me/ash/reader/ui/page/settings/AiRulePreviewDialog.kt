package me.ash.reader.ui.page.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.ai.AiGeneratedRulePreview
import me.ash.reader.ui.page.home.reading.AiMarkdown

/** AI 规则通过本地试跑后的确认页；用户在这里明确决定是否保存。 */
@Composable
internal fun AiRulePreviewDialog(
    preview: AiGeneratedRulePreview,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_rule_preview_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_rule_local_validation_passed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text =
                        stringResource(
                            R.string.ai_rule_preview_metrics,
                            preview.articleCount,
                            preview.score,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (preview.sampleTitles.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.ai_rule_sample_articles),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    preview.sampleTitles.forEach { title ->
                        Text("• $title", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    text = stringResource(R.string.ai_rule_json_preview),
                    style = MaterialTheme.typography.titleSmall,
                )
                SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                    AiMarkdown("```json\n${preview.ruleJson}\n```")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.ai_rule_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
