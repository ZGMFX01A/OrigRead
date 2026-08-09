package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
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
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.OpenLinkPreference
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton

private const val UPWARD = 1
private const val DOWNWARD = -1

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
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
    val hapticFeedback = LocalHapticFeedback.current
    val isPullToSwitchArticleEnabled = LocalPullToSwitchArticle.current.value
    val readingUiState = viewModel.readingUiState.collectAsStateValue()
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()
    val translationState = viewModel.translationUiState.collectAsStateValue()
    val translationSettings = viewModel.translationSettings.collectAsStateValue()
    val aiSummaryState = viewModel.aiSummaryUiState.collectAsStateValue()
    val aiSettings = viewModel.aiSettings.collectAsStateValue()

    var isReaderScrollingDown by remember { mutableStateOf(false) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var showAiSummaryOptions by remember { mutableStateOf(false) }
    var showInteractiveVerification by remember { mutableStateOf(false) }

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
                            val exit = 100
                            val enter = exit * 2
                            (slideInVertically(
                                initialOffsetY = { (it * 0.2f * direction).toInt() },
                                animationSpec =
                                    spring(
                                        dampingRatio = .9f,
                                        stiffness = Spring.StiffnessLow,
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    ),
                            ) +
                                fadeIn(
                                    tween(
                                        delayMillis = exit,
                                        durationMillis = enter,
                                        easing = LinearOutSlowInEasing,
                                    )
                                )) togetherWith
                                (slideOutVertically(
                                    targetOffsetY = { (it * -0.2f * direction).toInt() },
                                    animationSpec =
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessLow,
                                            visibilityThreshold = IntOffset.VisibilityThreshold,
                                        ),
                                ) +
                                    fadeOut(
                                        tween(durationMillis = exit, easing = FastOutLinearInEasing)
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
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        if (showSummaryPanel) {
                                            // TopBar 是覆盖式布局，摘要面板从 TopBar 下方开始占据独立空间。
                                            Spacer(
                                                modifier =
                                                    Modifier.height(
                                                        paddings.calculateTopPadding() + 64.dp
                                                    )
                                            )
                                            AiSummaryPanel(
                                                document = summaryDocument,
                                                isLoading = aiSummaryState.isLoading,
                                                progressStage = aiSummaryState.progressStage,
                                                elapsedSeconds = aiSummaryState.elapsedSeconds,
                                                activeProviderName =
                                                    aiSummaryState.activeProviderName,
                                                activeModel = aiSummaryState.activeModel,
                                                onClose = viewModel::dismissAiSummary,
                                                onRegenerate = { showAiSummaryOptions = true },
                                                modifier =
                                                    Modifier.fillMaxWidth()
                                                        .padding(
                                                            start = 10.dp,
                                                            top = 6.dp,
                                                            end = 10.dp,
                                                        ),
                                            )
                                            // 轻量卡片已经通过背景、细边框和 Accent Bar 区分，间距无需再刻意拉大。
                                            Spacer(modifier = Modifier.height(10.dp))
                                        }
                                        Box(
                                            modifier = Modifier.fillMaxWidth().weight(1f),
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
                                                contentPadding = paddings,
                                                topBarSpacerHeight =
                                                    if (showSummaryPanel) 0.dp else 64.dp,
                                                topContentPadding =
                                                    if (showSummaryPanel) 0.dp
                                                    else paddings.calculateTopPadding(),
                                                content =
                                                    if (translationState.showTranslation) {
                                                        visibleTranslation?.translatedContent
                                                            ?: content.text.orEmpty()
                                                    } else {
                                                        content.text.orEmpty()
                                                    },
                                                feedName = feedName,
                                                title =
                                                    if (translationState.showTranslation) {
                                                        visibleTranslation?.translatedTitle
                                                            ?: title.toString()
                                                    } else {
                                                        title.toString()
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
                        onUnread = { viewModel.updateReadStatus(it) },
                        onStarred = { viewModel.updateStarredStatus(it) },
                        onNextArticle = {
                            readerState.nextArticle?.let {
                                val (id, index) = it
                                onLoadArticle(id, index)
                            }
                        },
                        onAiSummary = { viewModel.summarizeArticle() },
                        onTranslate = viewModel::translateOrToggle,
                        onTranslateWithTarget = viewModel::translateWithTarget,
                        onSetDefaultTranslationTarget = viewModel::setDefaultTranslationTarget,
                    )
                }
            }
        },
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
