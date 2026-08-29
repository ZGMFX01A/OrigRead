package me.ash.reader.llm.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.ash.reader.R
import me.ash.reader.infrastructure.ai.availableModels
import me.ash.reader.llm.chat.data.LlmArticleCandidate
import me.ash.reader.llm.chat.data.LlmChatRole
import me.ash.reader.llm.chat.data.LlmContextRefEntity
import me.ash.reader.llm.chat.data.LlmConversationEntity
import me.ash.reader.llm.chat.data.LlmMessageEntity
import me.ash.reader.llm.chat.data.LlmMessageStatus
import me.ash.reader.llm.chat.data.LlmToolCallEntity
import me.ash.reader.llm.chat.data.LlmToolCallStatus
import me.ash.reader.llm.chat.data.stripDisabledLlmCitationTokens
import me.ash.reader.llm.quickmessage.LlmQuickMessage
import me.ash.reader.llm.quickmessage.LlmQuickMessageResolution
import me.ash.reader.llm.quickmessage.resolveQuickMessageText
import me.ash.reader.llm.runtime.LlmReasoningEffort
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.runtime.LlmToolDescriptor
import me.ash.reader.llm.search.WebSearchMode
import me.ash.reader.llm.search.WebSearchRequestStatus
import me.ash.reader.ui.page.home.reading.AiSummaryAccentIcon
import me.ash.reader.ui.page.home.reading.ArticleAssistantContext

private const val CONTEXT_SOURCE_PREVIEW_LIMIT = 1_200

/**
 * 文章级 LLM 阅读助手。
 *
 * 交互参考“在文档中提问”的成熟阅读器形态：正文仍是主场景，助手作为可关闭的上下文层；
 * Provider / Model 收进输入区附近，避免把 Runtime 配置做成页面主视觉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmArticleAssistantSheet(
    articleContext: ArticleAssistantContext,
    articleAnalysisRequested: Boolean = false,
    onArticleAnalysisConsumed: () -> Unit = {},
    onOpenArticle: (String) -> Unit = {},
    onDismiss: () -> Unit,
    viewModel: LlmChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    var input by rememberSaveable(articleContext.articleId) { mutableStateOf("") }
    var historyExpanded by remember { mutableStateOf(false) }
    var conversationMenuExpanded by remember { mutableStateOf(false) }
    var modelPickerVisible by remember { mutableStateOf(false) }
    var manualToolSheetVisible by remember { mutableStateOf(false) }
    var contextSourcesAssistantId by remember { mutableStateOf<String?>(null) }
    var webSearchResultsAssistantId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<LlmConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<LlmConversationEntity?>(null) }
    var autoFollow by remember(uiState.currentConversationId) { mutableStateOf(true) }
    var userScrollControlActive by remember(uiState.currentConversationId) { mutableStateOf(false) }
    val latestLastAssistantId =
        rememberUpdatedState(uiState.messages.lastOrNull { it.role == LlmChatRole.ASSISTANT }?.id)
    val coroutineScope = rememberCoroutineScope()
    val canScrollUp by remember { derivedStateOf { listState.canScrollBackward } }
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val articleAnalysisPrompt = stringResource(R.string.llm_article_analysis_request)

    LaunchedEffect(articleContext) {
        viewModel.bindArticleContext(articleContext)
    }
    LaunchedEffect(articleAnalysisRequested) {
        if (articleAnalysisRequested) {
            // 先同步当前文章 Context，再消费一次性 UI 请求；真正任务类型由 ViewModel/Room 持久化。
            viewModel.bindArticleContext(articleContext)
            onArticleAnalysisConsumed()
            viewModel.analyzeArticle(articleAnalysisPrompt)
        }
    }
    LaunchedEffect(listState, uiState.currentConversationId) {
        // 只有真实拖拽才关闭自动跟随；程序自己的 scrollToItem 不会被误判成用户意图。
        // 显式记录 Drag 生命周期，避免 Drag.Start 与 LazyList isScrollInProgress 更新存在一帧时序差时又被错误恢复。
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    userScrollControlActive = true
                    autoFollow = false
                }
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> userScrollControlActive = false
            }
        }
    }
    LaunchedEffect(listState, uiState.currentConversationId) {
        // 用真实尾部锚点的可见性统一驱动“恢复跟随”和“需要追尾”，不再依赖 canScrollForward 的瞬时值。
        // 这样顶部 overscroll / BottomSheet nested scroll 不会把一次边界状态误判成“已经回到底部”。
        snapshotFlow {
                ChatAutoFollowObservation(
                    autoFollow = autoFollow,
                    userScrollControlActive = userScrollControlActive,
                    layout = listState.chatAutoFollowLayoutSnapshot(),
                )
            }
            .collect { observation ->
                if (shouldResumeChatAutoFollow(observation)) {
                    autoFollow = true
                    return@collect
                }
                if (shouldIssueChatAutoFollowScroll(observation)) {
                    latestLastAssistantId.value?.let(LlmChatPerfTracker::recordAutoFollowScroll)
                    // 最后一条消息后存在稳定锚点；只有锚点确实离开 viewport 才执行一次追尾，
                    // 避免短回答或布局未变化时每次 Room 更新都重复 scrollToItem()。
                    listState.scrollToItem(observation.layout.totalItemsCount - 1)
                }
            }
        }

    val dismissAssistant = {
        viewModel.stopGeneration()
        onDismiss()
    }
    val currentConversation =
        uiState.conversations.firstOrNull { it.id == uiState.currentConversationId }

    ModalBottomSheet(
        onDismissRequest = dismissAssistant,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
        ) {
            AssistantHeader(
                providerName =
                    uiState.providers
                        .firstOrNull { it.id == uiState.selectedProviderId }
                        ?.name
                        ?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.llm_chat_select_provider),
                conversations = uiState.conversations,
                currentConversationId = uiState.currentConversationId,
                historyExpanded = historyExpanded,
                conversationMenuExpanded = conversationMenuExpanded,
                currentConversation = currentConversation,
                onHistoryExpandedChange = { historyExpanded = it },
                onConversationMenuExpandedChange = { conversationMenuExpanded = it },
                onNewConversation = viewModel::newConversation,
                onSelectConversation = viewModel::selectConversation,
                onRenameConversation = { renameTarget = it },
                onDeleteConversation = { deleteTarget = it },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (uiState.messages.isEmpty()) {
                ArticleAssistantEmptyState(
                    configured = uiState.selectedProviderId != null && uiState.selectedModel != null,
                    modifier = Modifier.weight(1f),
                )
            } else {
                val lastAssistantId =
                    uiState.messages.lastOrNull { it.role == LlmChatRole.ASSISTANT }?.id
                val contextRefsByAssistantId =
                    remember(uiState.contextRefs) {
                        uiState.contextRefs.groupBy(LlmContextRefEntity::assistantMessageId)
                    }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(uiState.messages, key = LlmMessageEntity::id) { message ->
                            val messageContextRefs = contextRefsByAssistantId[message.id].orEmpty()
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistantMessage(
                                    message = message,
                                    showReasoning = uiState.showReasoning,
                                    contextRefs = messageContextRefs,
                                    onShowContextSources = {
                                        contextSourcesAssistantId = message.id
                                    },
                                    onShowWebSearchResults = {
                                        webSearchResultsAssistantId = message.id
                                    },
                                    canRegenerate =
                                        !uiState.isGenerating &&
                                            message.id == lastAssistantId &&
                                            uiState.messages.any { it.role == LlmChatRole.USER },
                                    onRegenerate = viewModel::regenerateLast,
                                )
                                uiState.toolCalls
                                    .filter { it.assistantMessageId == message.id }
                                    .forEach { call ->
                                        ToolCallCard(
                                            call = call,
                                            interactionEnabled = !uiState.isGenerating,
                                            onApprove = { viewModel.approveToolCall(call.id) },
                                            onDeny = { viewModel.denyToolCall(call.id) },
                                        )
                                    }
                            }
                        }
                        // 独立尾部锚点用于超长最后一条消息的一键到底与流式跟随。
                        item(key = "conversation-bottom-anchor") {
                            Spacer(Modifier.size(1.dp))
                        }
                    }

                    if (canScrollUp || canScrollDown) {
                        Column(
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (canScrollUp) {
                                ScrollJumpButton(
                                    icon = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.llm_chat_scroll_top),
                                    onClick = {
                                        userScrollControlActive = true
                                        autoFollow = false
                                        coroutineScope.launch {
                                            try {
                                                listState.animateScrollToItem(0)
                                            } finally {
                                                userScrollControlActive = false
                                            }
                                        }
                                    },
                                )
                            }
                            if (canScrollDown) {
                                ScrollJumpButton(
                                    icon = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.llm_chat_scroll_bottom),
                                    onClick = {
                                        userScrollControlActive = true
                                        autoFollow = true
                                        coroutineScope.launch {
                                            try {
                                                listState.animateScrollToItem(uiState.messages.size)
                                            } finally {
                                                userScrollControlActive = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            uiState.transientError?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = viewModel::clearTransientError) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                }
            }

            AssistantComposer(
                input = input,
                uiState = uiState,
                onInputChange = { input = it },
                onOpenModelPicker = { modelPickerVisible = true },
                onOpenManualTool = {
                    viewModel.refreshManualTools()
                    manualToolSheetVisible = true
                },
                onRemoveManualToolContext = viewModel::removeManualToolContext,
                onLoadArticleCandidates = viewModel::loadArticleCandidates,
                onAttachArticleCandidate = viewModel::attachArticleCandidate,
                onRemoveAdditionalArticle = viewModel::removeAdditionalArticle,
                onWebSearchModeChange = viewModel::setWebSearchMode,
                onReasoningEffortChange = viewModel::setReasoningEffort,
                onQuickMessage = viewModel::sendQuickMessage,
                onSend = {
                    val text = input.trim()
                    if (text.isNotBlank()) {
                        input = ""
                        viewModel.sendMessage(text)
                    }
                },
                onStop = viewModel::stopGeneration,
            )
        }
    }

    if (modelPickerVisible) {
        ModelPickerSheet(
            uiState = uiState,
            onDismiss = { modelPickerVisible = false },
            onSelect = { providerId, model ->
                viewModel.selectProviderModel(providerId, model)
                modelPickerVisible = false
            },
        )
    }

    if (manualToolSheetVisible && uiState.manualToolFallbackAvailable) {
        ManualToolSheet(
            tools = uiState.manualTools,
            running = uiState.manualToolRunning,
            onDismiss = { manualToolSheetVisible = false },
            onRun = { toolId, argumentsJson ->
                viewModel.runManualTool(toolId, argumentsJson)
                manualToolSheetVisible = false
            },
        )
    }

    contextSourcesAssistantId?.let { assistantMessageId ->
        ContextSourcesSheet(
            refs = uiState.contextRefs.filter { it.assistantMessageId == assistantMessageId },
            currentArticleId = articleContext.articleId,
            onOpenArticle = onOpenArticle,
            onDismiss = {
                contextSourcesAssistantId = null
            },
        )
    }

    webSearchResultsAssistantId?.let { assistantMessageId ->
        val message = uiState.messages.firstOrNull { it.id == assistantMessageId }
        if (message != null) {
            val refs =
                uiState.contextRefs.filter { ref -> ref.assistantMessageId == assistantMessageId }
            WebSearchResultsSheet(
                query = message.webSearchQuery?.trim()?.takeIf(String::isNotBlank),
                providerName = message.webSearchProviderName?.trim()?.takeIf(String::isNotBlank),
                results = remember(assistantMessageId, refs) {
                    projectWebSearchResults(assistantMessageId, refs)
                },
                onDismiss = { webSearchResultsAssistantId = null },
            )
        }
    }

    uiState.pendingManualTool?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::denyManualTool,
            title = { Text(stringResource(R.string.llm_manual_tool_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.llm_manual_tool_confirm_desc, pending.descriptor.name))
                    Text(
                        text = pending.argumentsJson.take(1600),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::denyManualTool) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::approveManualTool) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    renameTarget?.let { conversation ->
        RenameConversationDialog(
            currentTitle = conversation.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                viewModel.renameConversation(conversation.id, title)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.llm_chat_delete_title)) },
            text = { Text(stringResource(R.string.llm_chat_delete_message, conversation.title)) },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(conversation.id)
                        deleteTarget = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
        )
    }
}

@Composable
private fun AssistantHeader(
    providerName: String,
    conversations: List<LlmConversationEntity>,
    currentConversationId: String?,
    historyExpanded: Boolean,
    conversationMenuExpanded: Boolean,
    currentConversation: LlmConversationEntity?,
    onHistoryExpandedChange: (Boolean) -> Unit,
    onConversationMenuExpandedChange: (Boolean) -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRenameConversation: (LlmConversationEntity) -> Unit,
    onDeleteConversation: (LlmConversationEntity) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AiSummaryAccentIcon(
            contentDescription = null,
            size = 32.dp,
            iconSize = 18.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.llm_article_assistant_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = providerName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            IconButton(onClick = { onHistoryExpandedChange(true) }) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = stringResource(R.string.llm_chat_history),
                )
            }
            DropdownMenu(
                expanded = historyExpanded,
                onDismissRequest = { onHistoryExpandedChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.llm_chat_new)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = {
                        onHistoryExpandedChange(false)
                        onNewConversation()
                    },
                )
                if (conversations.isNotEmpty()) HorizontalDivider()
                conversations.forEach { conversation ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                conversation.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight =
                                    if (conversation.id == currentConversationId) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                            )
                        },
                        onClick = {
                            onHistoryExpandedChange(false)
                            onSelectConversation(conversation.id)
                        },
                    )
                }
            }
        }

        IconButton(onClick = onNewConversation) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.llm_chat_new))
        }

        Box {
            IconButton(
                onClick = { onConversationMenuExpandedChange(true) },
                enabled = currentConversation != null,
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more))
            }
            DropdownMenu(
                expanded = conversationMenuExpanded,
                onDismissRequest = { onConversationMenuExpandedChange(false) },
            ) {
                currentConversation?.let { conversation ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            onConversationMenuExpandedChange(false)
                            onRenameConversation(conversation)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            onConversationMenuExpandedChange(false)
                            onDeleteConversation(conversation)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleAssistantEmptyState(
    configured: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.llm_article_assistant_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (shouldShowArticleAssistantConfigurationHint(configured)) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.llm_chat_not_configured),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 已配置 Provider/Model 时，文章 Chat 的正常空态只保留一句行动提示。
 * 只有缺少可用运行时配置时才展示必要的设置引导，避免重复解释产品设计。
 */
internal fun shouldShowArticleAssistantConfigurationHint(configured: Boolean): Boolean = !configured

/**
 * Chat 列表当前与尾部锚点相关的最小布局快照。
 *
 * 不把 LazyListState 本身带进业务判断，既方便纯 JVM 回归，也避免继续用 canScrollForward 这种边界瞬时值
 * 直接决定 auto-follow 状态。
 */
internal data class ChatAutoFollowLayoutSnapshot(
    val isScrollInProgress: Boolean,
    val totalItemsCount: Int,
    val lastVisibleItemIndex: Int?,
    val lastVisibleItemEndOffset: Int?,
    val viewportEndOffset: Int,
)

/** 一次 auto-follow 决策需要的 UI 状态；显式用户滚动控制单独保存以覆盖 Drag/动画启动的一帧时序差。 */
internal data class ChatAutoFollowObservation(
    val autoFollow: Boolean,
    val userScrollControlActive: Boolean,
    val layout: ChatAutoFollowLayoutSnapshot,
)

/** 只有真正的最后一个 item（conversation-bottom-anchor）完整进入 viewport 才算回到底部。 */
internal fun isChatBottomAnchorFullyVisible(layout: ChatAutoFollowLayoutSnapshot): Boolean {
    if (layout.totalItemsCount <= 0) return false
    val lastVisibleIndex = layout.lastVisibleItemIndex ?: return false
    val lastVisibleEnd = layout.lastVisibleItemEndOffset ?: return false
    return lastVisibleIndex == layout.totalItemsCount - 1 &&
        lastVisibleEnd <= layout.viewportEndOffset
}

/** 用户已经主动脱离跟随时，只有拖拽/惯性滚动都结束且尾部锚点完整可见才恢复。 */
internal fun shouldResumeChatAutoFollow(observation: ChatAutoFollowObservation): Boolean =
    !observation.autoFollow &&
        !observation.userScrollControlActive &&
        !observation.layout.isScrollInProgress &&
        isChatBottomAnchorFullyVisible(observation.layout)

/** 已处于跟随态时，仅在列表静止且尾部锚点确实离开 viewport 后执行一次程序追尾。 */
internal fun shouldIssueChatAutoFollowScroll(observation: ChatAutoFollowObservation): Boolean =
    observation.autoFollow &&
        !observation.userScrollControlActive &&
        !observation.layout.isScrollInProgress &&
        observation.layout.totalItemsCount > 0 &&
        !isChatBottomAnchorFullyVisible(observation.layout)

/** 将 Compose LazyList 布局压缩成稳定、无正文内容的 auto-follow 快照。 */
private fun LazyListState.chatAutoFollowLayoutSnapshot(): ChatAutoFollowLayoutSnapshot {
    val layout = layoutInfo
    val lastVisibleItem = layout.visibleItemsInfo.lastOrNull()
    return ChatAutoFollowLayoutSnapshot(
        isScrollInProgress = isScrollInProgress,
        totalItemsCount = layout.totalItemsCount,
        lastVisibleItemIndex = lastVisibleItem?.index,
        lastVisibleItemEndOffset = lastVisibleItem?.let { it.offset + it.size },
        viewportEndOffset = layout.viewportEndOffset,
    )
}

/** 长对话中的轻量跳转按钮，不占用输入区，也不把导航动作做成新的主视觉。 */
@Composable
private fun ScrollJumpButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: LlmMessageEntity,
    showReasoning: Boolean,
    contextRefs: List<LlmContextRefEntity>,
    onShowContextSources: () -> Unit,
    onShowWebSearchResults: () -> Unit,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
) {
    val isUser = message.role == LlmChatRole.USER
    if (isUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.84f),
                shape = RoundedCornerShape(18.dp),
                // OrigRead 的全局主题来自动态 tonal palette。用户气泡如果直接铺 primaryContainer，
                // 蓝/紫等壁纸主色会在聊天页形成大面积染色；这里改用中性 Surface 层级，
                // 只把 primary 留给选中态、图标等小面积强调。
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        return
    }

    val clipboardManager = LocalClipboardManager.current
    val displayContent = remember(message.content) { stripDisabledLlmCitationTokens(message.content) }
    val displayReasoning =
        remember(message.reasoning) {
            message.reasoning?.let { reasoning -> stripDisabledLlmCitationTokens(reasoning) }
        }
    val hasVisibleModelText =
        displayContent.isNotBlank() || (showReasoning && !displayReasoning.isNullOrBlank())
    LaunchedEffect(message.id, hasVisibleModelText) {
        if (hasVisibleModelText) {
            LlmChatPerfTracker.recordFirstVisible(
                assistantMessageId = message.id,
                contentChars = displayContent.length,
                reasoningChars = if (showReasoning) displayReasoning?.length ?: 0 else 0,
            )
        }
    }
    val webSearchUiModel =
        remember(
            message.id,
            message.status,
            message.webSearchStatus,
            message.webSearchQuery,
            message.webSearchProviderName,
            message.webSearchErrorMessage,
            contextRefs,
        ) {
            projectWebSearchMessage(message, contextRefs)
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.llm_assistant_name),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        if (webSearchUiModel != null) {
            WebSearchActivityCard(
                model = webSearchUiModel,
                onOpenResults = onShowWebSearchResults,
            )
            Spacer(Modifier.size(8.dp))
        }
        if (showReasoning && !displayReasoning.isNullOrBlank()) {
            ReasoningBlock(
                reasoning = displayReasoning,
                stateKey = message.id,
            )
            Spacer(Modifier.size(8.dp))
        }
        if (displayContent.isNotBlank()) {
            LlmRichMarkdown(
                markdown = displayContent,
                perfMessageId = message.id,
            )
        } else if (
            message.status == LlmMessageStatus.STREAMING &&
                message.webSearchStatus != WebSearchRequestStatus.TRIGGERED
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.llm_chat_generating))
            }
        }
        when (message.status) {
            LlmMessageStatus.ERROR ->
                Text(
                    text = message.errorMessage ?: stringResource(R.string.llm_chat_request_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            LlmMessageStatus.STOPPED ->
                Text(
                    text = stringResource(R.string.llm_chat_stopped),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            else -> Unit
        }
        if (displayContent.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(displayContent)) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.llm_chat_copy),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (contextRefs.isNotEmpty()) {
                    TextButton(
                        onClick = onShowContextSources,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.llm_context_sources_count, contextRefs.size),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (canRegenerate) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.llm_chat_regenerate),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            MessageUsageRow(message)
        }
    }
}

/** 单次 Assistant 的 Dedicated Search 折叠活动卡；完整结果详情在 UX2.3 单独实现。 */
@Composable
private fun WebSearchActivityCard(
    model: WebSearchMessageUiModel,
    onOpenResults: () -> Unit,
) {
    if (model.state == WebSearchActivityUiState.SUCCESS && model.query == null) {
        LegacyWebSearchStatusRow(model, onOpenResults)
        return
    }

    val isError = model.errorState != WebSearchMessageErrorState.NONE
    Surface(
        modifier =
            Modifier.fillMaxWidth().clickable(
                enabled = model.state == WebSearchActivityUiState.SUCCESS && model.canShowResults,
                onClick = onOpenResults,
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (model.state == WebSearchActivityUiState.SEARCHING) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.8.dp)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint =
                            if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text =
                        when (model.state) {
                            WebSearchActivityUiState.SEARCHING ->
                                stringResource(R.string.llm_web_search_request_running)
                            WebSearchActivityUiState.SUCCESS ->
                                stringResource(R.string.llm_web_search_activity_title)
                            WebSearchActivityUiState.FAILED_FALLBACK ->
                                stringResource(R.string.llm_web_search_activity_failed)
                            WebSearchActivityUiState.FORCE_FAILURE ->
                                stringResource(R.string.llm_web_search_activity_failed)
                            WebSearchActivityUiState.CANCELLED ->
                                stringResource(R.string.llm_web_search_activity_cancelled)
                        },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                )
                when (model.state) {
                    WebSearchActivityUiState.SUCCESS -> {
                        val summary =
                            model.providerName?.let { provider ->
                                stringResource(
                                    R.string.llm_web_search_activity_result_count_provider,
                                    provider,
                                    model.resultCount,
                                )
                            } ?: stringResource(
                                R.string.llm_web_search_activity_result_count,
                                model.resultCount,
                            )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else ->
                        model.providerName?.let { provider ->
                            Text(
                                text = provider,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
            }

            model.query?.let { query ->
                Text(
                    text = stringResource(R.string.llm_web_search_activity_query, query),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when (model.state) {
                WebSearchActivityUiState.SEARCHING -> Unit
                WebSearchActivityUiState.SUCCESS -> {
                    if (model.resultCount == 0) {
                        Text(
                            text = stringResource(R.string.llm_web_search_activity_no_results),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (model.sourceLabels.isNotEmpty()) {
                                Text(
                                    text = model.sourceLabels.joinToString(" · "),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (model.canShowResults) {
                                TextButton(
                                    onClick = onOpenResults,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.llm_web_search_activity_view_results),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                WebSearchActivityUiState.FAILED_FALLBACK ->
                    Text(
                        text = stringResource(R.string.llm_web_search_request_failed_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                WebSearchActivityUiState.FORCE_FAILURE ->
                    Text(
                        text =
                            model.errorMessage
                                ?: stringResource(R.string.llm_web_search_activity_force_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                WebSearchActivityUiState.CANCELLED -> Unit
            }
        }
    }
}

/** v11 及更早历史没有冻结 query；保持旧版单行展示，绝不事后猜测搜索词。 */
@Composable
private fun LegacyWebSearchStatusRow(
    model: WebSearchMessageUiModel,
    onOpenResults: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Public,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                model.providerName?.let { provider ->
                    stringResource(R.string.llm_web_search_request_success_provider, provider)
                } ?: stringResource(R.string.llm_web_search_request_success),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (model.canShowResults) {
            TextButton(
                onClick = onOpenResults,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(
                    stringResource(R.string.llm_web_search_activity_view_results),
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Tool Call 状态卡片。
 * Pending 时必须先展示目标 Tool 与参数，再允许用户批准；参数只做有界预览，避免大 JSON 撑满阅读助手。
 */
@Composable
private fun ToolCallCard(
    call: LlmToolCallEntity,
    interactionEnabled: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val toolName =
        call.toolId
            .takeUnless { it.startsWith("unresolved:") }
            ?.substringAfterLast(':')
            ?.takeIf(String::isNotBlank)
            ?: call.apiName
    val statusText =
        when (call.status) {
            LlmToolCallStatus.PENDING_APPROVAL -> stringResource(R.string.llm_tool_call_pending)
            LlmToolCallStatus.RUNNING -> stringResource(R.string.llm_tool_call_running)
            LlmToolCallStatus.COMPLETE -> stringResource(R.string.llm_tool_call_complete)
            LlmToolCallStatus.DENIED -> stringResource(R.string.llm_tool_call_denied)
            LlmToolCallStatus.ERROR -> stringResource(R.string.llm_tool_call_error)
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color =
            if (call.status == LlmToolCallStatus.PENDING_APPROVAL) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = toolName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (call.status == LlmToolCallStatus.RUNNING) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = call.argumentsJson,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            call.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (call.status == LlmToolCallStatus.PENDING_APPROVAL) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDeny, enabled = interactionEnabled) {
                        Text(stringResource(R.string.llm_tool_call_deny))
                    }
                    TextButton(onClick = onApprove, enabled = interactionEnabled) {
                        Text(stringResource(R.string.llm_tool_call_approve))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(
    reasoning: String,
    stateKey: String,
) {
    // 以消息 ID 保存展开状态，避免流式 reasoning 每次追加文本时 hash 改变导致 UI 自动折叠。
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.llm_chat_reasoning),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.llm_chat_hide_reasoning
                            else R.string.llm_chat_show_reasoning
                        ),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                LlmRichMarkdown(
                    markdown = reasoning,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    perfMessageId = stateKey,
                )
            }
        }
    }
}

@Composable
private fun MessageUsageRow(message: LlmMessageEntity) {
    val promptTokens = message.promptTokens
    val completionTokens = message.completionTokens
    val durationMs = message.durationMs
    if (promptTokens == null && completionTokens == null && durationMs == null) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        promptTokens?.let { tokens ->
            UsageMetric(
                icon = Icons.Rounded.ArrowUpward,
                value = tokenValue(tokens, message.tokenUsageEstimated),
                contentDescription = stringResource(R.string.llm_chat_prompt_tokens),
            )
        }
        completionTokens?.let { tokens ->
            UsageMetric(
                icon = Icons.Rounded.ArrowDownward,
                value = tokenValue(tokens, message.tokenUsageEstimated),
                contentDescription = stringResource(R.string.llm_chat_completion_tokens),
            )
        }
        durationMs?.let { duration ->
            UsageMetric(
                icon = Icons.Rounded.Schedule,
                value = formatDuration(duration),
                contentDescription = stringResource(R.string.llm_chat_duration),
            )
        }
    }
}

@Composable
private fun UsageMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    contentDescription: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun tokenValue(value: Int, estimated: Boolean): String =
    (if (estimated) "≈" else "") + formatCompactNumber(value)

private fun formatCompactNumber(value: Int): String =
    when {
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }

private fun formatDuration(durationMs: Long): String =
    if (durationMs >= 1_000L) {
        String.format(java.util.Locale.US, "%.1fs", durationMs / 1_000.0)
    } else {
        "${durationMs}ms"
    }

@Composable
private fun AssistantComposer(
    input: String,
    uiState: LlmChatUiState,
    onInputChange: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenManualTool: () -> Unit,
    onRemoveManualToolContext: (String) -> Unit,
    onLoadArticleCandidates: (String) -> Unit,
    onAttachArticleCandidate: (LlmArticleCandidate) -> Unit,
    onRemoveAdditionalArticle: (String) -> Unit,
    onWebSearchModeChange: (WebSearchMode) -> Unit,
    onReasoningEffortChange: (LlmReasoningEffort) -> Unit,
    onQuickMessage: (LlmQuickMessage) -> LlmQuickMessageResolution,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var webSearchSheetVisible by remember { mutableStateOf(false) }
    var reasoningSheetVisible by remember { mutableStateOf(false) }
    var quickMessageSheetVisible by remember { mutableStateOf(false) }
    var relatedArticleSheetVisible by remember { mutableStateOf(false) }
    val configured = uiState.selectedProviderId != null && uiState.selectedModel != null
    val canModifyAdditionalArticles =
        !uiState.isGenerating &&
            uiState.toolCalls.none { it.status == LlmToolCallStatus.PENDING_APPROVAL }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.additionalArticleAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.additionalArticleAttachments.forEach { attachment ->
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    text = attachment.title.ifBlank { attachment.articleId },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.llm_related_article_attached),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveAdditionalArticle(attachment.articleId) },
                                    enabled = canModifyAdditionalArticles,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.llm_related_article_remove),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            if (uiState.manualToolContexts.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.manualToolContexts.forEach { context ->
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    text = context.toolName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Extension,
                                    contentDescription = stringResource(R.string.llm_manual_tool_attached),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveManualToolContext(context.id) },
                                    enabled = !uiState.isGenerating && !uiState.manualToolRunning,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.llm_manual_tool_remove),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onOpenModelPicker,
                    enabled = !uiState.isGenerating && uiState.providers.isNotEmpty(),
                    modifier = Modifier.widthIn(max = 248.dp),
                    label = {
                        Text(
                            uiState.selectedModel
                                ?.takeIf(String::isNotBlank)
                                ?: stringResource(R.string.llm_chat_select_model),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { relatedArticleSheetVisible = true },
                    enabled = canModifyAdditionalArticles,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.llm_related_articles_add),
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (uiState.quickMessages.isNotEmpty()) {
                    IconButton(
                        onClick = { quickMessageSheetVisible = true },
                        enabled = !uiState.isGenerating,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.llm_quick_message_open),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (uiState.webSearchEnabled) {
                    IconButton(
                        onClick = { webSearchSheetVisible = true },
                        enabled = !uiState.isGenerating,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = stringResource(R.string.llm_web_search_mode_title),
                            tint =
                                if (uiState.webSearchMode == WebSearchMode.OFF) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (uiState.manualToolFallbackAvailable) {
                    IconButton(
                        onClick = onOpenManualTool,
                        enabled = !uiState.isGenerating && !uiState.manualToolRunning,
                        modifier = Modifier.size(36.dp),
                    ) {
                        if (uiState.manualToolRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Extension,
                                contentDescription = stringResource(R.string.llm_manual_tool),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { reasoningSheetVisible = true },
                    enabled = !uiState.isGenerating,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lightbulb,
                        contentDescription = stringResource(R.string.llm_settings_reasoning_effort),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isGenerating,
                placeholder = { Text(stringResource(R.string.llm_article_input_hint)) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
                trailingIcon = {
                    IconButton(
                        onClick = if (uiState.isGenerating) onStop else onSend,
                        enabled = uiState.isGenerating || (configured && input.isNotBlank()),
                    ) {
                        Icon(
                            imageVector =
                                if (uiState.isGenerating) Icons.Rounded.Stop
                                else Icons.AutoMirrored.Rounded.Send,
                            contentDescription =
                                if (uiState.isGenerating) stringResource(R.string.llm_chat_stop)
                                else stringResource(R.string.llm_chat_send),
                        )
                    }
                },
            )
        }
    }

    if (webSearchSheetVisible) {
        WebSearchModeSheet(
            currentMode = uiState.webSearchMode,
            onDismiss = { webSearchSheetVisible = false },
            onSelect = onWebSearchModeChange,
        )
    }

    if (reasoningSheetVisible) {
        ReasoningEffortSheet(
            currentEffort = uiState.reasoningEffort,
            onDismiss = { reasoningSheetVisible = false },
            onSelect = onReasoningEffortChange,
        )
    }

    if (quickMessageSheetVisible) {
        QuickMessageSheet(
            messages = uiState.quickMessages,
            onDismiss = { quickMessageSheetVisible = false },
            onSelect = onQuickMessage,
        )
    }

    if (relatedArticleSheetVisible) {
        RelatedArticlePickerSheet(
            uiState = uiState,
            canModify = canModifyAdditionalArticles,
            onDismiss = { relatedArticleSheetVisible = false },
            onLoadCandidates = onLoadArticleCandidates,
            onAttach = onAttachArticleCandidate,
        )
    }
}

/**
 * 多文章 Context 的显式选择器。
 *
 * 空搜索展示“最近文章”（项目没有真实打开时间记录，因此不宣称是最近阅读）；输入关键字后只搜索标题。
 * 候选正文不会因为打开此面板就进入模型，只有用户点击具体文章后才转换成活动附件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelatedArticlePickerSheet(
    uiState: LlmChatUiState,
    canModify: Boolean,
    onDismiss: () -> Unit,
    onLoadCandidates: (String) -> Unit,
    onAttach: (LlmArticleCandidate) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val attachedIds = remember(uiState.additionalArticleAttachments) {
        uiState.additionalArticleAttachments.mapTo(hashSetOf()) { it.articleId }
    }
    val selectionLimitReached = attachedIds.size >= MAX_ADDITIONAL_ARTICLES

    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(250)
        onLoadCandidates(query)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_related_articles_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    stringResource(
                        R.string.llm_related_articles_selection_count,
                        attachedIds.size,
                        MAX_ADDITIONAL_ARTICLES,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = canModify,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                    )
                },
                placeholder = { Text(stringResource(R.string.llm_related_articles_search_hint)) },
            )
            Text(
                text =
                    stringResource(
                        if (query.isBlank()) R.string.llm_related_articles_recent
                        else R.string.llm_related_articles_results
                    ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                uiState.articleCandidatesLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                uiState.articleCandidatesLoadFailed -> {
                    Text(
                        text = stringResource(R.string.llm_related_articles_load_failed),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                uiState.articleCandidates.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.llm_related_articles_empty),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f),
                    ) {
                        items(
                            items = uiState.articleCandidates,
                            key = { it.articleId },
                        ) { candidate ->
                            val attached = candidate.articleId in attachedIds
                            val canAttach = canModify && !attached && !selectionLimitReached
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = canAttach) {
                                            onAttach(candidate)
                                        }
                                        .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = candidate.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = candidate.feedName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    imageVector = if (attached) Icons.Rounded.Check else Icons.Rounded.Add,
                                    contentDescription =
                                        stringResource(
                                            if (attached) R.string.llm_related_article_attached
                                            else R.string.llm_related_articles_add
                                        ),
                                    tint =
                                        if (attached) MaterialTheme.colorScheme.primary
                                        else if (canAttach) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chat 只保留一个 Quick Messages 紧凑入口；具体快捷项在这里按需展开。
 * 点击后直接发送普通 USER 消息，模板变量缺失时停留在面板并明确说明原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickMessageSheet(
    messages: List<LlmQuickMessage>,
    onDismiss: () -> Unit,
    onSelect: (LlmQuickMessage) -> LlmQuickMessageResolution,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var resolutionError by remember { mutableStateOf<LlmQuickMessageResolution?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_quick_messages_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            resolutionError?.let { error ->
                val errorText =
                    when {
                        error.unsupportedVariables.isNotEmpty() ->
                            stringResource(
                                R.string.llm_quick_message_unsupported_variables,
                                error.unsupportedVariables.joinToString(", "),
                            )
                        error.unavailableVariables.isNotEmpty() ->
                            stringResource(
                                R.string.llm_quick_message_missing_variables,
                                error.unavailableVariables.joinToString(", "),
                            )
                        else -> stringResource(R.string.llm_quick_message_empty_content)
                    }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = errorText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(messages, key = LlmQuickMessage::id) { message ->
                    val displayText = resolveQuickMessageText(context, message)
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val resolution = onSelect(message)
                                    if (resolution.ready) {
                                        onDismiss()
                                    } else {
                                        resolutionError = resolution
                                    }
                                },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = displayText.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = displayText.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Search 的专用结果详情。
 *
 * 这里只展示当前 Assistant 已冻结的 WEB_SEARCH_RESULT ContextRef；usageState 直接来自
 * includedInPrompt / truncatedInPrompt，不按搜索排名猜测“模型可能看过”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebSearchResultsSheet(
    query: String?,
    providerName: String?,
    results: List<WebSearchResultUiModel>,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_web_search_activity_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            query?.let { frozenQuery ->
                Text(
                    text = stringResource(R.string.llm_web_search_activity_query, frozenQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text =
                    providerName?.let { provider ->
                        stringResource(
                            R.string.llm_web_search_activity_result_count_provider,
                            provider,
                            results.size,
                        )
                    } ?: stringResource(
                        R.string.llm_web_search_activity_result_count,
                        results.size,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.llm_web_search_results_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (results.isEmpty()) {
                Text(
                    text = stringResource(R.string.llm_web_search_activity_no_results),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.72f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                ) {
                    items(
                        items = results,
                        key = WebSearchResultUiModel::id,
                        contentType = { "web-search-result" },
                    ) { result ->
                        WebSearchResultCard(
                            result = result,
                            onOpen = { sourceUrl ->
                                runCatching { uriHandler.openUri(sourceUrl) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebSearchResultCard(
    result: WebSearchResultUiModel,
    onOpen: (String) -> Unit,
) {
    val sourceInitial =
        (result.domain ?: result.title)
            ?.trim()
            ?.firstOrNull()
            ?.uppercaseChar()
            ?.toString()
            ?: "?"
    Surface(
        modifier =
            Modifier.fillMaxWidth().clickable(
                enabled = result.sourceUrl != null,
                onClick = { result.sourceUrl?.let(onOpen) },
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = sourceInitial,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.title ?: stringResource(R.string.llm_web_search_result_untitled),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            result.domain
                                ?: stringResource(R.string.llm_web_search_result_unknown_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WebSearchUsageBadge(result.usageState)
            }

            result.preview?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            result.sourceUrl?.let { sourceUrl ->
                Text(
                    text = sourceUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { onOpen(sourceUrl) },
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.llm_context_open_source))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WebSearchUsageBadge(state: WebSearchResultUsageState) {
    val label =
        stringResource(
            when (state) {
                WebSearchResultUsageState.USED -> R.string.llm_context_included
                WebSearchResultUsageState.USED_TRUNCATED -> R.string.llm_context_truncated
                WebSearchResultUsageState.OMITTED -> R.string.llm_context_omitted
            }
        )
    val containerColor =
        when (state) {
            WebSearchResultUsageState.USED -> MaterialTheme.colorScheme.primaryContainer
            WebSearchResultUsageState.USED_TRUNCATED -> MaterialTheme.colorScheme.secondaryContainer
            WebSearchResultUsageState.OMITTED -> MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        when (state) {
            WebSearchResultUsageState.USED -> MaterialTheme.colorScheme.onPrimaryContainer
            WebSearchResultUsageState.USED_TRUNCATED -> MaterialTheme.colorScheme.onSecondaryContainer
            WebSearchResultUsageState.OMITTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * 展示某一次 Assistant 请求冻结下来的 ContextRef。
 * 历史来源只读，不允许在这里删除；“未纳入”明确表示该候选资料没有进入当次模型 Prompt。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSourcesSheet(
    refs: List<LlmContextRefEntity>,
    currentArticleId: String,
    onOpenArticle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val orderedRefs =
        remember(refs) {
            refs.sortedWith(
                compareByDescending<LlmContextRefEntity> { it.includedInPrompt }
                    .thenByDescending { it.priority }
                    .thenBy { it.createdAt }
            )
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_context_sources_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.llm_context_sources_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.72f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(orderedRefs, key = LlmContextRefEntity::id) { ref ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = ref.title ?: contextTypeLabel(ref, currentArticleId),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = contextRefStatusLabel(ref),
                                    style = MaterialTheme.typography.labelMedium,
                                    color =
                                        if (ref.includedInPrompt) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = contextTypeLabel(ref, currentArticleId),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ref.sourceId?.takeIf(String::isNotBlank)?.let { source ->
                                Text(
                                    text = stringResource(R.string.llm_context_source, source),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val preview =
                                (ref.promptContentSnapshot ?: ref.contentSnapshot)
                                    .trim()
                                    .take(CONTEXT_SOURCE_PREVIEW_LIMIT)
                            if (preview.isNotBlank()) {
                                Text(
                                    text = preview,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            ref.articleId?.takeIf(String::isNotBlank)?.let { articleId ->
                                TextButton(
                                    onClick = {
                                        onDismiss()
                                        onOpenArticle(articleId)
                                    },
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(stringResource(R.string.llm_context_open_article))
                                }
                            } ?: ref.sourceUrl?.let { sourceUrl ->
                                TextButton(
                                    onClick = { runCatching { uriHandler.openUri(sourceUrl) } },
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(stringResource(R.string.llm_context_open_source))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun contextRefStatusLabel(ref: LlmContextRefEntity): String =
    when {
        !ref.includedInPrompt -> stringResource(R.string.llm_context_omitted)
        ref.truncatedInPrompt -> stringResource(R.string.llm_context_truncated)
        else -> stringResource(R.string.llm_context_included)
    }

@Composable
private fun contextTypeLabel(
    ref: LlmContextRefEntity,
    currentArticleId: String,
): String =
    stringResource(
        when (ref.type) {
            LlmContextType.ARTICLE ->
                if (ref.articleId != null && ref.articleId != currentArticleId) {
                    R.string.llm_context_type_related_article
                } else {
                    R.string.llm_context_type_article
                }
            LlmContextType.ARTICLE_SUMMARY -> R.string.llm_context_type_summary
            LlmContextType.ARTICLE_TRANSLATION -> R.string.llm_context_type_translation
            LlmContextType.SELECTED_TEXT -> R.string.llm_context_type_selection
            LlmContextType.MANUAL -> R.string.llm_context_type_manual
            LlmContextType.WEB_SEARCH_RESULT -> R.string.llm_context_type_web_search
            LlmContextType.TOOL_RESULT -> R.string.llm_context_type_tool_result
        }
    )

/**
 * 不支持标准 Tool Calling 模型的显式 MCP Tool 降级入口。
 * Tool Result 由 ViewModel 转成 TOOL_RESULT Context；这里不构造任何假的 assistant.tool_calls。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualToolSheet(
    tools: List<LlmToolDescriptor>,
    running: Boolean,
    onDismiss: () -> Unit,
    onRun: (String, String) -> Unit,
) {
    var selectedToolId by remember(tools) { mutableStateOf<String?>(null) }
    var argumentsJson by remember(selectedToolId) { mutableStateOf("{}") }
    val selectedTool = tools.firstOrNull { it.id == selectedToolId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_manual_tool_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.llm_manual_tool_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selectedTool == null) {
                tools.forEach { tool ->
                    Surface(
                        onClick = { selectedToolId = tool.id },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(tool.name, style = MaterialTheme.typography.titleSmall)
                            tool.description.takeIf(String::isNotBlank)?.let { description ->
                                Text(
                                    text = description,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { selectedToolId = null }, enabled = !running) {
                        Text(stringResource(R.string.back))
                    }
                    Text(
                        text = selectedTool.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.llm_manual_tool_schema),
                    style = MaterialTheme.typography.titleSmall,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        text = selectedTool.inputSchemaJson.take(6000),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = argumentsJson,
                    onValueChange = { argumentsJson = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running,
                    minLines = 3,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.llm_manual_tool_arguments)) },
                )
                TextButton(
                    onClick = { onRun(selectedTool.id, argumentsJson) },
                    enabled = !running,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.llm_manual_tool_run))
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

private fun LlmReasoningEffort.displayName(): String =
    when (this) {
        LlmReasoningEffort.AUTO -> "Auto"
        LlmReasoningEffort.MINIMAL -> "Minimal"
        LlmReasoningEffort.LOW -> "Low"
        LlmReasoningEffort.MEDIUM -> "Medium"
        LlmReasoningEffort.HIGH -> "High"
        LlmReasoningEffort.MAXIMUM -> "Maximum"
    }

/** Web Search 模式面板；FORCE 只武装下一条消息，不会变成永久搜索开关。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebSearchModeSheet(
    currentMode: WebSearchMode,
    onDismiss: () -> Unit,
    onSelect: (WebSearchMode) -> Unit,
) {
    val options =
        listOf(
            WebSearchMode.OFF to
                (R.string.llm_web_search_mode_off to R.string.llm_web_search_mode_off_desc),
            WebSearchMode.AUTO to
                (R.string.llm_web_search_mode_auto to R.string.llm_web_search_mode_auto_desc),
            WebSearchMode.FORCE to
                (R.string.llm_web_search_mode_force to R.string.llm_web_search_mode_force_desc),
        )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_web_search_mode_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.llm_web_search_mode_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            options.forEach { (mode, labels) ->
                val selected = currentMode == mode
                Surface(
                    onClick = {
                        onSelect(mode)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color =
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = null,
                            tint =
                                if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(labels.first),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(labels.second),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

/** 对话内 Reasoning Effort 快速调节面板，使用移动端离散 Slider 表达强度档位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningEffortSheet(
    currentEffort: LlmReasoningEffort,
    onDismiss: () -> Unit,
    onSelect: (LlmReasoningEffort) -> Unit,
) {
    val efforts =
        remember {
            listOf(
                LlmReasoningEffort.AUTO,
                LlmReasoningEffort.MINIMAL,
                LlmReasoningEffort.LOW,
                LlmReasoningEffort.MEDIUM,
                LlmReasoningEffort.HIGH,
                LlmReasoningEffort.MAXIMUM,
            )
        }
    var sliderPosition by
        rememberSaveable(currentEffort) {
            mutableStateOf(efforts.indexOf(currentEffort).coerceAtLeast(0).toFloat())
        }
    val selectedIndex = sliderPosition.roundToInt().coerceIn(efforts.indices)
    val selectedEffort = efforts[selectedIndex]

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.llm_settings_reasoning_effort),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.llm_settings_reasoning_effort_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lightbulb,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = selectedEffort.displayName(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Slider(
                value = sliderPosition,
                onValueChange = { rawValue ->
                    val nextIndex = rawValue.roundToInt().coerceIn(efforts.indices)
                    sliderPosition = nextIndex.toFloat()
                    val nextEffort = efforts[nextIndex]
                    if (nextEffort != currentEffort) {
                        onSelect(nextEffort)
                    }
                },
                valueRange = 0f..efforts.lastIndex.toFloat(),
                steps = (efforts.size - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    uiState: LlmChatUiState,
    onDismiss: () -> Unit,
    onSelect: (providerId: String, model: String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var providerFilterId by rememberSaveable { mutableStateOf<String?>(null) }
    val normalizedQuery = query.trim()
    val groupedModels =
        uiState.providers
            .filter { providerFilterId == null || it.id == providerFilterId }
            .mapNotNull { provider ->
                val models =
                    provider.availableModels().filter { model ->
                        normalizedQuery.isBlank() ||
                            model.contains(normalizedQuery, ignoreCase = true) ||
                            provider.name.contains(normalizedQuery, ignoreCase = true)
                    }
                provider.takeIf { models.isNotEmpty() }?.let { it to models }
            }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.llm_model_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = RoundedCornerShape(26.dp),
            )
            Spacer(Modifier.size(12.dp))

            if (groupedModels.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.llm_model_search_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    groupedModels.forEach { (provider, models) ->
                        item(key = "provider:${provider.id}") {
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = provider.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = models.size.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        models.forEach { model ->
                            item(key = "${provider.id}:$model") {
                                val selected =
                                    uiState.selectedProviderId == provider.id &&
                                        uiState.selectedModel == model
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    color =
                                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow,
                                    border =
                                        if (selected) {
                                            BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                            )
                                        } else {
                                            null
                                        },
                                ) {
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .clickable { onSelect(provider.id, model) }
                                                .padding(horizontal = 14.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(44.dp),
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = provider.monogram(),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = provider.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (selected) {
                                            Spacer(Modifier.width(12.dp))
                                            Surface(
                                                modifier = Modifier.size(32.dp),
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primary,
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = providerFilterId == null,
                    onClick = { providerFilterId = null },
                    label = { Text(stringResource(R.string.all)) },
                )
                uiState.providers.forEach { provider ->
                    FilterChip(
                        selected = providerFilterId == provider.id,
                        onClick = {
                            providerFilterId =
                                if (providerFilterId == provider.id) null else provider.id
                        },
                        label = {
                            Text(
                                text = provider.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 模型卡片使用稳定的 Provider 缩写作为轻量视觉锚点，不引入额外品牌图标依赖。 */
private fun me.ash.reader.infrastructure.ai.AiProviderProfile.monogram(): String =
    name.trim().take(2).ifBlank { "AI" }.uppercase()

@Composable
private fun RenameConversationDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable(currentTitle) { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llm_chat_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}
