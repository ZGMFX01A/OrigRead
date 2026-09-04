package me.ash.reader.ui.page.home.reading

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.ai.availableModels
import me.ash.reader.infrastructure.preference.LocalPullToSwitchArticle
import me.ash.reader.infrastructure.preference.LocalReadingAutoHideToolbar
import me.ash.reader.infrastructure.preference.LocalReadingRenderer
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.LocalSettings
import me.ash.reader.infrastructure.preference.OpenLinkPreference
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.infrastructure.preference.ReadingSharePreference
import me.ash.reader.infrastructure.preference.ReadingShareTarget
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.infrastructure.share.ReadingShareContentBuilder
import me.ash.reader.infrastructure.share.ReadingShareIntent
import me.ash.reader.infrastructure.share.ReadingShareLabels
import me.ash.reader.infrastructure.share.ReadingSharePayload
import me.ash.reader.infrastructure.share.LogseqShare
import me.ash.reader.infrastructure.share.NotionShareTarget
import me.ash.reader.infrastructure.share.ObsidianShare
import me.ash.reader.infrastructure.share.NotionShareEntryPoint
import me.ash.reader.infrastructure.share.NotionShareInProgressException
import me.ash.reader.infrastructure.share.SiYuanShare
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.component.reader.NativeReaderAnchorNavigationResult
import me.ash.reader.ui.component.reader.NativeReaderAnchorState
import me.ash.reader.ui.component.reader.PendingCitationNavigation
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerState
import me.ash.reader.ui.component.webview.WebViewReaderAnchorNavigationResult
import me.ash.reader.ui.component.webview.WebViewReaderAnchorState
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton

private const val UPWARD = 1
private const val DOWNWARD = -1

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterialApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun ReadingPage(
    //    navController: NavHostController,
    viewModel: ArticleListReaderViewModel,
    navigationAction: NavigationAction,
    onLoadArticle: (String, Int) -> Unit,
    onNavAction: (NavigationAction) -> Unit,
    onNavigateToStylePage: () -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val motionScheme = MaterialTheme.motionScheme
    val readingRenderer = LocalReadingRenderer.current
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val isPullToSwitchArticleEnabled = LocalPullToSwitchArticle.current.value
    val readingUiState = viewModel.readingUiState.collectAsStateValue()
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()
    val translationState = viewModel.translationUiState.collectAsStateValue()
    val translationSettings = viewModel.translationSettings.collectAsStateValue()
    val aiSummaryState = viewModel.aiSummaryUiState.collectAsStateValue()
    val aiSettings = viewModel.aiSettings.collectAsStateValue()
    val llmSettings = viewModel.llmSettings.collectAsStateValue()
    val aiAssistantEnabled = llmSettings.assistantEnabled
    val notionShareRepository =
        remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                NotionShareEntryPoint::class.java,
            ).notionShareRepository()
        }
    val notionShareConfiguration = notionShareRepository.configuration.collectAsStateValue()
    val notionShareInProgress = notionShareRepository.shareInProgress.collectAsStateValue()
    val nativeReaderAnchorState = remember { NativeReaderAnchorState() }
    val webViewReaderAnchorState = remember { WebViewReaderAnchorState() }
    val readerEvidenceMarkerState = remember { ReaderEvidenceMarkerState() }

    var isReaderScrollingDown by remember { mutableStateOf(false) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var showAiSummaryOptions by remember { mutableStateOf(false) }
    var showArticleAssistant by remember { mutableStateOf(false) }
    var articleAnalysisRequested by remember(readerState.articleId) { mutableStateOf(false) }
    var selectedTextForAssistant by remember(readerState.articleId) { mutableStateOf<String?>(null) }
    var selectedTextForAssistantFromTranslation by remember(readerState.articleId) {
        mutableStateOf(false)
    }
    var pendingCitationNavigation by remember { mutableStateOf<PendingCitationNavigation?>(null) }
    var citationNavigationFailure by remember { mutableStateOf<PendingCitationNavigation?>(null) }
    var showInteractiveVerification by remember { mutableStateOf(false) }
    var showReadingShareFirstUse by remember { mutableStateOf(false) }
    var showReadingShareConfig by remember { mutableStateOf(false) }

    /**
     * 打开文章助手时显式确定本次 Selection 生命周期。
     * 普通 Ask / 深度分析必须清掉旧选区，只有本次真正由正文选区触发时才携带 selectedText。
     */
    fun openArticleAssistant(
        selectedText: String? = null,
        selectedTextFromTranslation: Boolean = false,
        analyzeArticle: Boolean = false,
    ) {
        selectedTextForAssistant = selectedText?.trim()?.takeIf(String::isNotBlank)
        selectedTextForAssistantFromTranslation =
            selectedTextForAssistant != null && selectedTextFromTranslation
        articleAnalysisRequested = analyzeArticle
        showArticleAssistant = true
    }

    /** 关闭助手即结束本次临时 Selection，防止同一文章稍后从普通入口重开时静默复用旧选区。 */
    fun dismissArticleAssistant() {
        pendingCitationNavigation = null
        citationNavigationFailure = null
        showArticleAssistant = false
        articleAnalysisRequested = false
        selectedTextForAssistant = null
        selectedTextForAssistantFromTranslation = false
        readerEvidenceMarkerState.clear()
    }

    fun hideArticleAssistantForCitation() {
        showArticleAssistant = false
        articleAnalysisRequested = false
        selectedTextForAssistant = null
        selectedTextForAssistantFromTranslation = false
    }

    LaunchedEffect(aiAssistantEnabled) {
        if (!aiAssistantEnabled) dismissArticleAssistant()
    }

    LaunchedEffect(
        pendingCitationNavigation,
        readerState.articleId,
        readerState.content,
        translationState.showTranslation,
        readingRenderer,
        nativeReaderAnchorState.readyRevision,
        webViewReaderAnchorState.readyRevision,
    ) {
        val pending = pendingCitationNavigation ?: return@LaunchedEffect
        val currentArticleId = readerState.articleId?.trim()?.ifBlank { null } ?: return@LaunchedEffect
        val targetArticleId = pending.articleId.trim()

        if (!pending.isTargetArticle(currentArticleId)) {
            if (pending.shouldInvalidateForArticle(currentArticleId)) {
                pendingCitationNavigation = null
            }
            return@LaunchedEffect
        }
        if (readerState.content is ReaderState.Error) {
            citationNavigationFailure = pending
            pendingCitationNavigation = null
            return@LaunchedEffect
        }
        if (translationState.showTranslation) {
            viewModel.showOriginalContentForCitation()
            return@LaunchedEffect
        }

        when (readingRenderer) {
            ReadingRendererPreference.NativeComponent -> {
                if (nativeReaderAnchorState.readyArticleId != targetArticleId) return@LaunchedEffect
                when (nativeReaderAnchorState.navigateTo(pending.target)) {
                    is NativeReaderAnchorNavigationResult.Located -> {
                        if (pending.sameRequest(pendingCitationNavigation)) {
                            pendingCitationNavigation = null
                        }
                    }
                    is NativeReaderAnchorNavigationResult.Unavailable -> {
                        if (pending.sameRequest(pendingCitationNavigation)) {
                            citationNavigationFailure = pending
                            pendingCitationNavigation = null
                        }
                    }
                }
            }
            ReadingRendererPreference.WebView -> {
                if (webViewReaderAnchorState.readyArticleId != targetArticleId) return@LaunchedEffect
                webViewReaderAnchorState.navigateTo(pending.target) { result ->
                    if (!pending.sameRequest(pendingCitationNavigation)) return@navigateTo
                    when (result) {
                        is WebViewReaderAnchorNavigationResult.Located -> {
                            pendingCitationNavigation = null
                        }
                        WebViewReaderAnchorNavigationResult.Pending -> Unit
                        is WebViewReaderAnchorNavigationResult.Unavailable -> {
                            citationNavigationFailure = pending
                            pendingCitationNavigation = null
                        }
                    }
                }
            }
        }
    }

    var currentImageData by remember { mutableStateOf(ImageData()) }

    val isShowToolBar =
        if (aiSummaryState.showPanel) {
            true
        } else if (LocalReadingAutoHideToolbar.current.value) {
            readerState.articleId != null && !isReaderScrollingDown
        } else {
            true
        }

    var showTopDivider by remember { mutableStateOf(false) }

    //    LaunchedEffect(readerState.listIndex) {
    //        readerState.listIndex?.let {
    //            navController.previousBackStackEntry?.savedStateHandle?.set("articleIndex", it)
    //        }
    //    }

    var bringToTop by remember { mutableStateOf(false) }

    val visibleTranslation =
        translationState.document.takeIf { translationState.showTranslation }
    val displayedTitle = visibleTranslation?.translatedTitle ?: readerState.title
    val displayedContent = visibleTranslation?.translatedContent ?: readerState.content.text.orEmpty()
    val articleAssistantContext =
        readerState.articleId?.let { articleId ->
            ArticleAssistantContext(
                articleId = articleId,
                title = readerState.title.orEmpty(),
                link = readerState.link,
                originalContent = readerState.content.text.orEmpty(),
                // Chat 的自动文章事实来源固定为原文；整篇摘要/译文不进入 Conversation Context。
                // 用户显式选中的译文片段可以参与提问，但 LLM 层不会把它映射成原文 Citation。
                selectedText = selectedTextForAssistant,
                selectedTextFromTranslation = selectedTextForAssistantFromTranslation,
            )
        }
    val readingSharePreference = settings.readingShare
    val readingShareLabels =
        ReadingShareLabels(
            sourceUrl = stringResource(R.string.reading_share_source_url),
            translation = stringResource(R.string.reading_share_translation_option),
            summary = stringResource(R.string.reading_share_summary_option),
        )

    fun shareReading(preference: ReadingSharePreference) {
        fun shareToSystem(intent: Intent) {
            context.startActivity(
                Intent.createChooser(
                    intent,
                    context.getString(R.string.share),
                )
            )
        }

        fun shareToConfiguredTarget(payload: ReadingSharePayload): Boolean {
            when (preference.target) {
                ReadingShareTarget.OBSIDIAN -> {
                    if (ObsidianShare.share(context, readerState.title, payload.markdown)) return true
                    context.showToast(
                        context.getString(
                            R.string.reading_share_target_unavailable_fallback,
                            "Obsidian",
                        )
                    )
                }
                ReadingShareTarget.SIYUAN -> {
                    if (SiYuanShare.share(context, readerState.title, payload.markdown)) return true
                    context.showToast(
                        context.getString(
                            R.string.reading_share_target_unavailable_fallback,
                            "思源",
                        )
                    )
                }
                ReadingShareTarget.LOGSEQ -> {
                    if (
                        LogseqShare.share(
                            context = context,
                            title = readerState.title,
                            url = readerState.link,
                            markdown = payload.markdown,
                        )
                    ) {
                        return true
                    }
                    context.showToast(
                        context.getString(
                            R.string.reading_share_target_unavailable_fallback,
                            "Logseq",
                        )
                    )
                }
                ReadingShareTarget.NOTION -> {
                    if (!NotionShareTarget.availability(context).available) {
                        context.showToast(
                            context.getString(
                                R.string.reading_share_target_unavailable_fallback,
                                "Notion",
                            )
                        )
                    } else if (!notionShareConfiguration.tokenConfigured) {
                        showReadingShareConfig = true
                        context.showToast(context.getString(R.string.reading_share_notion_not_configured))
                        return false
                    } else {
                        context.showToast(context.getString(R.string.reading_share_notion_in_progress))
                        coroutineScope.launch {
                            notionShareRepository.share(readerState.title, payload).fold(
                                onSuccess = { pageUrl ->
                                    context.showToast(
                                        context.getString(R.string.reading_share_notion_success, pageUrl),
                                    )
                                    val notionIntent =
                                        Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).apply {
                                            setPackage(NotionShareTarget.packageName)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    runCatching { context.startActivity(notionIntent) }
                                        .onFailure {
                                            context.openURL(
                                                pageUrl,
                                                OpenLinkPreference.AutoPreferDefaultBrowser,
                                            )
                                        }
                                },
                                onFailure = { error ->
                                    if (error !is NotionShareInProgressException) {
                                        context.showToast(
                                            context.getString(
                                                R.string.reading_share_notion_failure,
                                                error.message.orEmpty(),
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                        return true
                    }
                }
                ReadingShareTarget.SYSTEM -> Unit
            }
            shareToSystem(ReadingShareIntent.create(readerState.title, payload))
            return false
        }

        val payload =
            ReadingShareContentBuilder.build(
                title = readerState.title,
                link = readerState.link,
                body = readerState.content.text,
                // 只分享当前阅读页正在显示的翻译；历史缓存不代表用户本次要分享。
                translatedTitle = visibleTranslation?.translatedTitle,
                translatedContent = visibleTranslation?.translatedContent,
                translatedDisplayMode =
                    visibleTranslation?.displayMode ?: TranslationDisplayMode.TRANSLATED,
                // 摘要面板关闭时，即使有历史结果也不带入分享内容。
                summary =
                    aiSummaryState.document?.summary
                        ?.takeIf { aiSummaryState.showPanel },
                preference = preference,
                labels = readingShareLabels,
                bodyTitle =
                    when (preference.target) {
                        // 文件名/页面标题已经由专有渠道单独写入；正文只保留当前已打开的译文标题。
                        ReadingShareTarget.OBSIDIAN,
                        ReadingShareTarget.LOGSEQ,
                        ReadingShareTarget.NOTION -> visibleTranslation?.translatedTitle
                            ?.takeIf { preference.includeTranslation }
                        ReadingShareTarget.SYSTEM,
                        ReadingShareTarget.SIYUAN -> readerState.title
                    },
            )
        shareToConfiguredTarget(payload)
    }
    val enabledAiProviders =
        if (!aiSettings.enabled) {
            emptyList()
        } else {
            aiSettings.providers.filter { provider ->
                provider.enabled && provider.endpoint.isNotBlank() && provider.availableModels().isNotEmpty()
            }
        }
    val traditionalTranslationTargets =
        TranslationProviderType.entries
            .filter(viewModel::isTranslationProviderConfigured)
            .map(TranslationTarget::Traditional)
    val aiTranslationTargets =
        enabledAiProviders.flatMap { provider ->
            provider.availableModels()
                .map { model ->
                    TranslationTarget.Ai(
                        providerId = provider.id,
                        providerName = provider.name,
                        model = model,
                    )
                }
        }
    val translationTargets = traditionalTranslationTargets + aiTranslationTargets
    val defaultTranslationTarget = viewModel.resolveDefaultTranslationTarget()
    val activeTranslationTarget = translationState.document?.target
    // AI 按钮是否可点击只由“当前是否已有正文”决定。
    // 配置缺失、全局未启用等问题交给既有 AI 错误链明确提示，不能再通过隐藏/禁用按钮静默吞掉。
    val aiSummaryAvailable = readerState.content.text?.isNotBlank() == true
    val ttsState = viewModel.textToSpeechManager.stateFlow.collectAsStateValue()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel.textToSpeechManager, readerState.articleId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.textToSpeechManager.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.textToSpeechManager.stop()
        }
    }

    LaunchedEffect(readerState.articleId) {
        val currentArticleId = readerState.articleId?.trim()?.ifBlank { null }
        val keepCitationMarker =
            currentArticleId != null && pendingCitationNavigation?.isTargetArticle(currentArticleId) == true
        if (!keepCitationMarker) {
            readerEvidenceMarkerState.clear()
        }
        // 阅读对象变化时关闭上一文章的助手，避免旧会话覆盖在新正文之上。
        showArticleAssistant = false
    }

    LaunchedEffect(translationState.errorMessage) {
        translationState.errorMessage?.let { message ->
            context.showToast(message)
            viewModel.clearTranslationError()
        }
    }

    LaunchedEffect(aiSummaryState.errorMessage) {
        aiSummaryState.errorMessage?.let { message ->
            context.showToast(message)
            viewModel.clearAiSummaryError()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        content = { paddings ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (readerState.articleId != null) {
                    TopBar(
                        isShow = isShowToolBar,
                        isScrolled = showTopDivider,
                        title = displayedTitle,
                        link = readerState.link,
                        onClick = { bringToTop = true },
                        navigationAction = navigationAction,
                        onNavButtonClick = onNavAction,
                        onNavigateToStylePage = onNavigateToStylePage,
                        showReadAloudButton = readerState.content.text?.isNotBlank() == true,
                        showReadOriginalButton =
                            readerState.link?.let {
                                it.startsWith("http://") || it.startsWith("https://")
                            } == true,
                        showFullContentButton =
                            readingUiState.articleWithFeed?.feed?.sourceType == SourceType.RSS,
                        isFullContent =
                            readerState.content is ReaderState.FullContent ||
                                readerState.content is ReaderState.Error,
                        onReadOriginal = {
                            context.openURL(
                                readerState.link,
                                OpenLinkPreference.AutoPreferCustomTabs,
                            )
                        },
                        onFullContent = {
                            if (it) viewModel.renderFullContent()
                            else viewModel.renderDescriptionContent()
                        },
                        onShare = {
                            if (readingSharePreference.isConfigured) {
                                shareReading(readingSharePreference)
                            } else {
                                showReadingShareFirstUse = true
                            }
                        },
                        shareEnabled = !notionShareInProgress,
                        onShareLongClick = { showReadingShareConfig = true },
                        ttsButton = {
                            TtsButton(
                                onClick = { state ->
                                    when (state) {
                                        TextToSpeechManager.State.Error -> {
                                            context.showToast("TextToSpeech initialization failed")
                                        }

                                        TextToSpeechManager.State.Idle -> {
                                            viewModel.textToSpeechManager.readHtml(displayedContent)
                                        }

                                        is TextToSpeechManager.State.Reading -> {
                                            viewModel.textToSpeechManager.stop()
                                        }

                                        TextToSpeechManager.State.Preparing -> {
                                            /* no-op */
                                        }
                                    }
                                },
                                state = ttsState,
                            )
                        },
                    )
                }

                val isNextArticleAvailable = readerState.nextArticle != null
                val isPreviousArticleAvailable = readerState.previousArticle != null

                if (readerState.articleId != null) {
                    // Content
                    AnimatedContent(
                        targetState = readerState,
                        transitionSpec = {
                            val direction =
                                when {
                                    initialState.nextArticle?.articleId == targetState.articleId ->
                                        UPWARD
                                    initialState.previousArticle?.articleId ==
                                        targetState.articleId -> DOWNWARD
                                    initialState.articleId == targetState.articleId -> {
                                        when (targetState.content) {
                                            is ReaderState.Description -> DOWNWARD
                                            else -> UPWARD
                                        }
                                    }

                                    else -> UPWARD
                                }
                            (slideInVertically(
                                initialOffsetY = { (it * 0.2f * direction).toInt() },
                                animationSpec = motionScheme.defaultSpatialSpec(),
                            ) +
                                fadeIn(
                                    animationSpec = motionScheme.defaultEffectsSpec()
                                )) togetherWith
                                (slideOutVertically(
                                    targetOffsetY = { (it * -0.2f * direction).toInt() },
                                    animationSpec = motionScheme.defaultSpatialSpec(),
                                ) +
                                    fadeOut(
                                        animationSpec = motionScheme.fastEffectsSpec()
                                    ))
                        },
                        label = "",
                    ) {
                        remember { it }
                            .run {
                                val state =
                                    rememberPullToLoadState(
                                        key = content,
                                        onLoadNext =
                                            if (isNextArticleAvailable) {
                                                {
                                                    val (id, index) = readerState.nextArticle
                                                    onLoadArticle(id, index)
                                                }
                                            } else null,
                                        onLoadPrevious =
                                            if (isPreviousArticleAvailable) {
                                                {
                                                    val (id, index) = readerState.previousArticle
                                                    onLoadArticle(id, index)
                                                }
                                            } else null,
                                    )

                                val listState =
                                    rememberSaveable(
                                        inputs = arrayOf(content),
                                        saver = LazyListState.Saver,
                                    ) {
                                        LazyListState()
                                    }

                                val scrollState = rememberScrollState()

                                val scope = rememberCoroutineScope()

                                LaunchedEffect(bringToTop) {
                                    if (bringToTop) {
                                        scope
                                            .launch {
                                                if (scrollState.value != 0) {
                                                    scrollState.animateScrollTo(0)
                                                } else if (listState.firstVisibleItemIndex != 0) {
                                                    listState.animateScrollToItem(0)
                                                }
                                            }
                                            .invokeOnCompletion { bringToTop = false }
                                    }
                                }

                                showTopDivider =
                                    snapshotFlow {
                                            scrollState.value >= 120 ||
                                                listState.firstVisibleItemIndex != 0
                                        }
                                        .collectAsStateValue(initial = false)

                                CompositionLocalProvider(
                                    LocalTextStyle provides
                                        LocalTextStyle.current.run {
                                            merge(
                                                lineHeight =
                                                    if (lineHeight.isSpecified)
                                                        (lineHeight.value *
                                                                LocalReadingTextLineHeight.current)
                                                            .sp
                                                    else TextUnit.Unspecified
                                            )
                                        }
                                ) {
                                    val summaryDocument = aiSummaryState.document
                                    val showSummaryPanel =
                                        aiSummaryState.showPanel &&
                                            (summaryDocument != null || aiSummaryState.isLoading)
                                    StableSummaryReadingLayout(
                                        modifier = Modifier.fillMaxSize(),
                                        summaryTopOffset = paddings.calculateTopPadding() + 64.dp,
                                        summaryContent =
                                            if (showSummaryPanel) {
                                                {
                                                    Column(modifier = Modifier.fillMaxWidth()) {
                                                        AiSummaryPanel(
                                                            document = summaryDocument,
                                                            isLoading = aiSummaryState.isLoading,
                                                            progressStage = aiSummaryState.progressStage,
                                                            elapsedSeconds = aiSummaryState.elapsedSeconds,
                                                            activeProviderName =
                                                                aiSummaryState.activeProviderName,
                                                            activeModel = aiSummaryState.activeModel,
                                                            streamingSummaryPreview =
                                                                aiSummaryState.streamingSummaryPreview,
                                                            streamingReasoningPreview =
                                                                aiSummaryState.streamingReasoningPreview,
                                                            onClose = viewModel::dismissAiSummary,
                                                            onRegenerate = {
                                                                showAiSummaryOptions = true
                                                            },
                                                            onAskArticle =
                                                                if (aiAssistantEnabled) {
                                                                    { openArticleAssistant() }
                                                                } else {
                                                                    null
                                                                },
                                                            onStop = viewModel::stopAiSummary,
                                                            modifier =
                                                                Modifier.fillMaxWidth()
                                                                    .padding(
                                                                        start = 10.dp,
                                                                        top = 6.dp,
                                                                        end = 10.dp,
                                                                    ),
                                                        )
                                                        // 摘要与正文之间保留现有视觉间距；整段高度只转成正文顶部滚动占位，不挤压 viewport。
                                                        Spacer(modifier = Modifier.height(10.dp))
                                                    }
                                                }
                                            } else {
                                                null
                                            },
                                    ) { summaryReservedHeight ->
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Content(
                                                modifier =
                                                    Modifier.pullToLoad(
                                                        state = state,
                                                        onScroll = { f ->
                                                            if (abs(f) > 2f)
                                                                isReaderScrollingDown = f < 0f
                                                        },
                                                        enabled = isPullToSwitchArticleEnabled,
                                                    ),
                                                articleId = readerState.articleId,
                                                contentPadding = paddings,
                                                // 摘要高度进入滚动内容本身，不再通过压缩 Content viewport 预留空间。
                                                topBarSpacerHeight = 64.dp + summaryReservedHeight,
                                                topContentPadding = paddings.calculateTopPadding(),
                                                content =
                                                    if (translationState.showTranslation) {
                                                        visibleTranslation?.translatedContent
                                                            ?: content.text.orEmpty()
                                                    } else {
                                                        content.text.orEmpty()
                                                    },
                                                isOriginalContent = !translationState.showTranslation,
                                                nativeReaderAnchorState = nativeReaderAnchorState,
                                                webViewReaderAnchorState = webViewReaderAnchorState,
                                                readerEvidenceMarkerState = readerEvidenceMarkerState,
                                                feedName = feedName,
                                                title =
                                                    if (translationState.showTranslation) {
                                                        visibleTranslation?.translatedTitle
                                                            ?: title.toString()
                                                    } else {
                                                        title.toString()
                                                    },
                                                originalTitle =
                                                    if (translationState.showTranslation && visibleTranslation != null) {
                                                        title.toString()
                                                    } else {
                                                        null
                                                    },
                                                author = author,
                                                link = link,
                                                publishedDate = publishedDate,
                                                isLoading = content is ReaderState.Loading,
                                                failureReason =
                                                    (content as? ReaderState.Error)?.reason,
                                                scrollState = scrollState,
                                                listState = listState,
                                                onReadOriginal = {
                                                    context.openURL(
                                                        link,
                                                        OpenLinkPreference.AutoPreferCustomTabs,
                                                    )
                                                },
                                                onVerifyAndParse =
                                                    if (
                                                        content is ReaderState.Error &&
                                                            content.reason == me.ash.reader.infrastructure.content.FullContentFailureReason.ACCESS_RESTRICTED &&
                                                            !link.isNullOrBlank()
                                                    ) {
                                                        { showInteractiveVerification = true }
                                                    } else {
                                                        null
                                                    },
                                                onImageClick = { imgUrl, altText ->
                                                    currentImageData = ImageData(imgUrl, altText)
                                                    showFullScreenImageViewer = true
                                                },
                                                onSelectedTextAction =
                                                    if (aiAssistantEnabled) {
                                                        { selectedText ->
                                                            openArticleAssistant(
                                                                selectedText = selectedText,
                                                                selectedTextFromTranslation = visibleTranslation != null,
                                                            )
                                                        }
                                                    } else {
                                                        null
                                                    },
                                            )
                                            PullToLoadIndicator(
                                                state = state,
                                                canLoadPrevious = isPreviousArticleAvailable,
                                                canLoadNext = isNextArticleAvailable,
                                            )
                                        }
                                    }
                                }
                            }
                    }
                }
                // Bottom Bar
                if (readerState.articleId != null) {
                    BottomBar(
                        isShow = isShowToolBar,
                        isUnread = readingUiState.isUnread,
                        isStarred = readingUiState.isStarred,
                        isNextArticleAvailable = isNextArticleAvailable,
                        showTranslationButton = readerState.content.text?.isNotBlank() == true,
                        isTranslationLoading = translationState.isLoading,
                        isTranslated = translationState.showTranslation,
                        translationTargets = translationTargets,
                        defaultTranslationTarget = defaultTranslationTarget,
                        activeTranslationTarget = activeTranslationTarget,
                        // AI 是阅读页固定能力，C 位按钮永远保留；配置状态与正文状态只控制可点击性。
                        showAiSummaryButton = true,
                        isAiSummaryEnabled = aiSummaryAvailable,
                        isAiSummaryLoading = aiSummaryState.isLoading,
                        hasAiSummary = aiSummaryState.document != null,
                        aiActionContentDescription =
                            if (aiAssistantEnabled && !llmSettings.defaultGenerateSummary) {
                                stringResource(R.string.llm_article_assistant_title)
                            } else {
                                null
                            },
                        onUnread = { viewModel.updateReadStatus(it) },
                        onStarred = { viewModel.updateStarredStatus(it) },
                        onNextArticle = {
                            readerState.nextArticle?.let {
                                val (id, index) = it
                                onLoadArticle(id, index)
                            }
                        },
                        onAiSummary = {
                            if (aiAssistantEnabled && !llmSettings.defaultGenerateSummary) {
                                openArticleAssistant()
                            } else {
                                viewModel.summarizeArticle()
                            }
                        },
                        onAiSummaryLongClick = { showAiSummaryOptions = true },
                        onStopAiSummary = viewModel::stopAiSummary,
                        onTranslate = viewModel::translateOrToggle,
                        onTranslateWithTarget = viewModel::translateWithTarget,
                        onSetDefaultTranslationTarget = viewModel::setDefaultTranslationTarget,
                    )
                }
            }
        },
    )
    EditionSelectedTextActionHost(
        // WebView 正文已有应用内 ActionMode；PROCESS_TEXT 只给 Compose NativeComponent 提供选区文本兼容通道。
        enabled =
            aiAssistantEnabled &&
                articleAssistantContext != null &&
                readingRenderer == ReadingRendererPreference.NativeComponent,
        onSelectedText = { selectedText ->
            openArticleAssistant(
                selectedText = selectedText,
                selectedTextFromTranslation = visibleTranslation != null,
            )
        },
    )
    EditionArticleAssistantSheet(
        visible = showArticleAssistant && aiAssistantEnabled,
        context = articleAssistantContext,
        articleAnalysisRequested = articleAnalysisRequested,
        onArticleAnalysisConsumed = { articleAnalysisRequested = false },
        onOpenArticle = { articleId ->
            dismissArticleAssistant()
            if (articleId != readerState.articleId) {
                // ContextRef 只保存稳定 articleId，不依赖当时列表位置；-1 会让阅读页按 ID 从当前列表/数据库恢复。
                onLoadArticle(articleId, -1)
            }
        },
        showQuickSummary = aiAssistantEnabled && !llmSettings.defaultGenerateSummary,
        onQuickSummary = { length ->
            // 与 Desktop 一致：快捷摘要切回独立摘要链路，不创建/追加 Chat 消息。
            dismissArticleAssistant()
            viewModel.summarizeArticle(lengthOverride = length)
        },
        onNavigateReaderCitation = { request ->
            citationNavigationFailure = null
            pendingCitationNavigation = request
            hideArticleAssistantForCitation()
            viewModel.showOriginalContentForCitation()
            if (request.articleId != readerState.articleId) {
                onLoadArticle(request.articleId, -1)
            }
        },
        citationNavigationFailure = citationNavigationFailure,
        onCitationNavigationFailureConsumed = {
            citationNavigationFailure = null
            readerEvidenceMarkerState.clear()
        },
        readerEvidenceMarkerState = readerEvidenceMarkerState,
        continueGenerationInBackground = llmSettings.continueGenerationInBackground,
        onDismiss = ::dismissArticleAssistant,
    )
    if (showFullScreenImageViewer) {

        ReaderImageViewer(
            imageData = currentImageData,
            onDownloadImage = {
                viewModel.downloadImage(
                    it,
                    onSuccess = { context.showToast(context.getString(R.string.image_saved)) },
                    onFailure = {
                        // FIXME: crash the app for error report
                        th ->
                        throw th
                    },
                )
            },
            onDismissRequest = { showFullScreenImageViewer = false },
        )
    }
    if (showAiSummaryOptions && enabledAiProviders.isNotEmpty()) {
        AiSummaryOptionsSheet(
            providers = enabledAiProviders,
            defaultProviderId = aiSettings.defaultProviderId,
            initialProviderId =
                aiSummaryState.activeProviderId ?: aiSummaryState.document?.providerId,
            initialModel = aiSummaryState.activeModel ?: aiSummaryState.document?.model,
            initialLength = aiSummaryState.document?.length ?: aiSettings.summaryLength,
            onDismiss = { showAiSummaryOptions = false },
            onAskArticle =
                if (aiAssistantEnabled) {
                    {
                        showAiSummaryOptions = false
                        openArticleAssistant()
                    }
                } else {
                    null
                },
            onAnalyzeArticle =
                if (aiAssistantEnabled) {
                    {
                        showAiSummaryOptions = false
                        openArticleAssistant(analyzeArticle = true)
                    }
                } else {
                    null
                },
            onGenerate = { providerId, model, length ->
                showAiSummaryOptions = false
                viewModel.summarizeArticle(
                    forceRefresh = true,
                    providerId = providerId,
                    modelOverride = model,
                    lengthOverride = length,
                )
            },
        )
    }
    if (showReadingShareFirstUse) {
        ReadingShareFirstUseSheet(
            onDismiss = { showReadingShareFirstUse = false },
            onUseDefault = {
                val configuredDefault = ReadingSharePreference.default.copy(isConfigured = true)
                configuredDefault.save(context, coroutineScope)
                showReadingShareFirstUse = false
                shareReading(configuredDefault)
            },
            onCustomize = {
                showReadingShareFirstUse = false
                showReadingShareConfig = true
            },
        )
    }
    if (showReadingShareConfig) {
        // 每次重新打开配置页时重新探测，避免仅依赖包名导致“已安装却入口消失”。
        val obsidianAvailability = remember { ObsidianShare.availability(context) }
        val siYuanAvailability = remember { SiYuanShare.availability(context) }
        val logseqAvailability = remember { LogseqShare.availability(context) }
        val notionAvailability = remember { NotionShareTarget.availability(context) }
        ReadingShareConfigSheet(
            initialPreference = readingSharePreference,
            obsidianAvailability = obsidianAvailability,
            siYuanAvailability = siYuanAvailability,
            logseqAvailability = logseqAvailability,
            notionAvailability = notionAvailability,
            notionConfiguration = notionShareConfiguration,
            onDismiss = { showReadingShareConfig = false },
            onSave = { preference, notionToken ->
                preference.save(context, coroutineScope)
                notionShareRepository.saveConfiguration(notionToken)
                showReadingShareConfig = false
            },
            onOpenNotionSetup = {
                context.openURL(
                    "https://www.notion.so/developers/tokens",
                    OpenLinkPreference.AutoPreferDefaultBrowser,
                )
            },
        )
    }
    if (showInteractiveVerification && !readerState.link.isNullOrBlank()) {
        InteractiveArticleVerificationDialog(
            url = requireNotNull(readerState.link),
            onDismiss = { showInteractiveVerification = false },
            onCapture = { html, finalUrl ->
                showInteractiveVerification = false
                viewModel.parseVerifiedPage(html, finalUrl)
            },
        )
    }
}
