package me.ash.reader.ui.page.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.ai.AiContentRuleStatus
import me.ash.reader.infrastructure.ai.AiGeneratedRulePreview
import me.ash.reader.ui.page.home.reading.AiMarkdown

/** AI 规则通过本地试跑后的确认页；用户在这里明确决定是否保存。 */
@Composable
internal fun AiRulePreviewDialog(
    preview: AiGeneratedRulePreview,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    var showJson by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 700.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.ai_rule_preview_title), style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    text = stringResource(R.string.ai_rule_preview_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Column {
                            Text(
                                text = stringResource(R.string.ai_rule_local_validation_passed),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.ai_rule_preview_validation_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiRuleMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Numbers,
                        value = preview.articleCount.toString(),
                        label = stringResource(R.string.ai_rule_preview_articles),
                    )
                    AiRuleMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.CheckCircle,
                        value = preview.score.toString(),
                        label = stringResource(R.string.ai_rule_preview_score),
                    )
                    AiRuleMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.AutoAwesome,
                        value = preview.attempts.toString(),
                        label = stringResource(R.string.ai_rule_preview_attempts),
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (preview.contentStatus) {
                            AiContentRuleStatus.VERIFIED -> MaterialTheme.colorScheme.primaryContainer
                            AiContentRuleStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                            AiContentRuleStatus.SKIPPED -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.ai_rule_content_title),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when (preview.contentStatus) {
                                AiContentRuleStatus.VERIFIED -> stringResource(
                                    R.string.ai_rule_content_verified,
                                    preview.contentSampleCount,
                                )
                                AiContentRuleStatus.FAILED -> stringResource(
                                    R.string.ai_rule_content_failed,
                                    preview.contentMessage.orEmpty(),
                                )
                                AiContentRuleStatus.SKIPPED -> stringResource(
                                    R.string.ai_rule_content_skipped,
                                    preview.contentMessage.orEmpty(),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = stringResource(
                            R.string.ai_rule_preview_runtime,
                            preview.providerName,
                            preview.model,
                            preview.attempts,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (preview.finalUrl.isNotBlank() && preview.finalUrl != preview.targetUrl) {
                        Text(
                            text = stringResource(R.string.ai_rule_preview_final_url, preview.finalUrl),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    preview.sourceKind?.let { sourceKind ->
                        Text(
                            text = stringResource(R.string.ai_rule_preview_source_kind, sourceKind),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()
                if (preview.sampleTitles.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.ai_rule_sample_articles),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    preview.sampleTitles.forEach { title ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", color = MaterialTheme.colorScheme.primary)
                            Text(title, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { showJson = !showJson },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Code, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_rule_json_preview))
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        if (showJson) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                    )
                }
                if (showJson) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        SelectionContainer(modifier = Modifier.padding(12.dp)) {
                            AiMarkdown(
                                markdown = "```json\n${preview.ruleJson}\n```",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
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

@Composable
private fun AiRuleMetricCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
