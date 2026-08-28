@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.ReadingSharePreference
import me.ash.reader.infrastructure.preference.ReadingShareTarget
import me.ash.reader.infrastructure.share.NotionShareConfiguration
import me.ash.reader.infrastructure.share.ShareTargetAvailability

@Composable
internal fun ReadingShareFirstUseSheet(
    onDismiss: () -> Unit,
    onUseDefault: () -> Unit,
    onCustomize: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShareSheetHeader(
                title = stringResource(R.string.reading_share_first_use_title),
                description = stringResource(R.string.reading_share_first_use_desc),
            )
            ShareSheetNotice(
                icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                text = stringResource(R.string.reading_share_source_always),
            )
            Button(
                onClick = onUseDefault,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reading_share_use_default))
            }
            OutlinedButton(
                onClick = onCustomize,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reading_share_customize))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun ReadingShareConfigSheet(
    initialPreference: ReadingSharePreference,
    obsidianAvailability: ShareTargetAvailability,
    siYuanAvailability: ShareTargetAvailability,
    logseqAvailability: ShareTargetAvailability,
    notionAvailable: Boolean,
    notionConfiguration: NotionShareConfiguration,
    onDismiss: () -> Unit,
    onSave: (ReadingSharePreference, notionToken: String) -> Unit,
    onOpenNotionSetup: () -> Unit,
) {
    var includeTitle by remember(initialPreference) { mutableStateOf(initialPreference.includeTitle) }
    var includeBody by remember(initialPreference) { mutableStateOf(initialPreference.includeBody) }
    var includeTranslation by remember(initialPreference) {
        mutableStateOf(initialPreference.includeTranslation)
    }
    var includeSummary by remember(initialPreference) { mutableStateOf(initialPreference.includeSummary) }
    var target by remember(initialPreference) { mutableStateOf(initialPreference.target) }
    var notionToken by remember { mutableStateOf("") }
    var notionError by remember { mutableStateOf("") }
    val savedTokenMask = remember(notionConfiguration.tokenLength) {
        "•".repeat(notionConfiguration.tokenLength.coerceAtMost(128))
    }
    val notionTokenRequiredText =
        stringResource(R.string.reading_share_notion_token_required)

    fun currentPreference() =
        ReadingSharePreference(
            isConfigured = true,
            includeTitle = includeTitle,
            includeBody = includeBody,
            includeTranslation = includeTranslation,
            includeSummary = includeSummary,
            target = target,
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ShareSheetHeader(
                title = stringResource(R.string.reading_share_config_title),
                description = stringResource(R.string.reading_share_config_desc),
            )
            Spacer(Modifier.height(4.dp))
            ReadingShareOption(
                title = stringResource(R.string.reading_share_title_option),
                checked = includeTitle,
                onCheckedChange = { includeTitle = it },
            )
            ReadingShareOption(
                title = stringResource(R.string.reading_share_body_option),
                checked = includeBody,
                onCheckedChange = { includeBody = it },
            )
            ReadingShareOption(
                title = stringResource(R.string.reading_share_translation_option),
                checked = includeTranslation,
                onCheckedChange = { includeTranslation = it },
            )
            ReadingShareOption(
                title = stringResource(R.string.reading_share_summary_option),
                checked = includeSummary,
                onCheckedChange = { includeSummary = it },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.reading_share_target_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ReadingShareOption(
                title = stringResource(R.string.reading_share_target_system),
                checked = target == ReadingShareTarget.SYSTEM,
                onCheckedChange = { if (it) target = ReadingShareTarget.SYSTEM },
            )
            if (obsidianAvailability.detected || target == ReadingShareTarget.OBSIDIAN) {
                ReadingShareOption(
                    title = stringResource(R.string.reading_share_target_obsidian),
                    checked = target == ReadingShareTarget.OBSIDIAN,
                    description =
                        if (obsidianAvailability.available) null
                        else stringResource(R.string.reading_share_target_unavailable),
                    enabled = obsidianAvailability.available,
                    onCheckedChange = { if (it) target = ReadingShareTarget.OBSIDIAN },
                )
            }
            if (siYuanAvailability.detected || target == ReadingShareTarget.SIYUAN) {
                ReadingShareOption(
                    title = stringResource(R.string.reading_share_target_siyuan),
                    checked = target == ReadingShareTarget.SIYUAN,
                    description =
                        if (siYuanAvailability.available) null
                        else stringResource(R.string.reading_share_target_unavailable),
                    enabled = siYuanAvailability.available,
                    onCheckedChange = { if (it) target = ReadingShareTarget.SIYUAN },
                )
            }
            if (logseqAvailability.detected || target == ReadingShareTarget.LOGSEQ) {
                ReadingShareOption(
                    title = stringResource(R.string.reading_share_target_logseq),
                    checked = target == ReadingShareTarget.LOGSEQ,
                    description =
                        if (logseqAvailability.available) null
                        else stringResource(R.string.reading_share_target_unavailable),
                    enabled = logseqAvailability.available,
                    onCheckedChange = { if (it) target = ReadingShareTarget.LOGSEQ },
                )
            }
            if (notionAvailable) {
                ReadingShareOption(
                    title = stringResource(R.string.reading_share_target_notion),
                    checked = target == ReadingShareTarget.NOTION,
                    onCheckedChange = { if (it) target = ReadingShareTarget.NOTION },
                )
            }
            if (target == ReadingShareTarget.NOTION) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.reading_share_notion_config_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = stringResource(R.string.reading_share_notion_config_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = stringResource(R.string.reading_share_notion_tokens_url),
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = TextDecoration.Underline,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable(onClick = onOpenNotionSetup),
                )
                OutlinedTextField(
                    value = notionToken,
                    onValueChange = {
                        notionToken = it
                        notionError = ""
                    },
                    label = { Text(stringResource(R.string.reading_share_notion_token)) },
                    placeholder = {
                        Text(
                            if (notionConfiguration.tokenConfigured && savedTokenMask.isNotBlank()) {
                                savedTokenMask
                            } else {
                                stringResource(R.string.reading_share_notion_token_placeholder)
                            },
                        )
                    },
                    supportingText = if (notionConfiguration.tokenConfigured) {
                        { Text(stringResource(R.string.reading_share_notion_token_saved)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                if (notionError.isNotBlank()) {
                    Text(
                        text = notionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ShareSheetNotice(
                icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                text = stringResource(R.string.reading_share_source_always),
            )
            Text(
                text = stringResource(R.string.reading_share_html_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        if (target == ReadingShareTarget.NOTION &&
                            !notionConfiguration.tokenConfigured &&
                            notionToken.isBlank()
                        ) {
                            notionError = notionTokenRequiredText
                        } else {
                            onSave(currentPreference(), notionToken)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.reading_share_save_only))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ShareSheetHeader(title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = null,
                modifier = Modifier.padding(10.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ReadingShareOption(
    title: String,
    checked: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        supportingContent =
            description?.let { text ->
                {
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        trailingContent = {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) },
    )
}

@Composable
private fun ShareSheetNotice(icon: @Composable () -> Unit, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
