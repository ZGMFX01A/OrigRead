package me.ash.reader.ui.component.webview

import android.webkit.WebView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.ash.reader.ui.component.reader.ReaderEvidenceAnchorTarget
import me.ash.reader.ui.component.reader.CitationMotion
import me.ash.reader.ui.component.reader.ReaderEvidenceDocument
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerSnapshot
import me.ash.reader.ui.component.reader.READER_EVIDENCE_MARKER_SELECTION_SENTINEL
import me.ash.reader.ui.component.reader.ReaderEvidenceResolveStrategy
import me.ash.reader.ui.component.reader.READER_EVIDENCE_MARKER_URL_PREFIX
import me.ash.reader.ui.component.reader.buildReaderEvidenceDocument
import me.ash.reader.ui.component.reader.resolveReaderEvidenceAnchor
import org.jsoup.Jsoup

enum class WebViewReaderAnchorUnavailableReason {
    NOT_BOUND,
    NOT_ORIGINAL_CONTENT,
    ARTICLE_MISMATCH,
    ANCHOR_NOT_FOUND,
    RENDER_NOT_READY,
    DOM_ANCHOR_NOT_FOUND,
}

sealed interface WebViewReaderAnchorNavigationResult {
    data class Located(
        val stableLocatorKey: String,
        val strategy: ReaderEvidenceResolveStrategy,
    ) : WebViewReaderAnchorNavigationResult

    data object Pending : WebViewReaderAnchorNavigationResult

    /** 当前导航被用户/新请求/renderer generation 抢占；这是取消，不是“找不到 Citation”。 */
    data object Cancelled : WebViewReaderAnchorNavigationResult

    data class Unavailable(
        val reason: WebViewReaderAnchorUnavailableReason,
    ) : WebViewReaderAnchorNavigationResult
}

private data class WebViewReaderAnchorBinding(
    val articleId: String?,
    val originalContent: Boolean,
    val evidenceDocument: ReaderEvidenceDocument,
    val renderGeneration: Long,
    val webView: WebView,
    val highlightColorCss: String,
    val markerForegroundCss: String,
    val markerBackgroundCss: String,
    val highlightDurationMillis: Long,
    val outerScrollHost: WebViewReaderOuterScrollHost?,
    val viewportScrollHost: WebViewReaderViewportScrollHost?,
    var ready: Boolean = false,
)

internal data class WebViewReaderOuterScrollHost(
    val scrollState: ScrollState,
    val webViewTopInScrollContentPx: () -> Int,
    val coroutineScope: CoroutineScope,
)

internal data class WebViewReaderViewportScrollHost(
    val coroutineScope: CoroutineScope,
    val readableTopInsetPx: () -> Int,
)

internal data class WebViewReaderAnchorGeometry(
    val documentTopPx: Float,
    val heightPx: Float,
)

private data class PendingWebViewReaderAnchor(
    val target: ReaderEvidenceAnchorTarget,
    val onResult: (WebViewReaderAnchorNavigationResult) -> Unit,
)

private data class ActiveWebViewReaderAnchor(
    val revision: Long,
    val onResult: (WebViewReaderAnchorNavigationResult) -> Unit,
)

/**
 * WebView counterpart of NativeReaderAnchorState. The app resolves historical evidence first;
 * JavaScript receives only one already-disambiguated stable key and never gets a JS -> Native
 * interface for citation navigation.
 */
@Stable
class WebViewReaderAnchorState {
    private var binding: WebViewReaderAnchorBinding? = null
    private var pending: PendingWebViewReaderAnchor? = null
    private var activeNavigation: ActiveWebViewReaderAnchor? = null
    private var markerSnapshot: ReaderEvidenceMarkerSnapshot? = null
    private var outerScrollJob: Job? = null
    private var navigationRevision: Long = 0L

    var readyArticleId: String? by mutableStateOf(null)
        private set
    var readyRevision: Long by mutableStateOf(0L)
        private set

    internal fun bindRender(
        articleId: String?,
        originalContent: Boolean,
        evidenceDocument: ReaderEvidenceDocument,
        renderGeneration: Long,
        webView: WebView,
        highlightColorCss: String,
        markerForegroundCss: String,
        markerBackgroundCss: String,
        highlightDurationMillis: Long,
        outerScrollHost: WebViewReaderOuterScrollHost? = null,
        viewportScrollHost: WebViewReaderViewportScrollHost? = null,
    ) {
        // A WebView reload can replace the document while a Compose-hosted citation animation is
        // still running. Cancel it immediately so stale coordinates cannot keep scrolling the next
        // render/article after the generation changes.
        val preservePending =
            pending != null &&
                shouldPreservePendingWebViewAnchor(
                    previousArticleId = binding?.articleId,
                    nextArticleId = articleId,
                    originalContent = originalContent,
                )
        navigationRevision += 1
        cancelActiveNavigation()
        binding =
            WebViewReaderAnchorBinding(
                articleId = articleId,
                originalContent = originalContent,
                evidenceDocument = evidenceDocument,
                renderGeneration = renderGeneration,
                webView = webView,
                highlightColorCss = highlightColorCss,
                markerForegroundCss = markerForegroundCss,
                markerBackgroundCss = markerBackgroundCss,
                highlightDurationMillis = highlightDurationMillis.coerceAtLeast(1L),
                outerScrollHost = outerScrollHost,
                viewportScrollHost = viewportScrollHost,
            )
        readyArticleId = null
        if (!preservePending) cancelPendingNavigation()
    }

    internal fun markRenderReady(
        webView: WebView,
        renderGeneration: Long,
    ): Boolean {
        val current = binding ?: return false
        if (current.webView !== webView || current.renderGeneration != renderGeneration) return false
        if (current.ready) return true
        current.ready = true
        readyArticleId = current.articleId?.trim()?.ifBlank { null }
        readyRevision += 1
        applyMarkers(current)
        pending?.also { pending = null }?.let { request ->
            navigateTo(request.target, request.onResult)
        }
        return true
    }

    internal fun setMarkerSnapshot(snapshot: ReaderEvidenceMarkerSnapshot?) {
        markerSnapshot = snapshot
        binding?.takeIf { it.ready }?.let(::applyMarkers)
    }

    internal fun cancelNavigation() {
        navigationRevision += 1
        cancelActiveNavigation()
        cancelPendingNavigation()
        binding?.webView?.evaluateJavascript("document.__origreadCitationNavigation = null;", null)
    }

    internal fun unbind(webView: WebView? = null) {
        val current = binding
        if (webView != null && current?.webView !== webView) return
        navigationRevision += 1
        cancelActiveNavigation()
        cancelPendingNavigation()
        binding = null
        markerSnapshot = null
        readyArticleId = null
        readyRevision += 1
    }

    fun navigateTo(
        target: ReaderEvidenceAnchorTarget,
        onResult: (WebViewReaderAnchorNavigationResult) -> Unit = {},
    ): WebViewReaderAnchorNavigationResult {
        val current = binding
            ?: return unavailable(WebViewReaderAnchorUnavailableReason.NOT_BOUND, onResult)
        if (!current.originalContent) {
            return unavailable(WebViewReaderAnchorUnavailableReason.NOT_ORIGINAL_CONTENT, onResult)
        }
        val targetArticleId = target.articleId?.trim()?.ifBlank { null }
        val currentArticleId = current.articleId?.trim()?.ifBlank { null }
        if (targetArticleId != null && targetArticleId != currentArticleId) {
            return unavailable(WebViewReaderAnchorUnavailableReason.ARTICLE_MISMATCH, onResult)
        }
        val resolved = current.evidenceDocument.resolveReaderEvidenceAnchor(target)
            ?: return unavailable(WebViewReaderAnchorUnavailableReason.ANCHOR_NOT_FOUND, onResult)
        if (!current.ready) {
            cancelPendingNavigation()
            pending = PendingWebViewReaderAnchor(target, onResult)
            return WebViewReaderAnchorNavigationResult.Pending
        }

        val expectedGeneration = current.renderGeneration
        val expectedView = current.webView
        val stableKey = resolved.block.stableLocatorKey
        val outerScrollHost = current.outerScrollHost
        val viewportScrollHost = current.viewportScrollHost
        val viewportView = expectedView as? ReaderScrollWebView
        val expectedNavigationRevision = beginActiveNavigation(onResult)
        if (outerScrollHost != null || (viewportScrollHost != null && viewportView != null)) {
            val scope = outerScrollHost?.coroutineScope ?: viewportScrollHost!!.coroutineScope
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    viewportView?.stopReaderFling()
                    fun navigationStillCurrent(): Boolean {
                        val latest = binding
                        return latest != null &&
                            latest.webView === expectedView &&
                            latest.renderGeneration == expectedGeneration &&
                            navigationRevision == expectedNavigationRevision &&
                            activeNavigation?.revision == expectedNavigationRevision
                    }

                    fun targetFor(geometry: WebViewReaderAnchorGeometry): Int {
                        if (outerScrollHost != null) {
                            val scrollState = outerScrollHost.scrollState
                            return webViewOuterScrollTarget(
                                webViewTopInScrollContentPx = outerScrollHost.webViewTopInScrollContentPx(),
                                nodeDocumentTopPx = geometry.documentTopPx,
                                nodeHeightPx = geometry.heightPx,
                                viewportSizePx = scrollState.viewportSize,
                                maxScrollPx = scrollState.maxValue,
                            )
                        }
                        val view = viewportView!!
                        val inset = viewportScrollHost!!.readableTopInsetPx()
                            .coerceIn(0, (view.height - 1).coerceAtLeast(0))
                        // DOM coordinates include the HTML header spacer; only the overlay's
                        // obscured area must be subtracted from the browser's readable viewport.
                        return webViewOuterScrollTarget(
                            webViewTopInScrollContentPx = -inset,
                            nodeDocumentTopPx = geometry.documentTopPx,
                            nodeHeightPx = geometry.heightPx,
                            viewportSizePx = view.height - inset,
                            maxScrollPx = (view.readerContentHeight - view.height).coerceAtLeast(0),
                        )
                    }

                    suspend fun scrollToTarget(target: Int, durationMillis: Int) {
                        val animation = tween<Float>(durationMillis, easing = FastOutSlowInEasing)
                        if (outerScrollHost != null) {
                            outerScrollHost.scrollState.animateScrollTo(target, animation)
                        } else {
                            val view = viewportView!!
                            animate(view.scrollY.toFloat(), target.toFloat(), animationSpec = animation) { value, _ ->
                                view.scrollTo(0, value.roundToInt())
                            }
                        }
                    }

                    val geometry =
                        parseWebViewReaderAnchorGeometry(
                            expectedView.awaitJavascriptResult(
                                buildWebViewReaderAnchorGeometryScript(stableKey)
                            )
                        )
                    if (!navigationStillCurrent()) return@launch
                    if (geometry == null) {
                        completeActiveNavigation(
                            expectedNavigationRevision,
                            WebViewReaderAnchorNavigationResult.Unavailable(
                                WebViewReaderAnchorUnavailableReason.DOM_ANCHOR_NOT_FOUND
                            )
                        )
                        return@launch
                    }

                    scrollToTarget(targetFor(geometry), CitationMotion.ScrollMillis)
                    if (!navigationStillCurrent()) return@launch

                    // Images/fonts can reflow the document while the scroll host is
                    // animating. Re-measure the same frozen DOM anchor once after the main scroll;
                    // if its target moved, make one short correction before highlighting. Keep this
                    // bounded to one settle pass instead of installing a long-lived DOM observer.
                    val settledGeometry =
                        parseWebViewReaderAnchorGeometry(
                            expectedView.awaitJavascriptResult(
                                buildWebViewReaderAnchorGeometryScript(stableKey)
                            )
                        )
                    if (!navigationStillCurrent()) return@launch
                    if (settledGeometry == null) {
                        completeActiveNavigation(
                            expectedNavigationRevision,
                            WebViewReaderAnchorNavigationResult.Unavailable(
                                WebViewReaderAnchorUnavailableReason.DOM_ANCHOR_NOT_FOUND
                            )
                        )
                        return@launch
                    }
                    val settledTarget = targetFor(settledGeometry)
                    val currentScroll = outerScrollHost?.scrollState?.value ?: viewportView!!.scrollY
                    if (webViewOuterScrollNeedsSettle(currentScroll, settledTarget)) {
                        scrollToTarget(settledTarget, CitationMotion.SettleScrollMillis)
                    }
                    if (!navigationStillCurrent()) return@launch

                    val highlightResult =
                        expectedView.awaitJavascriptResult(
                            buildWebViewReaderHighlightScript(
                                stableLocatorKey = stableKey,
                                highlightColorCss = current.highlightColorCss,
                                highlightDurationMillis = current.highlightDurationMillis,
                            )
                        )
                    if (!navigationStillCurrent()) return@launch
                    val located =
                        highlightResult?.trim()?.trim('"') == WEBVIEW_ANCHOR_JS_LOCATED
                    completeActiveNavigation(
                        expectedNavigationRevision,
                        if (located) {
                            WebViewReaderAnchorNavigationResult.Located(
                                stableKey,
                                resolved.strategy,
                            )
                        } else {
                            WebViewReaderAnchorNavigationResult.Unavailable(
                                WebViewReaderAnchorUnavailableReason.DOM_ANCHOR_NOT_FOUND
                            )
                        }
                    )
                }
            outerScrollJob = job
            job.invokeOnCompletion { cause ->
                if (outerScrollJob === job) outerScrollJob = null
                if (cause != null) {
                    completeActiveNavigation(
                        expectedNavigationRevision,
                        if (cause is CancellationException) {
                            WebViewReaderAnchorNavigationResult.Cancelled
                        } else {
                            WebViewReaderAnchorNavigationResult.Unavailable(
                                WebViewReaderAnchorUnavailableReason.RENDER_NOT_READY
                            )
                        },
                    )
                }
            }
            job.start()
            return WebViewReaderAnchorNavigationResult.Pending
        }

        val script =
            buildWebViewReaderAnchorScript(
                stableLocatorKey = stableKey,
                highlightColorCss = current.highlightColorCss,
                highlightDurationMillis = current.highlightDurationMillis,
            )
        expectedView.evaluateJavascript(script) { rawResult ->
            val latest = binding
            if (
                latest == null ||
                    latest.webView !== expectedView ||
                    latest.renderGeneration != expectedGeneration ||
                    navigationRevision != expectedNavigationRevision ||
                    activeNavigation?.revision != expectedNavigationRevision
            ) {
                return@evaluateJavascript
            }
            val located = rawResult?.trim()?.trim('"') == WEBVIEW_ANCHOR_JS_LOCATED
            completeActiveNavigation(
                expectedNavigationRevision,
                if (located) {
                    WebViewReaderAnchorNavigationResult.Located(stableKey, resolved.strategy)
                } else {
                    WebViewReaderAnchorNavigationResult.Unavailable(
                        WebViewReaderAnchorUnavailableReason.DOM_ANCHOR_NOT_FOUND
                    )
                }
            )
        }
        return WebViewReaderAnchorNavigationResult.Pending
    }

    /**
     * Transaction adapter：把 callback/Pending API 收口成可取消的 suspend 调用。
     * 外层 request job 取消时不再接受迟到结果，WebView generation guard 仍负责拒绝 stale DOM。
     */
    suspend fun navigateToAwait(
        target: ReaderEvidenceAnchorTarget,
    ): WebViewReaderAnchorNavigationResult =
        suspendCancellableCoroutine { continuation ->
            val immediate = navigateTo(target) { result ->
                if (result != WebViewReaderAnchorNavigationResult.Pending && continuation.isActive) {
                    continuation.resume(result)
                }
            }
            if (immediate !is WebViewReaderAnchorNavigationResult.Pending && continuation.isActive) {
                continuation.resume(immediate)
            }
            continuation.invokeOnCancellation {
                navigationRevision += 1
                cancelActiveNavigation()
                cancelPendingNavigation()
            }
        }

    /** Start one request-scoped navigation. Replacing an older request completes it as Cancelled. */
    private fun beginActiveNavigation(
        onResult: (WebViewReaderAnchorNavigationResult) -> Unit,
    ): Long {
        cancelActiveNavigation()
        navigationRevision += 1
        val revision = navigationRevision
        activeNavigation = ActiveWebViewReaderAnchor(revision, onResult)
        return revision
    }

    private fun completeActiveNavigation(
        revision: Long,
        result: WebViewReaderAnchorNavigationResult,
    ) {
        val active = activeNavigation?.takeIf { it.revision == revision } ?: return
        activeNavigation = null
        active.onResult(result)
    }

    private fun cancelActiveNavigation() {
        val active = activeNavigation
        activeNavigation = null
        val job = outerScrollJob
        outerScrollJob = null
        job?.cancel()
        active?.onResult(WebViewReaderAnchorNavigationResult.Cancelled)
    }

    private fun cancelPendingNavigation() {
        val request = pending
        pending = null
        request?.onResult(WebViewReaderAnchorNavigationResult.Cancelled)
    }

    private fun unavailable(
        reason: WebViewReaderAnchorUnavailableReason,
        onResult: (WebViewReaderAnchorNavigationResult) -> Unit,
    ): WebViewReaderAnchorNavigationResult.Unavailable =
        WebViewReaderAnchorNavigationResult.Unavailable(reason).also(onResult)

    private fun applyMarkers(current: WebViewReaderAnchorBinding) {
        val expectedGeneration = current.renderGeneration
        val expectedView = current.webView
        val script =
            buildWebViewReaderMarkerScript(
                snapshot = markerSnapshot,
                currentArticleId = current.articleId,
                markerForegroundCss = current.markerForegroundCss,
                markerBackgroundCss = current.markerBackgroundCss,
            )
        expectedView.evaluateJavascript(script) {
            val latest = binding
            if (
                latest == null ||
                    latest.webView !== expectedView ||
                    latest.renderGeneration != expectedGeneration
            ) {
                return@evaluateJavascript
            }
        }
    }
}

private suspend fun WebView.awaitJavascriptResult(script: String): String? =
    suspendCancellableCoroutine { continuation ->
        try {
            evaluateJavascript(script) { rawResult ->
                if (continuation.isActive) continuation.resume(rawResult)
            }
        } catch (_: RuntimeException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

internal data class WebViewPreparedReaderContent(
    val html: String,
    val evidenceDocument: ReaderEvidenceDocument,
)

internal fun prepareWebViewReaderContent(
    content: String,
    sourceUrl: String?,
    originalContent: Boolean,
): WebViewPreparedReaderContent {
    if (!originalContent) {
        return WebViewPreparedReaderContent(content, ReaderEvidenceDocument(emptyList()))
    }
    val body = Jsoup.parse(content, sourceUrl.orEmpty()).body()
    val evidenceDocument = buildReaderEvidenceDocument(body)
    return WebViewPreparedReaderContent(body.html(), evidenceDocument)
}

internal fun webViewReaderBaseUrl(sourceUrl: String?, renderGeneration: Long): String {
    val source = sourceUrl?.trim()?.takeIf(String::isNotBlank)
    if (source != null && (source.startsWith("https://") || source.startsWith("http://"))) {
        return "${source.substringBefore('#')}#$WEBVIEW_RENDER_FRAGMENT_PREFIX$renderGeneration"
    }
    return "$WEBVIEW_FALLBACK_BASE_URL#$WEBVIEW_RENDER_FRAGMENT_PREFIX$renderGeneration"
}

internal fun webViewReaderGenerationFromUrl(url: String?): Long? {
    val fragment = url?.substringAfterLast('#', missingDelimiterValue = "") ?: return null
    if (!fragment.startsWith(WEBVIEW_RENDER_FRAGMENT_PREFIX)) return null
    return fragment.removePrefix(WEBVIEW_RENDER_FRAGMENT_PREFIX).toLongOrNull()
}

internal fun WebViewRenderGuard.acceptedReaderGeneration(url: String?): Long? =
    webViewReaderGenerationFromUrl(url)?.takeIf(::isCurrentGeneration)

internal fun shouldPreservePendingWebViewAnchor(
    previousArticleId: String?,
    nextArticleId: String?,
    originalContent: Boolean,
): Boolean {
    if (!originalContent) return false
    val previous = previousArticleId?.trim()?.ifBlank { null } ?: return false
    val next = nextArticleId?.trim()?.ifBlank { null } ?: return false
    return previous == next
}

internal fun buildWebViewReaderAnchorGeometryScript(stableLocatorKey: String): String {
    val key = stableLocatorKey.toJavaScriptStringLiteral()
    return """
        (function() {
          const key = $key;
          const node = document.querySelector('[data-origread-block-id="' + CSS.escape(key) + '"]');
          if (!node) return '$WEBVIEW_ANCHOR_JS_MISSING';
          const rect = node.getBoundingClientRect();
          const scale = Number.isFinite(window.devicePixelRatio) && window.devicePixelRatio > 0
            ? window.devicePixelRatio
            : 1;
          const documentTopPx = (window.scrollY + rect.top) * scale;
          const heightPx = rect.height * scale;
          return '$WEBVIEW_ANCHOR_JS_GEOMETRY_PREFIX' + documentTopPx + '|' + heightPx;
        })()
    """.trimIndent()
}

internal fun parseWebViewReaderAnchorGeometry(rawResult: String?): WebViewReaderAnchorGeometry? {
    val decoded = rawResult?.trim()?.trim('"') ?: return null
    if (!decoded.startsWith(WEBVIEW_ANCHOR_JS_GEOMETRY_PREFIX)) return null
    val values = decoded.removePrefix(WEBVIEW_ANCHOR_JS_GEOMETRY_PREFIX).split('|')
    if (values.size != 2) return null
    val top = values[0].toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    val height = values[1].toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    if (top < 0f || height < 0f) return null
    return WebViewReaderAnchorGeometry(documentTopPx = top, heightPx = height)
}

internal fun webViewOuterScrollTarget(
    webViewTopInScrollContentPx: Int,
    nodeDocumentTopPx: Float,
    nodeHeightPx: Float,
    viewportSizePx: Int,
    maxScrollPx: Int,
): Int {
    val viewport = viewportSizePx.coerceAtLeast(0)
    val readableNodeHeight = nodeHeightPx.coerceIn(0f, viewport.toFloat())
    val desiredTop = (viewport - readableNodeHeight) / 2f
    return (webViewTopInScrollContentPx + nodeDocumentTopPx - desiredTop)
        .toInt()
        .coerceIn(0, maxScrollPx.coerceAtLeast(0))
}

/**
 * `positionInParent()` for a child inside `verticalScroll` moves as the viewport scrolls.
 * Add the host ScrollState value so Citation calculations keep one stable content-space origin.
 */
internal fun webViewScrollContentCoordinate(
    positionInParentPx: Int,
    outerScrollPx: Int,
): Int = positionInParentPx + outerScrollPx

internal fun webViewOuterScrollNeedsSettle(
    currentScrollPx: Int,
    targetScrollPx: Int,
): Boolean = kotlin.math.abs(targetScrollPx - currentScrollPx) > 2

internal fun buildWebViewReaderHighlightScript(
    stableLocatorKey: String,
    highlightColorCss: String,
    highlightDurationMillis: Long,
): String {
    val key = stableLocatorKey.toJavaScriptStringLiteral()
    val color = highlightColorCss.toJavaScriptStringLiteral()
    val duration = highlightDurationMillis.coerceAtLeast(1L)
    val pulseId = WEBVIEW_HIGHLIGHT_SEQUENCE.incrementAndGet()
    return """
        (function() {
          const key = $key;
          const node = document.querySelector('[data-origread-block-id="' + CSS.escape(key) + '"]');
          if (!node) return '$WEBVIEW_ANCHOR_JS_MISSING';
          const pulseId = $pulseId;
          document.__origreadCitationNavigation = pulseId;
          if (document.__origreadCitationAnimation) document.__origreadCitationAnimation.cancel();
          requestAnimationFrame(function() {
            if (document.__origreadCitationNavigation !== pulseId) return;
            document.__origreadCitationAnimation = node.animate(
              [
                { backgroundColor: 'transparent' },
                { backgroundColor: $color, offset: ${CitationMotion.FadeInMillis.toDouble() / CitationMotion.HighlightMillis} },
                { backgroundColor: $color, offset: ${(CitationMotion.FadeInMillis + CitationMotion.HoldMillis).toDouble() / CitationMotion.HighlightMillis} },
                { backgroundColor: 'transparent' }
              ],
              { duration: $duration, easing: 'linear' }
            );
          });
          return '$WEBVIEW_ANCHOR_JS_LOCATED';
        })()
    """.trimIndent()
}

internal fun buildWebViewReaderAnchorScript(
    stableLocatorKey: String,
    highlightColorCss: String,
    highlightDurationMillis: Long,
): String {
    val key = stableLocatorKey.toJavaScriptStringLiteral()
    val color = highlightColorCss.toJavaScriptStringLiteral()
    val duration = highlightDurationMillis.coerceAtLeast(1L)
    val pulseId = WEBVIEW_HIGHLIGHT_SEQUENCE.incrementAndGet()
    return """
        (function() {
          const key = $key;
          const node = document.querySelector('[data-origread-block-id="' + CSS.escape(key) + '"]');
          if (!node) return '$WEBVIEW_ANCHOR_JS_MISSING';
          const pulseId = $pulseId;
          document.__origreadCitationNavigation = pulseId;
          if (document.__origreadCitationAnimation) document.__origreadCitationAnimation.cancel();
          const pulse = function() {
            if (document.__origreadCitationNavigation !== pulseId) return;
            document.__origreadCitationAnimation = node.animate(
              [
                { backgroundColor: 'transparent' },
                { backgroundColor: $color, offset: ${CitationMotion.FadeInMillis.toDouble() / CitationMotion.HighlightMillis} },
                { backgroundColor: $color, offset: ${(CitationMotion.FadeInMillis + CitationMotion.HoldMillis).toDouble() / CitationMotion.HighlightMillis} },
                { backgroundColor: 'transparent' }
              ],
              { duration: $duration, easing: 'linear' }
            );
          };
          const startY = window.scrollY;
          const startedAt = performance.now();
          const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
          const scrollDuration = reducedMotion ? 0 : ${CitationMotion.ScrollMillis};
          // The summary/top bar overlays the bounded browser. Center references in the remaining
          // readable viewport rather than behind that overlay. Native metadata is already in the
          // document's header spacer, so getBoundingClientRect includes its height automatically.
          const desiredNodeTop = function(rect) {
            const inset = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--origread-readable-top')) || 0;
            const top = Math.max(0, Math.min(window.innerHeight - 1, inset));
            const available = window.innerHeight - top;
            return top + (available - Math.min(rect.height, available)) / 2;
          };
          const settleAndPulse = function() {
            if (document.__origreadCitationNavigation !== pulseId) return;
            const rect = node.getBoundingClientRect();
            const desiredTop = desiredNodeTop(rect);
            const correction = rect.top - desiredTop;
            if (Math.abs(correction) > 2) {
              const maxY = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
              window.scrollTo({top: Math.max(0, Math.min(maxY, window.scrollY + correction)), behavior: 'instant'});
            }
            requestAnimationFrame(pulse);
          };
          const scrollFrame = function(now) {
            if (document.__origreadCitationNavigation !== pulseId) return;
            const rect = node.getBoundingClientRect();
            const maxY = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
            const targetY = Math.max(0, Math.min(maxY, window.scrollY + rect.top - desiredNodeTop(rect)));
            const progress = scrollDuration === 0 ? 1 : Math.min(1, (now - startedAt) / scrollDuration);
            const eased = progress * progress * (3 - 2 * progress);
            window.scrollTo({top: startY + (targetY - startY) * eased, behavior: 'instant'});
            if (progress >= 1) {
              requestAnimationFrame(settleAndPulse);
              return;
            }
            requestAnimationFrame(scrollFrame);
          };
          requestAnimationFrame(scrollFrame);
          return '$WEBVIEW_ANCHOR_JS_LOCATED';
        })()
    """.trimIndent()
}

internal fun buildWebViewReaderMarkerScript(
    snapshot: ReaderEvidenceMarkerSnapshot?,
    currentArticleId: String?,
    markerForegroundCss: String,
    markerBackgroundCss: String,
): String {
    val currentSnapshot = snapshot
    val markerEntries =
        currentSnapshot
            ?.markers
            .orEmpty()
            .asSequence()
            .map { it.stableLocatorKey }
            .distinct()
            .mapNotNull { stableKey ->
                val orders = currentSnapshot?.displayOrdersFor(currentArticleId, stableKey).orEmpty()
                orders.takeIf(List<Int>::isNotEmpty)?.let { stableKey to it }
            }
            .sortedBy { it.first }
            .toList()
    val entriesLiteral =
        markerEntries.joinToString(prefix = "[", postfix = "]") { (stableKey, orders) ->
            val orderLiteral = orders.joinToString(prefix = "[", postfix = "]")
            "{key:${stableKey.toJavaScriptStringLiteral()},orders:$orderLiteral}"
        }
    val foreground = markerForegroundCss.toJavaScriptStringLiteral()
    val background = markerBackgroundCss.toJavaScriptStringLiteral()
    val selectionSentinel = READER_EVIDENCE_MARKER_SELECTION_SENTINEL.toString().toJavaScriptStringLiteral()
    return """
        (function() {
          document.querySelectorAll('[data-origread-citation-marker="true"]').forEach(function(marker) {
            marker.remove();
          });
          const entries = $entriesLiteral;
          entries.forEach(function(entry) {
            const node = document.querySelector('[data-origread-block-id="' + CSS.escape(entry.key) + '"]');
            if (!node) return;
            entry.orders.forEach(function(order) {
              const marker = document.createElement('a');
              marker.setAttribute('data-origread-citation-marker', 'true');
              marker.href = '${READER_EVIDENCE_MARKER_URL_PREFIX}' + order;
              marker.textContent = $selectionSentinel + '[' + order + ']' + $selectionSentinel;
              marker.style.color = $foreground;
              marker.style.backgroundColor = $background;
              marker.style.fontSize = '0.72em';
              marker.style.fontFamily = 'system-ui, sans-serif';
              marker.style.fontWeight = '600';
              marker.style.lineHeight = '1.2';
              marker.style.verticalAlign = '0.12em';
              marker.style.borderRadius = '0.34em';
              marker.style.padding = '0.04em 0.28em';
              marker.style.marginLeft = '0.18em';
              marker.style.whiteSpace = 'nowrap';
              marker.style.textDecoration = 'none';
              node.appendChild(marker);
            });
          });
          return entries.length;
        })()
    """.trimIndent()
}

private const val WEBVIEW_RENDER_FRAGMENT_PREFIX = "origread-render-"
private const val WEBVIEW_FALLBACK_BASE_URL = "https://origread.invalid/reader"
private const val WEBVIEW_ANCHOR_JS_LOCATED = "origread-located"
private const val WEBVIEW_ANCHOR_JS_MISSING = "origread-missing"
private const val WEBVIEW_ANCHOR_JS_GEOMETRY_PREFIX = "origread-geometry:"
private val WEBVIEW_HIGHLIGHT_SEQUENCE = AtomicLong(0L)

private fun String.toJavaScriptStringLiteral(): String =
    buildString(length + 2) {
        append('"')
        this@toJavaScriptStringLiteral.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(character)
            }
        }
        append('"')
    }
