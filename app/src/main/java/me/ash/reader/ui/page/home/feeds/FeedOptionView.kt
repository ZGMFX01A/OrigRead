package me.ash.reader.ui.page.home.feeds

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.ui.component.base.RYSelectionChip
import me.ash.reader.ui.component.base.Subtitle

@Composable
fun FeedOptionView(
    modifier: Modifier = Modifier,
    link: String = "",
    groups: List<Group> = emptyList(),
    selectedAllowNotificationPreset: Boolean = false,
    selectedParseFullContentPreset: Boolean = false,
    selectedOpenInBrowserPreset: Boolean = false,
    isMoveToGroup: Boolean = false,
    showGroup: Boolean = true,
    showUnsubscribe: Boolean = true,
    notSubscribeMode: Boolean = false,
    selectedGroupId: String = "",
    allowNotificationPresetOnClick: () -> Unit = {},
    parseFullContentPresetOnClick: () -> Unit = {},
    openInBrowserPresetOnClick: () -> Unit = {},
    clearArticlesOnClick: () -> Unit = {},
    unsubscribeOnClick: () -> Unit = {},
    onGroupClick: (groupId: String) -> Unit = {},
    onAddNewGroup: () -> Unit = {},
    onFeedUrlClick: () -> Unit = {},
    onFeedUrlLongClick: () -> Unit = {},
    showWebsiteParser: Boolean = false,
    websiteParserName: String = "",
    onWebsiteParserClick: () -> Unit = {},
    articleFilterCount: Int = 0,
    onArticleFilterClick: () -> Unit = {},
    sourceType: SourceType = SourceType.RSS,
    showUrl: Boolean = true,
    showArticleFilter: Boolean = true,
    scrollable: Boolean = true,
) {
    val contentModifier =
        if (scrollable) modifier.verticalScroll(rememberScrollState()) else modifier
    Column(modifier = contentModifier) {
        if (showUrl) {
            EditableUrl(text = link, onClick = onFeedUrlClick, onLongClick = onFeedUrlLongClick)
        }
        if (showWebsiteParser) {
            if (showUrl) Spacer(modifier = Modifier.height(18.dp))
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onWebsiteParserClick)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.website_parser_current, websiteParserName),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.website_parser_setting_desc),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (showArticleFilter) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onArticleFilterClick)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.source_filter_current, articleFilterCount),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.source_filter_entry_desc),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.go_to),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (showUrl || showWebsiteParser || showArticleFilter) {
            Spacer(modifier = Modifier.height(22.dp))
        }

        Preset(
            selectedAllowNotificationPreset = selectedAllowNotificationPreset,
            selectedParseFullContentPreset = selectedParseFullContentPreset,
            selectedOpenInBrowserPreset = selectedOpenInBrowserPreset,
            showUnsubscribe = showUnsubscribe,
            notSubscribeMode = notSubscribeMode,
            allowNotificationPresetOnClick = allowNotificationPresetOnClick,
            parseFullContentPresetOnClick = parseFullContentPresetOnClick,
            openInBrowserPresetOnClick = openInBrowserPresetOnClick,
            clearArticlesOnClick = clearArticlesOnClick,
            unsubscribeOnClick = unsubscribeOnClick,
            sourceType = sourceType,
        )

        if (showGroup) {
            Spacer(modifier = Modifier.height(26.dp))

            AddToGroup(
                isMoveToGroup = isMoveToGroup,
                groups = groups,
                selectedGroupId = selectedGroupId,
                onGroupClick = onGroupClick,
                onAddNewGroup = onAddNewGroup,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableUrl(text: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            modifier =
                Modifier.clip(MaterialTheme.shapes.small)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            text = text,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Preset(
    selectedAllowNotificationPreset: Boolean = false,
    selectedParseFullContentPreset: Boolean = false,
    selectedOpenInBrowserPreset: Boolean = false,
    showUnsubscribe: Boolean = true,
    notSubscribeMode: Boolean = false,
    allowNotificationPresetOnClick: () -> Unit = {},
    parseFullContentPresetOnClick: () -> Unit = {},
    openInBrowserPresetOnClick: () -> Unit = {},
    clearArticlesOnClick: () -> Unit = {},
    unsubscribeOnClick: () -> Unit = {},
    sourceType: SourceType = SourceType.RSS,
) {
    if (sourceType != SourceType.RSS) {
        Subtitle(text = stringResource(R.string.source_reading_mode))
        Spacer(modifier = Modifier.height(10.dp))
        ReadingModeOption(
            title = stringResource(R.string.read_inside_origread),
            description = stringResource(R.string.read_inside_origread_desc),
            selected = !selectedOpenInBrowserPreset,
            icon = Icons.AutoMirrored.Outlined.Article,
            onClick = {
                if (selectedOpenInBrowserPreset) openInBrowserPresetOnClick()
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        ReadingModeOption(
            title = stringResource(R.string.open_original_in_browser),
            description = stringResource(R.string.open_original_in_browser_desc),
            selected = selectedOpenInBrowserPreset,
            icon = Icons.Outlined.OpenInBrowser,
            onClick = {
                if (!selectedOpenInBrowserPreset) openInBrowserPresetOnClick()
            },
        )

        if (sourceType == SourceType.WEBSITE && !selectedOpenInBrowserPreset) {
            Spacer(modifier = Modifier.height(10.dp))
            ReadingToggle(
                title = stringResource(R.string.fetch_original_full_content),
                description = stringResource(R.string.website_full_content_desc),
                selected = selectedParseFullContentPreset,
                icon = Icons.AutoMirrored.Outlined.Article,
                onClick = parseFullContentPresetOnClick,
            )
        }
        Spacer(modifier = Modifier.height(26.dp))
    }

    Subtitle(text = stringResource(R.string.notifications))
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = allowNotificationPresetOnClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.allow_notification), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.notifications_desc),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = selectedAllowNotificationPreset,
            onCheckedChange = { allowNotificationPresetOnClick() },
        )
    }
    if (notSubscribeMode) {
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            RYSelectionChip(
                modifier = Modifier,
                content = stringResource(R.string.clear_articles),
                selected = false,
            ) {
                clearArticlesOnClick()
            }
            if (showUnsubscribe) {
                RYSelectionChip(
                    modifier = Modifier,
                    content = stringResource(R.string.unsubscribe),
                    selected = false,
                ) {
                    unsubscribeOnClick()
                }
            }
        }
    }
}

@Composable
private fun ReadingModeOption(
    title: String,
    description: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun ReadingToggle(
    title: String,
    description: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = selected,
            onCheckedChange = { onClick() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddToGroup(
    isMoveToGroup: Boolean = false,
    groups: List<Group>,
    selectedGroupId: String,
    onGroupClick: (groupId: String) -> Unit = {},
    onAddNewGroup: () -> Unit = {},
) {
    Subtitle(
        text = stringResource(if (isMoveToGroup) R.string.move_to_group else R.string.add_to_group)
    )
    Spacer(modifier = Modifier.height(10.dp))

    if (groups.size > 6) {
        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            items(groups) {
                RYSelectionChip(
                    modifier = Modifier,
                    content = it.name,
                    selected = it.id == selectedGroupId,
                ) {
                    onGroupClick(it.id)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            item { NewGroupButton(onAddNewGroup, Modifier) }
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            groups.forEach {
                RYSelectionChip(
                    modifier = Modifier,
                    content = it.name,
                    selected = it.id == selectedGroupId,
                ) {
                    onGroupClick(it.id)
                }
            }
            NewGroupButton(onAddNewGroup, Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun NewGroupButton(onAddNewGroup: () -> Unit, modifier: Modifier) {
    Row(
        modifier =
            modifier
                .height(36.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onAddNewGroup() }
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(20.dp),
            imageVector = Icons.Outlined.Add,
            contentDescription = stringResource(R.string.create_new_group),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.create_new_group),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
