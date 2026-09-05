package me.ash.reader.ui.component.webview

import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong
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
    var ready: Boolean = false,
)

private data class PendingWebViewReaderAnchor(
    val target: ReaderEvidenceAnchorTarget,
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
    private var markerSnapshot: ReaderEvidenceMarkerSnapshot? = null

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
    ) {
        val preservePending =
            pending != null &&
                shouldPreservePendingWebViewAnchor(
                    previousArticleId = binding?.articleId,
                    nextArticleId = articleId,
                    originalContent = originalContent,
                )
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
            )
        readyArticleId = null
        if (!preservePending) pending = null
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

    internal fun unbind(webView: WebView? = null) {
        val current = binding
        if (webView != null && current?.webView !== webView) return
        binding = null
        pending = null
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
            pending = PendingWebViewReaderAnchor(target, onResult)
            return WebViewReaderAnchorNavigationResult.Pending
        }

        val expectedGeneration = current.renderGeneration
        val expectedView = current.webView
        val stableKey = resolved.block.stableLocatorKey
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
                    latest.renderGeneration != expectedGeneration
            ) {
                return@evaluateJavascript
            }
            val located = rawResult?.trim()?.trim('"') == WEBVIEW_ANCHOR_JS_LOCATED
            onResult(
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
          const settleAndPulse = function() {
            if (document.__origreadCitationNavigation !== pulseId) return;
            const rect = node.getBoundingClientRect();
            const readableSize = Math.min(rect.height, window.innerHeight);
            const desiredTop = (window.innerHeight - readableSize) / 2;
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
            const readableSize = Math.min(rect.height, window.innerHeight);
            const maxY = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
            const targetY = Math.max(0, Math.min(maxY, window.scrollY + rect.top - (window.innerHeight - readableSize) / 2));
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
