package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.RenameDialog
import me.ash.reader.ui.component.base.ClipboardTextField
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.roundClick
import me.ash.reader.ui.page.home.feeds.FeedOptionView
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.website.CandidateState

@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
fun SubscribeDialog(
    subscribeViewModel: SubscribeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val subscribeUiState = subscribeViewModel.subscribeUiState.collectAsStateValue()
    val subscribeState = subscribeViewModel.subscribeState.collectAsStateValue()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri ->
            context.contentResolver.openInputStream(uri)?.let { inputStream ->
                subscribeViewModel.importFromInputStream(inputStream)
            }
        }
    }

    if (subscribeState is SubscribeState.Visible) {

        DisposableEffect(Unit) {
            onDispose {
                subscribeViewModel.cancelSearch()
            }
        }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val closeSheet = {
            focusManager.clearFocus()
            subscribeViewModel.hideDrawer()
        }
        ModalBottomSheet(
            onDismissRequest = closeSheet,
            sheetState = sheetState,
            dragHandle = null,
        ) {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                SubscribeSheetHeader(
                    state = subscribeState,
                    onRename = subscribeViewModel::showRenameDialog,
                    onClose = closeSheet,
                )
                HorizontalDivider()

                when (val state = subscribeState) {
                    is SubscribeState.Input ->
                        SubscribeInputContent(
                            state = state,
                            onSearch = subscribeViewModel::searchFeed,
                        )

                    is SubscribeState.Configure ->
                        SubscribeConfigureContent(
                            state = state,
                            onSelectCandidate = subscribeViewModel::selectSourceCandidate,
                            onToggleRssHub = subscribeViewModel::toggleRssHubCandidate,
                            onRetryRssHub = subscribeViewModel::retrySourceDiscovery,
                            onToggleNotification = subscribeViewModel::toggleAllowNotificationPreset,
                            onToggleFullContent = subscribeViewModel::toggleParseFullContentPreset,
                            onToggleBrowser = subscribeViewModel::toggleOpenInBrowserPreset,
                            onGroupClick = subscribeViewModel::selectedGroup,
                            onAddGroup = subscribeViewModel::showNewGroupDialog,
                        )

                    SubscribeState.Hidden -> Unit
                }

                HorizontalDivider()
                SubscribeSheetActions(
                    state = subscribeState,
                    onImportOpml = {
                        focusManager.clearFocus()
                        launcher.launch(arrayOf(MimeType.ANY))
                        subscribeViewModel.hideDrawer()
                    },
                    onSearch = {
                        focusManager.clearFocus()
                        subscribeViewModel.searchFeed()
                    },
                    onSubscribe = {
                        focusManager.clearFocus()
                        subscribeViewModel.subscribe()
                    },
                )
            }
        }

        RenameDialog(
            visible = subscribeUiState.renameDialogVisible,
            value = subscribeUiState.newName,
            onValueChange = {
                subscribeViewModel.inputNewName(it)
            },
            onDismissRequest = {
                subscribeViewModel.hideRenameDialog()
            },
            onConfirm = {
                subscribeViewModel.renameFeed()
                subscribeViewModel.hideRenameDialog()
            }
        )

        TextFieldDialog(
            visible = subscribeUiState.newGroupDialogVisible,
            title = stringResource(R.string.create_new_group),
            icon = Icons.Outlined.CreateNewFolder,
            value = subscribeUiState.newGroupContent,
            placeholder = stringResource(R.string.name),
            onValueChange = {
                subscribeViewModel.inputNewGroup(it)
            },
            onDismissRequest = {
                subscribeViewModel.hideNewGroupDialog()
            },
            onConfirm = {
                subscribeViewModel.addNewGroup()
            }
        )
    }
}

@Composable
private fun SubscribeSheetHeader(
    state: SubscribeState,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    val configure = state as? SubscribeState.Configure
    val input = state as? SubscribeState.Input
    val title =
        when (state) {
            is SubscribeState.Configure -> state.searchedFeed.title
            is SubscribeState.Fetching -> stringResource(R.string.searching)
            is SubscribeState.Idle -> stringResource(R.string.subscribe)
            SubscribeState.Hidden -> ""
        }
    val url = configure?.sourcePageUrl ?: input?.linkState?.text?.toString().orEmpty()

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedIcon(
            feedName = configure?.searchedFeed?.title,
            iconUrl = configure?.searchedFeed?.icon?.url,
            size = 42.dp,
            placeholderIcon = Icons.Rounded.RssFeed,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                modifier = if (configure != null) Modifier.roundClick(onClick = onRename) else Modifier,
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (url.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun SubscribeInputContent(
    state: SubscribeState.Input,
    onSearch: () -> Unit,
) {
    val errorText =
        when (state) {
            is SubscribeState.Fetching -> ""
            is SubscribeState.Idle -> state.errorMessage.orEmpty()
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        ClipboardTextField(
            state = state.linkState,
            modifier = Modifier.fillMaxWidth(),
            readOnly = state is SubscribeState.Fetching,
            placeholder = stringResource(R.string.feed_or_site_url),
            errorText = errorText,
            imeAction = ImeAction.Search,
            onConfirm = { onSearch() },
        )
        if (state is SubscribeState.Fetching) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = searchStageLabel(state.stage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state is SubscribeState.Idle && state.rssHubResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            RssHubRouteSection(
                results = state.rssHubResults,
                candidates = emptyList(),
                selectedIds = emptySet(),
                onToggle = {},
                onRetry = null,
            )
        }
    }
}

@Composable
private fun SubscribeConfigureContent(
    state: SubscribeState.Configure,
    onSelectCandidate: (String) -> Unit,
    onToggleRssHub: (String) -> Unit,
    onRetryRssHub: () -> Unit,
    onToggleNotification: () -> Unit,
    onToggleFullContent: () -> Unit,
    onToggleBrowser: () -> Unit,
    onGroupClick: (String) -> Unit,
    onAddGroup: () -> Unit,
) {
    val otherCandidates = state.candidates.filter { it.kind != SourceCandidateKind.RSSHUB }
    val selectedKind = state.candidates.firstOrNull { it.id == state.selectedCandidateId }?.kind

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = 570.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (state.rssHubResults.isNotEmpty()) {
            RssHubRouteSection(
                results = state.rssHubResults,
                candidates = state.candidates,
                selectedIds = state.selectedCandidateIds,
                onToggle = onToggleRssHub,
                onRetry =
                    if (state.rssHubResults.any { !it.available }) onRetryRssHub else null,
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (otherCandidates.size > 1 || (otherCandidates.isNotEmpty() && state.rssHubResults.isNotEmpty())) {
            Text(
                text = stringResource(R.string.other_available_sources),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                otherCandidates.forEach { candidate ->
                    SourceCandidateCard(
                        candidate = candidate,
                        selected = candidate.id in state.selectedCandidateIds,
                        recommended =
                            candidate.diagnostics.accepted &&
                                candidate.id == state.candidates.firstOrNull()?.id,
                        onClick = { onSelectCandidate(candidate.id) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (selectedKind != SourceCandidateKind.RSSHUB) {
            state.sourceNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        FeedOptionView(
            link = state.feedLink,
            groups = state.groups,
            selectedAllowNotificationPreset = state.notification,
            selectedParseFullContentPreset = state.fullContent,
            selectedOpenInBrowserPreset = state.browser,
            selectedGroupId = state.selectedGroupId,
            allowNotificationPresetOnClick = onToggleNotification,
            parseFullContentPresetOnClick = onToggleFullContent,
            openInBrowserPresetOnClick = onToggleBrowser,
            onGroupClick = onGroupClick,
            onAddNewGroup = onAddGroup,
            sourceType = state.sourceType,
            showUrl = false,
            showArticleFilter = false,
            scrollable = false,
        )
    }
}

@Composable
private fun RssHubRouteSection(
    results: List<RssHubProbeResult>,
    candidates: List<SubscribeSourceCandidate>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onRetry: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.rsshub_matched_channels),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.rsshub_matched_channels_desc, results.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.rsshub_retry_detection))
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        results.forEach { result ->
            val candidate =
                result.match.feedUrl?.let { feedUrl ->
                    candidates.firstOrNull {
                        it.kind == SourceCandidateKind.RSSHUB && it.feedLink == feedUrl
                    }
                }
            val selectable = candidate != null
            val selected = candidate?.id in selectedIds
            val cardModifier =
                if (selectable) {
                    Modifier.fillMaxWidth().roundClick { onToggle(requireNotNull(candidate).id) }
                } else {
                    Modifier.fillMaxWidth()
                }
            Surface(
                modifier = cardModifier,
                color =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selected,
                        enabled = selectable,
                        onCheckedChange =
                            if (selectable) {
                                { onToggle(requireNotNull(candidate).id) }
                            } else {
                                null
                            },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = result.match.route.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = rssHubRouteStatus(result, candidate),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (selectable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCandidateCard(
    candidate: SubscribeSourceCandidate,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val sourceName = candidate.kind.displayName()
    val feedName = candidate.feed.title?.trim().orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth().roundClick(onClick = onClick),
        color =
            if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = feedName.ifBlank { sourceName },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (recommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.source_candidate_recommended),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = "$sourceName · " + stringResource(
                        R.string.source_candidate_article_count,
                        candidate.diagnostics.articleCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SubscribeSheetActions(
    state: SubscribeState,
    onImportOpml: () -> Unit,
    onSearch: () -> Unit,
    onSubscribe: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (state is SubscribeState.Idle && state.importFromOpmlEnabled) {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onImportOpml) {
                Text(stringResource(R.string.import_from_opml))
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        when (state) {
            is SubscribeState.Configure ->
                Button(modifier = Modifier.fillMaxWidth().height(50.dp), onClick = onSubscribe) {
                    Text(stringResource(R.string.confirm_subscribe))
                }

            is SubscribeState.Input ->
                Button(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = state is SubscribeState.Idle && state.linkState.text.isNotBlank(),
                    onClick = onSearch,
                ) {
                    Text(
                        stringResource(
                            if (state is SubscribeState.Fetching) R.string.searching else R.string.search
                        )
                    )
                }

            SubscribeState.Hidden -> Unit
        }
    }
}

@Composable
private fun searchStageLabel(stage: SearchStage): String =
    when (stage) {
        SearchStage.CHECKING_RSS -> stringResource(R.string.search_stage_rss)
        SearchStage.CHECKING_RSSHUB -> stringResource(R.string.search_stage_rsshub)
        SearchStage.CHECKING_JSON -> stringResource(R.string.search_stage_json)
        SearchStage.CHECKING_WEBSITE -> stringResource(R.string.search_stage_website)
        SearchStage.CHECKING_DYNAMIC_WEBSITE -> stringResource(R.string.search_stage_dynamic_website)
    }

@Composable
private fun rssHubRouteStatus(
    result: RssHubProbeResult,
    candidate: SubscribeSourceCandidate?,
): String =
    when {
        result.available && candidate != null ->
            stringResource(R.string.rsshub_route_available, candidate.diagnostics.articleCount)

        result.available -> stringResource(R.string.rsshub_route_quality_rejected)
        result.state == CandidateState.TIMEOUT -> stringResource(R.string.rsshub_route_timeout)
        result.state == CandidateState.NETWORK_UNAVAILABLE ->
            stringResource(R.string.rsshub_route_network_unavailable)
        result.state == CandidateState.UNSUPPORTED -> stringResource(R.string.rsshub_route_disabled)
        result.state == CandidateState.NEEDS_INPUT -> stringResource(R.string.rsshub_route_needs_input)
        else -> stringResource(R.string.rsshub_route_invalid_content)
    }

@Composable
private fun SourceCandidateKind.displayName(): String =
    when (this) {
        SourceCandidateKind.RSS_DIRECT -> stringResource(R.string.source_kind_rss_direct)
        SourceCandidateKind.RSS_DISCOVERED -> stringResource(R.string.source_kind_rss_discovered)
        SourceCandidateKind.RSSHUB -> stringResource(R.string.source_kind_rsshub)
        SourceCandidateKind.JSON -> stringResource(R.string.source_kind_json)
        SourceCandidateKind.WEBSITE -> stringResource(R.string.source_kind_website)
        SourceCandidateKind.WEBSITE_DYNAMIC -> stringResource(R.string.source_kind_website_dynamic)
    }
