package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Date
import me.ash.reader.R
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.net.githubReleaseDownloadCandidates
import me.ash.reader.infrastructure.preference.LocalReadingRenderer
import me.ash.reader.infrastructure.preference.LocalReadingSubheadUpperCase
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.ui.component.reader.LocalTextContentWidth
import me.ash.reader.ui.component.reader.NativeReaderAnchorMap
import me.ash.reader.ui.component.reader.NativeReaderAnchorState
import me.ash.reader.ui.component.reader.Reader
import me.ash.reader.ui.component.reader.ReaderEvidenceDocument
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerState
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerNavigationTarget
import me.ash.reader.ui.component.reader.readerEvidenceMarkerDisplayOrder
import me.ash.reader.ui.component.reader.buildReaderEvidenceDocument
import me.ash.reader.ui.component.scrollbar.drawVerticalScrollIndicator
import me.ash.reader.ui.component.webview.OrigReadWebView
import me.ash.reader.ui.component.webview.WebViewReaderAnchorState
import me.ash.reader.ui.component.webview.WebViewReaderScrollState
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.isLlmEdition
import me.ash.reader.ui.ext.roundClick
import org.jsoup.Jsoup

private data class NativeReaderParsedContent(
    val body: org.jsoup.nodes.Element,
    val evidenceDocument: ReaderEvidenceDocument,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Content(
    modifier: Modifier = Modifier,
    articleId: String? = null,
    content: String,
    feedName: String,
    title: String,
    originalTitle: String? = null,
    author: String? = null,
    link: String? = null,
    publishedDate: Date,
    scrollState: ScrollState,
    listState: LazyListState,
    isLoading: Boolean,
    failureReason: FullContentFailureReason? = null,
    contentPadding: PaddingValues = PaddingValues(),
    topBarSpacerHeight: Dp = 64.dp,
    topContentPadding: Dp = contentPadding.calculateTopPadding(),
    onReadOriginal: () -> Unit = {},
    onVerifyAndParse: (() -> Unit)? = null,
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    onSelectedTextAction: ((String) -> Unit)? = null,
    isOriginalContent: Boolean = true,
    nativeReaderAnchorState: NativeReaderAnchorState? = null,
    webViewReaderAnchorState: WebViewReaderAnchorState? = null,
    readerEvidenceMarkerState: ReaderEvidenceMarkerState? = null,
    onReaderEvidenceMarkerClick: ((ReaderEvidenceMarkerNavigationTarget) -> Unit)? = null,
    onNativeReaderUserDrag: (() -> Unit)? = null,
    onWebViewReaderUserDrag: (() -> Unit)? = null,
    webViewScrollState: WebViewReaderScrollState? = null,
) {
    val context = LocalContext.current
    val subheadUpperCase = LocalReadingSubheadUpperCase.current
    val renderer = LocalReadingRenderer.current
    val nativeReaderContent =
        remember(content, link, renderer, isLoading, failureReason) {
            if (
                renderer == ReadingRendererPreference.NativeComponent &&
                    !isLoading &&
                    failureReason == null
            ) {
                content.byteInputStream().use { inputStream ->
                    val body = Jsoup.parse(inputStream, null, link ?: "").body()
                    NativeReaderParsedContent(
                        body = body,
                        evidenceDocument = buildReaderEvidenceDocument(body),
                    )
                }
            } else {
                null
            }
        }
    val nativeAnchorMapBuilder =
        remember(nativeReaderContent?.body) { NativeReaderAnchorMap.Builder() }
    LaunchedEffect(listState, onNativeReaderUserDrag) {
        if (onNativeReaderUserDrag == null) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                onNativeReaderUserDrag()
            }
        }
    }
    LaunchedEffect(scrollState, onWebViewReaderUserDrag) {
        if (onWebViewReaderUserDrag == null) return@LaunchedEffect
        scrollState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                onWebViewReaderUserDrag()
            }
        }
    }
    val nativeAnchorTopInsetPx =
        with(LocalDensity.current) { (topBarSpacerHeight + topContentPadding).roundToPx() }

    DisposableEffect(
        nativeReaderAnchorState,
        articleId,
        isOriginalContent,
        nativeReaderContent,
        listState,
        nativeAnchorTopInsetPx,
        renderer,
    ) {
        if (
            nativeReaderAnchorState != null &&
                nativeReaderContent != null &&
                renderer == ReadingRendererPreference.NativeComponent
        ) {
            nativeReaderAnchorState.bind(
                articleId = articleId,
                originalContent = isOriginalContent,
                evidenceDocument = nativeReaderContent.evidenceDocument,
                anchorMapBuilder = nativeAnchorMapBuilder,
                listState = listState,
                topInsetPx = nativeAnchorTopInsetPx,
            )
        } else {
            nativeReaderAnchorState?.unbind()
        }
        onDispose { nativeReaderAnchorState?.unbind() }
    }

    val textContentWidth = LocalTextContentWidth.current
    val maxWidthModifier = Modifier.widthIn(max = textContentWidth)
    val uriHandler = LocalUriHandler.current
    val releaseLinks = link.toOrigReadReleaseLinks(llmEdition = isLlmEdition)
    val selectedTextActionLabel = stringResource(R.string.selected_text_ai)

    val headline =
        @Composable {
            Column(modifier = Modifier.then(maxWidthModifier).padding(horizontal = 12.dp)) {
                DisableSelection {
                    Metadata(
                        feedName = feedName,
                        title = title,
                        originalTitle = originalTitle,
                        author = author,
                        publishedDate = publishedDate,
                        modifier = Modifier.roundClick { link?.let { uriHandler.openUri(it) } },
                    )
                }
            }
        }

    if (isLoading) {
        Column { LoadingIndicator(modifier = Modifier.size(56.dp)) }
    } else if (failureReason != null) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = topContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(topBarSpacerHeight))
            headline()
            FullContentFailure(
                modifier = Modifier.then(maxWidthModifier),
                reason = failureReason,
                onReadOriginal = onReadOriginal,
                onVerifyAndParse = onVerifyAndParse,
            )
            Spacer(modifier = Modifier.height(128.dp))
        }
    } else {

        when (renderer) {
            ReadingRendererPreference.WebView -> {
                val browserScrollState = webViewScrollState ?: rememberSaveable(
                    articleId, saver = WebViewReaderScrollState.Saver,
                ) { WebViewReaderScrollState() }
                SideEffect {
                    browserScrollState.onUserDrag = {
                        webViewReaderAnchorState?.cancelNavigation()
                        onWebViewReaderUserDrag?.invoke()
                    }
                }
                val readableTopInsetPx = with(LocalDensity.current) { topBarSpacerHeight.roundToPx() }
                WebViewReadingLayout(
                    modifier =
                        modifier
                            .padding(top = topContentPadding)
                            .fillMaxSize(),
                    scrollState = browserScrollState,
                    header = {
                        Column(modifier = Modifier.then(maxWidthModifier)) {
                            Spacer(modifier = Modifier.height(topBarSpacerHeight))
                            headline()
                        }
                    },
                    footer = {
                        Column(modifier = Modifier.then(maxWidthModifier)) {
                            releaseLinks?.let {
                                OrigReadReleaseActions(
                                    links = it,
                                    onOpenUrl = uriHandler::openUri,
                                )
                            }
                            Spacer(modifier = Modifier.height(128.dp))
                            Spacer(
                                modifier = Modifier.height(contentPadding.calculateBottomPadding())
                            )
                        }
                    },
                ) { headerHeightPx, footerHeightPx ->
                    // Constrain the Android View to this viewport, even for very long documents.
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        OrigReadWebView(
                            modifier = Modifier.then(maxWidthModifier).fillMaxSize(),
                            articleId = articleId,
                            sourceUrl = link,
                            isOriginalContent = isOriginalContent,
                            content = content,
                            refererDomain = link.extractDomain(),
                            onImageClick = onImageClick,
                            selectionActionLabel = selectedTextActionLabel.takeIf { onSelectedTextAction != null },
                            onSelectedTextAction = onSelectedTextAction,
                            readerAnchorState = webViewReaderAnchorState,
                            viewportScrollState = browserScrollState,
                            headerHeightPx = headerHeightPx,
                            footerHeightPx = footerHeightPx,
                            readableTopInsetPx = readableTopInsetPx,
                            markerSnapshot = readerEvidenceMarkerState?.snapshot.takeIf { isOriginalContent },
                            onEvidenceMarkerClick = onReaderEvidenceMarkerClick,
                        )
                    }
                }
            }

            ReadingRendererPreference.NativeComponent -> {
                PrioritizedProcessTextContextMenu(
                    enabled = onSelectedTextAction != null,
                    targetLabel = selectedTextActionLabel,
                ) {
                    SelectionContainer {
                        LazyColumn(
                            modifier =
                                modifier.fillMaxSize()
                                    .drawVerticalScrollIndicator(listState)
                                    .onGloballyPositioned { coordinates ->
                                        nativeReaderAnchorState?.updateViewportCoordinates(coordinates)
                                    },
                            state = listState,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Keep the anchor-map transaction inside the LazyListScope item-provider
                            // build. Compose is allowed to skip an unchanged LazyColumn while its
                            // parent recomposes; beginning the pass outside and committing it in a
                            // parent SideEffect could therefore publish an empty map.
                            nativeAnchorMapBuilder.beginPass()
                            nativeAnchorMapBuilder.recordItem()
                            item {
                                // Top bar height
                                Spacer(modifier = Modifier.height(topBarSpacerHeight))
                                // padding
                                Spacer(modifier = Modifier.height(topContentPadding))
                                headline()
                            }

                            Reader(
                                context = context,
                                subheadUpperCase = subheadUpperCase.value,
                                link = link ?: "",
                                content = content,
                                parsedBody = nativeReaderContent?.body,
                                onImageClick = onImageClick,
                                onLinkClick = { url ->
                                    val displayOrder = readerEvidenceMarkerDisplayOrder(url)
                                    if (displayOrder != null) {
                                        readerEvidenceMarkerState?.snapshot
                                            ?.navigationTargetFor(articleId, displayOrder)
                                            ?.let { target -> onReaderEvidenceMarkerClick?.invoke(target) }
                                    } else {
                                        uriHandler.openUri(url)
                                    }
                                },
                                anchorMapBuilder = nativeAnchorMapBuilder,
                                nativeReaderAnchorState = nativeReaderAnchorState,
                                anchorHighlight = nativeReaderAnchorState?.highlight,
                                markerSnapshot =
                                    readerEvidenceMarkerState?.snapshot.takeIf { isOriginalContent },
                                markerArticleId = articleId,
                            )

                            releaseLinks?.let { links ->
                                nativeAnchorMapBuilder.recordItem()
                                item {
                                    OrigReadReleaseActions(
                                        links = links,
                                        onOpenUrl = uriHandler::openUri,
                                    )
                                }
                            }

                            nativeAnchorMapBuilder.recordItem()
                            item {
                                Spacer(modifier = Modifier.height(128.dp))
                                Spacer(
                                    modifier = Modifier.height(contentPadding.calculateBottomPadding())
                                )
                            }
                            nativeAnchorMapBuilder.commitPass()
                        }
                        SideEffect {
                            nativeReaderAnchorState?.markRenderReady()
                        }
                    }
                }
            }
        }
    }
}

/** OrigRead 内置 Release 订阅的操作区：直接下载 APK，或打开完整 GitHub Release 页面。 */
@Composable
private fun OrigReadReleaseActions(
    links: OrigReadReleaseLinks,
    onOpenUrl: (String) -> Unit,
) {
    val preferredApkDownloadUrl =
        githubReleaseDownloadCandidates(links.apkDownloadUrl).firstOrNull() ?: links.apkDownloadUrl
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onOpenUrl(preferredApkDownloadUrl) },
        ) {
            Text(stringResource(R.string.download_release_apk))
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onOpenUrl(links.releasePageUrl) },
        ) {
            Text(stringResource(R.string.open_release_page))
        }
    }
}

/** 全文解析失败时展示稳定、可操作的回退页面。 */
@Composable
private fun FullContentFailure(
    reason: FullContentFailureReason,
    onReadOriginal: () -> Unit,
    onVerifyAndParse: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.full_content_failed),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(reason.messageResource()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (reason == FullContentFailureReason.ACCESS_RESTRICTED && onVerifyAndParse != null) {
            Button(onClick = onVerifyAndParse) {
                Text(stringResource(R.string.verify_and_parse))
            }
            OutlinedButton(onClick = onReadOriginal) {
                Text(stringResource(R.string.read_original))
            }
        } else {
            Button(onClick = onReadOriginal) {
                Text(stringResource(R.string.read_original))
            }
        }
    }
}

private fun FullContentFailureReason.messageResource(): Int = when (this) {
    FullContentFailureReason.NO_CONTENT -> R.string.full_content_failure_no_content
    FullContentFailureReason.DYNAMIC_CONTENT -> R.string.full_content_failure_dynamic
    FullContentFailureReason.ACCESS_RESTRICTED -> R.string.full_content_failure_access_restricted
    FullContentFailureReason.PAGE_UNAVAILABLE -> R.string.full_content_failure_page_unavailable
    FullContentFailureReason.INVALID_URL -> R.string.full_content_failure_invalid_url
    FullContentFailureReason.NETWORK -> R.string.full_content_failure_network
    FullContentFailureReason.UNKNOWN -> R.string.full_content_failure_unknown
}
