package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.infrastructure.translation.displayName

/**
 * 阅读页长按翻译按钮后的统一选择器。
 * 传统翻译和 AI Provider/Model 都可以直接执行，临时选择不会修改全局默认翻译服务。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationTargetSheet(
    targets: List<TranslationTarget>,
    defaultTarget: TranslationTarget?,
    activeTarget: TranslationTarget?,
    onDismiss: () -> Unit,
    onTargetSelected: (TranslationTarget) -> Unit,
    onSetDefaultTarget: (TranslationTarget) -> Unit,
) {
    val traditionalTargets = targets.filterIsInstance<TranslationTarget.Traditional>()
    val aiTargets = targets.filterIsInstance<TranslationTarget.Ai>()
    val aiGroups = aiTargets.groupBy { it.providerId to it.providerName }
    var expandedProviderId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        ) {
            item {
                Text(
                    text = stringResource(R.string.translation_choose_method),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }

            if (traditionalTargets.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.translation_traditional_section)) }
                items(traditionalTargets) { target ->
                    TranslationTargetRow(
                        title = target.displayName,
                        checked = target == activeTarget,
                        isDefault = target == defaultTarget,
                        enabled = true,
                        onClick = {
                            onDismiss()
                            onTargetSelected(target)
                        },
                        onSetDefault = { onSetDefaultTarget(target) },
                    )
                }
            }

            if (aiGroups.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    SectionTitle(stringResource(R.string.translation_ai_section))
                }
                aiGroups.forEach { (providerKey, providerTargets) ->
                    val (providerId, providerName) = providerKey
                    val expanded = expandedProviderId == providerId
                    item(key = "ai-provider-$providerId") {
                        ListItem(
                            headlineContent = { Text(providerName) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.translation_ai_model_count,
                                        providerTargets.size,
                                    )
                                )
                            },
                            trailingContent = {
                                Icon(
                                    imageVector =
                                        if (expanded) Icons.Rounded.ExpandLess
                                        else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    expandedProviderId = if (expanded) null else providerId
                                },
                        )
                    }
                    if (expanded) {
                        items(
                            items = providerTargets,
                            key = { "ai-model-${it.providerId}-${it.model}" },
                        ) { target ->
                            TranslationTargetRow(
                                title = target.model,
                                checked = target == activeTarget,
                                isDefault = target == defaultTarget,
                                enabled = true,
                                onClick = {
                                    onDismiss()
                                    onTargetSelected(target)
                                },
                                onSetDefault = { onSetDefaultTarget(target) },
                                indent = true,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun TranslationTargetRow(
    title: String,
    checked: Boolean,
    isDefault: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    indent: Boolean = false,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent =
            if (checked) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                null
            },
        trailingContent = {
            if (isDefault) {
                Text(
                    text = stringResource(R.string.translation_default_method),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onSetDefault, enabled = enabled) {
                    Text(stringResource(R.string.translation_set_default_method))
                }
            }
        },
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = if (indent) 20.dp else 0.dp)
                .clickable(enabled = enabled, onClick = onClick),
    )
}
