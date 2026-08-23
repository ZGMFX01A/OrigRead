package me.ash.reader.ui.page.home.feeds.drawer.feed

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetState
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.website.AutomaticWebsiteListDetector
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.component.ChangeUrlDialog
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.RenameDialog
import me.ash.reader.ui.component.base.BottomDrawer
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.roundClick
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.interaction.alphaIndicationClickable
import me.ash.reader.ui.page.home.feeds.FeedOptionView

@Composable
fun FeedOptionDrawer(
    drawerState: ModalBottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden),
    feedOptionViewModel: FeedOptionViewModel = hiltViewModel(),
    content: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current
    val scope = rememberCoroutineScope()
    val feedOptionUiState = feedOptionViewModel.feedOptionUiState.collectAsStateValue()
    val feed = feedOptionUiState.feed
    val toastString = stringResource(R.string.rename_toast, feedOptionUiState.newName)
    var sourcePattern by remember { mutableStateOf("") }
    var sourceType by remember { mutableStateOf(ArticleFilterRuleType.KEYWORD) }


    BackHandler(drawerState.isVisible) {
        scope.launch {
            drawerState.hide()
        }
    }

    if (feedOptionUiState.sourceFilterDialogVisible) {
        AlertDialog(
            onDismissRequest = feedOptionViewModel::hideSourceFilterDialog,
            title = { Text(stringResource(R.string.source_filter_rules)) },
            text = {
                Column {
                    Row {
                        RadioButton(
                            selected = sourceType == ArticleFilterRuleType.KEYWORD,
                            onClick = { sourceType = ArticleFilterRuleType.KEYWORD },
                        )
                        Text(stringResource(R.string.filter_type_keyword), modifier = Modifier.padding(top = 12.dp, end = 12.dp))
                        RadioButton(
                            selected = sourceType == ArticleFilterRuleType.REGEX,
                            onClick = { sourceType = ArticleFilterRuleType.REGEX },
                        )
                        Text(stringResource(R.string.filter_type_regex), modifier = Modifier.padding(top = 12.dp))
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = sourcePattern,
                        onValueChange = { sourcePattern = it },
                        label = { Text(stringResource(R.string.filter_pattern)) },
                        isError = feedOptionUiState.sourceFilterError != null,
                        supportingText = { feedOptionUiState.sourceFilterError?.let { Text(it) } },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = sourcePattern.isNotBlank(),
                        onClick = {
                            if (feedOptionViewModel.addSourceFilter(sourcePattern, sourceType)) sourcePattern = ""
                        },
                    ) { Text(stringResource(R.string.add)) }
                    LazyColumn(modifier = Modifier.height(240.dp)) {
                        items(feedOptionUiState.sourceFilterRules, key = { it.id }) { rule ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.fillMaxWidth(0.65f)) {
                                    Text(rule.keyword, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (rule.type == ArticleFilterRuleType.REGEX) {
                                            stringResource(R.string.filter_type_regex)
                                        } else {
                                            stringResource(R.string.filter_type_keyword)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                OrigReadSwitch(activated = rule.enabled) {
                                    feedOptionViewModel.setSourceFilterEnabled(rule, !rule.enabled)
                                }
                                FeedbackIconButton(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = { feedOptionViewModel.deleteSourceFilter(rule) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = feedOptionViewModel::hideSourceFilterDialog) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    BottomDrawer(
        drawerState = drawerState,
        sheetContent = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                if (feedOptionUiState.websiteParserDialogVisible) {
                    WebsiteParserPanel(
                        loading = feedOptionUiState.websiteParserLoading,
                        error = feedOptionUiState.websiteParserError,
                        candidates = feedOptionUiState.websiteCandidates,
                        configuredRules = feedOptionUiState.websiteConfiguredRules,
                        preferredRuleId = feedOptionUiState.preferredWebsiteRuleId,
                        onSelect = feedOptionViewModel::selectWebsiteRule,
                        onBack = feedOptionViewModel::hideWebsiteParserDialog,
                    )
                } else if (feedOptionUiState.jsonParserDialogVisible) {
                    JsonParserPanel(
                        configuredRules = feedOptionUiState.jsonConfiguredRules,
                        onToggle = feedOptionViewModel::setJsonRuleEnabled,
                        onBack = feedOptionViewModel::hideJsonParserDialog,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        FeedIcon(modifier = Modifier.clickable {
                            feedOptionViewModel.reloadIcon()
                        }, feedName = feed?.name, iconUrl = feed?.icon, size = 24.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            modifier = Modifier.alphaIndicationClickable {
                                if (feedOptionViewModel.rssService.get().updateSubscription) {
                                    feedOptionViewModel.showRenameDialog()
                                }
                            },
                            text = feed?.name ?: stringResource(R.string.unknown),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    FeedOptionView(
                        link = feed?.url ?: stringResource(R.string.unknown),
                        groups = feedOptionUiState.groups,
                        selectedAllowNotificationPreset = feedOptionUiState.feed?.isNotification
                            ?: false,
                        selectedParseFullContentPreset = feedOptionUiState.feed?.isFullContent ?: false,
                        selectedOpenInBrowserPreset = feedOptionUiState.feed?.isBrowser ?: false,
                        isMoveToGroup = true,
                        showGroup = feedOptionViewModel.rssService.get().moveSubscription,
                        showUnsubscribe = feedOptionViewModel.rssService.get().deleteSubscription,
                        notSubscribeMode = true,
                        selectedGroupId = feedOptionUiState.feed?.groupId ?: "",
                        allowNotificationPresetOnClick = {
                            feedOptionViewModel.changeAllowNotificationPreset()
                        },
                        parseFullContentPresetOnClick = {
                            feedOptionViewModel.changeParseFullContentPreset()
                        },
                        openInBrowserPresetOnClick = {
                            feedOptionViewModel.changeOpenInBrowserPreset()
                        },
                        clearArticlesOnClick = {
                            feedOptionViewModel.showClearDialog()
                        },
                        unsubscribeOnClick = {
                            feedOptionViewModel.showDeleteDialog()
                        },
                        onGroupClick = {
                            feedOptionViewModel.selectedGroup(it)
                        },
                        onAddNewGroup = {
                            feedOptionViewModel.showNewGroupDialog()
                        },
                        onFeedUrlClick = {
                            context.openURL(feed?.url, openLink, openLinkSpecificBrowser)
                        },
                        onFeedUrlLongClick = {
                            if (feedOptionViewModel.rssService.get().updateSubscription) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                feedOptionViewModel.showFeedUrlDialog()
                            }
                        },
                        showWebsiteParser = feed?.sourceType == SourceType.WEBSITE,
                        websiteParserName =
                            feedOptionUiState.preferredWebsiteRuleId?.let { ruleId ->
                                websiteParserCurrentDisplayName(ruleId, feedOptionUiState.preferredWebsiteRuleName)
                            } ?: stringResource(R.string.website_parser_auto_with_type),
                        onWebsiteParserClick = feedOptionViewModel::showWebsiteParserDialog,
                        showJsonParser = feed?.sourceType == SourceType.JSON,
                        jsonParserName = stringResource(
                            R.string.json_parser_current_name,
                            feedOptionUiState.jsonConfiguredRules.count { it.enabled },
                        ),
                        onJsonParserClick = feedOptionViewModel::showJsonParserDialog,
                        websiteReparseLoading = feedOptionUiState.websiteReparseLoading,
                        onWebsiteReparseClick = {
                            feedOptionViewModel.reparseWebsiteArticles { result ->
                                result.fold(
                                    onSuccess = { reparse ->
                                        context.showToast(
                                            context.getString(
                                                R.string.website_reparse_articles_success,
                                                reparse.updatedCount,
                                            )
                                        )
                                    },
                                    onFailure = { error ->
                                        context.showToast(
                                            context.getString(
                                                R.string.website_reparse_articles_failed,
                                                error.message ?: context.getString(R.string.unknown_error),
                                            )
                                        )
                                    },
                                )
                            }
                        },
                        articleFilterCount = feedOptionUiState.sourceFilterRules.size,
                        onArticleFilterClick = feedOptionViewModel::showSourceFilterDialog,
                        sourceType = feed?.sourceType ?: SourceType.RSS,
                    )
                }
            }
        }
    ) {
        content()
    }

    DeleteFeedDialog(
        feedName = feed?.name ?: "",
        onConfirm = { scope.launch { drawerState.hide() } })

    ClearFeedDialog(
        feedName = feed?.name ?: "",
        onConfirm = { scope.launch { drawerState.hide() } })

    TextFieldDialog(
        visible = feedOptionUiState.newGroupDialogVisible,
        title = stringResource(R.string.create_new_group),
        icon = Icons.Outlined.CreateNewFolder,
        value = feedOptionUiState.newGroupContent,
        placeholder = stringResource(R.string.name),
        onValueChange = {
            feedOptionViewModel.inputNewGroup(it)
        },
        onDismissRequest = {
            feedOptionViewModel.hideNewGroupDialog()
        },
        onConfirm = {
            feedOptionViewModel.addNewGroup()
        }
    )

    RenameDialog(
        visible = feedOptionUiState.renameDialogVisible,
        value = feedOptionUiState.newName,
        onValueChange = {
            feedOptionViewModel.inputNewName(it)
        },
        onDismissRequest = {
            feedOptionViewModel.hideRenameDialog()
        },
        onConfirm = {
            feedOptionViewModel.renameFeed()
            scope.launch { drawerState.hide() }
            context.showToast(toastString)
        }
    )

    ChangeUrlDialog(
        visible = feedOptionUiState.changeUrlDialogVisible,
        value = feedOptionUiState.newUrl,
        onValueChange = {
            feedOptionViewModel.inputNewUrl(it)
        },
        onDismissRequest = {
            feedOptionViewModel.hideFeedUrlDialog()
        },
        onConfirm = {
            feedOptionViewModel.changeFeedUrl()
            scope.launch { drawerState.hide() }
        }
    )

}

@Composable
private fun JsonParserPanel(
    configuredRules: List<JsonRule>,
    onToggle: (JsonRule, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.json_parser_title), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.json_parser_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (configuredRules.isEmpty()) {
            WebsiteParserEmptyState(stringResource(R.string.json_parser_no_rules))
        } else {
            LazyColumn {
                items(configuredRules, key = { it.id }) { rule ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = if (rule.enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = if (rule.enabled) 2.dp else 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onToggle(rule, !rule.enabled) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = rule.enabled, onCheckedChange = { onToggle(rule, it) })
                            Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                                Text(rule.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (rule.enabled) stringResource(R.string.json_parser_rule_enabled) else stringResource(R.string.json_parser_rule_disabled),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = rule.sourceKind.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebsiteParserPanel(
    loading: Boolean,
    error: String?,
    candidates: List<me.ash.reader.infrastructure.website.WebsiteParseCandidate>,
    configuredRules: List<WebsiteRule>,
    preferredRuleId: String?,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.website_parser_title),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(
                onClick = onBack,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cancel),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
                LazyColumn(modifier = Modifier.selectableGroup()) {
                    item {
                        Text(text = error, color = MaterialTheme.colorScheme.error)
                    }
                    item {
                        WebsiteParserSectionTitle(stringResource(R.string.website_parser_section_selection))
                        WebsiteParserOption(
                            title = stringResource(R.string.website_parser_auto),
                            description = stringResource(R.string.website_parser_auto_desc),
                            selected = preferredRuleId == null,
                            onClick = { onSelect(null) },
                        )
                    }
                    item {
                        WebsiteParserSectionTitle(
                            stringResource(R.string.website_parser_section_rules_with_count, configuredRules.size),
                        )
                    }
                    if (configuredRules.isEmpty()) {
                        item {
                            WebsiteParserEmptyState(stringResource(R.string.website_parser_no_configured_rules))
                        }
                    }
                    items(configuredRules, key = { "error-configured:${it.id}" }) { rule ->
                        WebsiteParserOption(
                            title = websiteRuleDisplayName(rule.id, rule.name),
                            description = if (rule.enabled) {
                                stringResource(R.string.website_parser_rule_enabled)
                            } else {
                                stringResource(R.string.website_parser_rule_disabled)
                            },
                            selected = preferredRuleId == rule.id,
                            onClick = { onSelect(rule.id) },
                        )
                    }
                }
            }

            else -> LazyColumn(modifier = Modifier.selectableGroup()) {
                item {
                    WebsiteParserSectionTitle(stringResource(R.string.website_parser_section_selection))
                    WebsiteParserOption(
                        title = stringResource(R.string.website_parser_auto),
                        description = stringResource(R.string.website_parser_auto_desc),
                        selected = preferredRuleId == null,
                        onClick = { onSelect(null) },
                    )
                }
                val candidateByRuleId = candidates.associateBy { it.rule.id }
                val configuredRuleIds = configuredRules.mapTo(hashSetOf()) { it.id }
                val builtinCandidates = candidates.filter { it.rule.id !in configuredRuleIds }
                item {
                    WebsiteParserSectionTitle(
                        stringResource(R.string.website_parser_section_rules_with_count, configuredRules.size),
                    )
                }
                if (configuredRules.isEmpty()) {
                    item {
                        WebsiteParserEmptyState(stringResource(R.string.website_parser_no_configured_rules))
                    }
                }
                items(configuredRules, key = { "configured:${it.id}" }) { rule ->
                    val candidate = candidateByRuleId[rule.id]
                    val diagnostics = candidate?.diagnostics
                    WebsiteParserOption(
                        title = websiteRuleDisplayName(rule.id, rule.name),
                        description = when {
                            !rule.enabled -> stringResource(R.string.website_parser_rule_disabled)
                            diagnostics == null -> stringResource(R.string.website_parser_rule_enabled)
                            else -> stringResource(
                                R.string.website_parser_candidate_desc,
                                diagnostics.articleCount,
                                diagnostics.score,
                                if (diagnostics.accepted) {
                                    stringResource(R.string.website_parser_available)
                                } else {
                                    stringResource(R.string.website_parser_unavailable)
                                },
                            )
                        },
                        selected = preferredRuleId == rule.id,
                        enabled = !rule.enabled || diagnostics?.accepted == true,
                        onClick = { onSelect(rule.id) },
                    )
                }
                item {
                    WebsiteParserSectionTitle(
                        stringResource(R.string.website_parser_section_builtin_with_count, builtinCandidates.size),
                    )
                }
                if (builtinCandidates.isEmpty()) {
                    item {
                        WebsiteParserEmptyState(stringResource(R.string.website_parser_no_builtin_candidates))
                    }
                }
                // rule.id 理论上已唯一，索引作为最终保护，避免异常上游数据再次使整个界面崩溃。
                itemsIndexed(
                    builtinCandidates,
                    key = { index, candidate -> "${candidate.rule.id}:$index" },
                ) { index, candidate ->
                    val diagnostics = candidate.diagnostics
                    WebsiteParserOption(
                        title = stringResource(
                            R.string.website_parser_builtin_candidate_title,
                            index + 1,
                            websiteRuleDisplayName(candidate.rule.id, candidate.rule.name),
                        ),
                        description = stringResource(
                            R.string.website_parser_candidate_desc,
                            diagnostics.articleCount,
                            diagnostics.score,
                            if (diagnostics.accepted) {
                                stringResource(R.string.website_parser_available)
                            } else {
                                stringResource(R.string.website_parser_unavailable)
                            },
                        ),
                        selected = preferredRuleId == candidate.rule.id,
                        enabled = diagnostics.accepted,
                        onClick = { onSelect(candidate.rule.id) },
                    )
                }
            }
        }
    }
}


@Composable
private fun websiteRuleDisplayName(ruleId: String, fallbackName: String?): String =
    when {
        ruleId.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX) -> {
            val suffix =
                fallbackName
                    ?.substringAfter(" · ", missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() && it != fallbackName }
            if (suffix == null) {
                stringResource(R.string.website_parser_smart_detection)
            } else {
                suffix
            }
        }

        ruleId == "ithome-home" -> stringResource(R.string.website_rule_ithome)
        else -> fallbackName ?: stringResource(R.string.unknown)
    }

@Composable
private fun websiteParserCurrentDisplayName(ruleId: String, fallbackName: String?): String =
    if (ruleId.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX)) {
        stringResource(R.string.website_parser_smart_detection_with_type)
    } else {
        stringResource(
            R.string.website_parser_rule_name_with_type,
            websiteRuleDisplayName(ruleId, fallbackName),
        )
    }

@Composable
private fun WebsiteParserSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun WebsiteParserEmptyState(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun WebsiteParserOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = onClick,
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(modifier = Modifier.padding(start = 8.dp, top = 8.dp).weight(1f)) {
                Text(
                    title,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Text(
                        text = stringResource(R.string.website_parser_current_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
