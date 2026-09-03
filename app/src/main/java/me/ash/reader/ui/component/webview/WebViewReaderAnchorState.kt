package me.ash.reader.ui.component.webview

import android.webkit.WebView
import androidx.compose.runtime.Stable
import java.util.concurrent.atomic.AtomicLong
import me.ash.reader.ui.component.reader.ReaderEvidenceAnchorTarget
import me.ash.reader.ui.component.reader.ReaderEvidenceDocument
import me.ash.reader.ui.component.reader.ReaderEvidenceResolveStrategy
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

    internal fun bindRender(
        articleId: String?,
        originalContent: Boolean,
        evidenceDocument: ReaderEvidenceDocument,
        renderGeneration: Long,
        webView: WebView,
        highlightColorCss: String,
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
                highlightDurationMillis = highlightDurationMillis.coerceAtLeast(1L),
            )
        if (!preservePending) pending = null
    }

    internal fun markRenderReady(
        webView: WebView,
        renderGeneration: Long,
    ): Boolean {
        val current = binding ?: return false
        if (current.webView !== webView || current.renderGeneration != renderGeneration) return false
        current.ready = true
        pending?.also { pending = null }?.let { request ->
            navigateTo(request.target, request.onResult)
        }
        return true
    }

    internal fun unbind(webView: WebView? = null) {
        val current = binding
        if (webView != null && current?.webView !== webView) return
        binding = null
        pending = null
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
          node.__origreadCitationPulse = pulseId;
          let started = false;
          let observer = null;
          const pulse = function() {
            if (started || node.__origreadCitationPulse !== pulseId) return;
            started = true;
            if (observer) observer.disconnect();
            if (node.__origreadCitationAnimation) node.__origreadCitationAnimation.cancel();
            node.__origreadCitationAnimation = node.animate(
              [
                { backgroundColor: 'transparent' },
                { backgroundColor: $color, offset: 0.28 },
                { backgroundColor: 'transparent' }
              ],
              { duration: $duration, easing: 'ease-out' }
            );
          };
          const rect = node.getBoundingClientRect();
          const visible = rect.bottom > 0 && rect.top < window.innerHeight;
          if (!visible && 'IntersectionObserver' in window) {
            observer = new IntersectionObserver(function(entries) {
              if (entries.some(function(entry) { return entry.isIntersecting; })) pulse();
            }, { threshold: 0.01 });
            observer.observe(node);
          }
          node.scrollIntoView({behavior: 'smooth', block: 'center', inline: 'nearest'});
          if (visible) {
            requestAnimationFrame(pulse);
          } else {
            // Old/limited WebView fallback. pulse() is idempotent, so the observer still wins first.
            setTimeout(pulse, 900);
          }
          return '$WEBVIEW_ANCHOR_JS_LOCATED';
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
