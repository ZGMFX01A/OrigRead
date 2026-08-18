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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.website.AutomaticWebsiteListDetector
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.ui.component.base.RYSwitch
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
                                RYSwitch(activated = rule.enabled) {
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
                        preferredRuleId = feedOptionUiState.preferredWebsiteRuleId,
                        onSelect = feedOptionViewModel::selectWebsiteRule,
                        onBack = feedOptionViewModel::hideWebsiteParserDialog,
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
                                websiteRuleDisplayName(ruleId, feedOptionUiState.preferredWebsiteRuleName)
                            } ?: stringResource(R.string.website_parser_auto),
                        onWebsiteParserClick = feedOptionViewModel::showWebsiteParserDialog,
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
private fun WebsiteParserPanel(
    loading: Boolean,
    error: String?,
    candidates: List<me.ash.reader.infrastructure.website.WebsiteParseCandidate>,
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

            error != null -> Text(text = error, color = MaterialTheme.colorScheme.error)

            else -> LazyColumn {
                item {
                    WebsiteParserOption(
                        title = stringResource(R.string.website_parser_auto),
                        description = stringResource(R.string.website_parser_auto_desc),
                        selected = preferredRuleId == null,
                        onClick = { onSelect(null) },
                    )
                }
                // rule.id 理论上已唯一，索引作为最终保护，避免异常上游数据再次使整个界面崩溃。
                itemsIndexed(candidates, key = { index, candidate -> "${candidate.rule.id}:$index" }) { _, candidate ->
                    val diagnostics = candidate.diagnostics
                    WebsiteParserOption(
                        title = websiteRuleDisplayName(candidate.rule.id, candidate.rule.name),
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
                stringResource(R.string.website_parser_smart_detection_named, suffix)
            }
        }

        ruleId == "ithome-home" -> stringResource(R.string.website_rule_ithome)
        else -> fallbackName ?: stringResource(R.string.unknown)
    }

@Composable
private fun WebsiteParserOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
