package me.ash.reader.ui.component.webview

import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver

/** Scroll metrics belong to the bounded WebView, not to an expanded Compose scroll container. */
@Stable
class WebViewReaderScrollState(initialScrollOffset: Int = 0) : ScrollIndicatorState {
    override var scrollOffset: Int by mutableIntStateOf(initialScrollOffset)
        private set
    override var contentSize: Int by mutableIntStateOf(0)
        private set
    override var viewportSize: Int by mutableIntStateOf(0)
        private set

    internal var view: ArticleSelectionWebView? = null
        private set
    private var documentReady = false
    private var pendingRestoreOffset: Int? = initialScrollOffset.takeIf { it > 0 }
    internal var onUserDrag: (() -> Unit)? = null

    internal fun bind(webView: ArticleSelectionWebView) {
        if (view === webView) return
        view?.let { previousView ->
            val carriedOffset =
                pendingRestoreOffset ?: previousView.scrollY.coerceAtLeast(0)
            scrollOffset = carriedOffset
            pendingRestoreOffset = carriedOffset.takeIf { it > 0 }
        }
        view = webView
        documentReady = false
    }

    internal fun update(webView: ArticleSelectionWebView) {
        if (view !== webView) return

        // While a non-zero reader position is waiting to be restored, a newly created/reloaded
        // WebView can temporarily report scrollY == 0. Do not let that transient value pull the
        // Compose title/footer overlays back to the top. Once the restore is consumed, or the user
        // takes over the scroll, the WebView becomes the live source of truth immediately.
        if (documentReady || pendingRestoreOffset == null) {
            scrollOffset = webView.scrollY.coerceAtLeast(0)
        }
        viewportSize = webView.height.coerceAtLeast(0)
        if (documentReady) {
            contentSize = webView.readerContentHeight.coerceAtLeast(viewportSize)
        }
    }

    internal fun unbind(webView: ArticleSelectionWebView) {
        if (view !== webView) return
        val carriedOffset =
            pendingRestoreOffset ?: webView.scrollY.coerceAtLeast(0)
        scrollOffset = carriedOffset
        pendingRestoreOffset = carriedOffset.takeIf { it > 0 }
        view = null
        documentReady = false
        contentSize = 0
        viewportSize = 0
    }

    internal fun prepareReload() {
        val pendingRestore = pendingRestoreOffset
        val carriedOffset =
            if (!documentReady && pendingRestore != null) {
                pendingRestore
            } else {
                view?.scrollY?.coerceAtLeast(0) ?: scrollOffset
            }
        scrollOffset = carriedOffset
        pendingRestoreOffset = carriedOffset.takeIf { it > 0 }
        documentReady = false
        contentSize = 0
    }

    internal fun markDocumentReady(webView: ArticleSelectionWebView) {
        if (view !== webView) return
        if (!documentReady) {
            documentReady = true
            val restoreOffset = pendingRestoreOffset
            pendingRestoreOffset = null
            if (restoreOffset != null) {
                webView.scrollTo(
                    0,
                    restoreOffset.coerceIn(
                        0,
                        (webView.readerContentHeight - webView.height).coerceAtLeast(0),
                    ),
                )
            }
        }
        update(webView)
    }

    internal fun beginUserScroll() {
        if (!documentReady) {
            pendingRestoreOffset = null
            view?.let(::update)
        }
        view?.stopReaderFling()
        view?.evaluateJavascript("document.__origreadCitationNavigation = null;", null)
        onUserDrag?.invoke()
    }

    fun scrollToTop() {
        beginUserScroll()
        view?.scrollReaderToTop()
    }

    companion object {
        val Saver = Saver<WebViewReaderScrollState, Int>(
            save = { it.scrollOffset },
            restore = { WebViewReaderScrollState(it) },
        )
    }
}
