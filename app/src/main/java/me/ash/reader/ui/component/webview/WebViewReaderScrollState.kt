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
    private var restoreOffset = initialScrollOffset
    internal var onUserDrag: (() -> Unit)? = null

    internal fun bind(webView: ArticleSelectionWebView) {
        if (view === webView) return
        view = webView
        documentReady = false
    }

    internal fun update(webView: ArticleSelectionWebView) {
        if (view !== webView) return
        if (!documentReady) return
        scrollOffset = webView.scrollY.coerceAtLeast(0)
        viewportSize = webView.height.coerceAtLeast(0)
        contentSize = webView.readerContentHeight.coerceAtLeast(viewportSize)
    }

    internal fun unbind(webView: ArticleSelectionWebView) {
        if (view !== webView) return
        view = null
        restoreOffset = scrollOffset
        documentReady = false
        contentSize = 0
        viewportSize = 0
    }

    internal fun prepareReload() {
        if (documentReady) restoreOffset = scrollOffset
        documentReady = false
        contentSize = 0
    }

    internal fun markDocumentReady(webView: ArticleSelectionWebView) {
        if (view !== webView) return
        if (!documentReady) {
            documentReady = true
            webView.scrollTo(0, restoreOffset.coerceIn(0, (webView.readerContentHeight - webView.height).coerceAtLeast(0)))
        }
        update(webView)
    }

    internal fun beginUserScroll() {
        view?.stopReaderFling()
        view?.evaluateJavascript("document.__origreadCitationNavigation = null;", null)
        onUserDrag?.invoke()
    }

    fun scrollToTop() {
        beginUserScroll()
        restoreOffset = 0
        view?.scrollReaderToTop()
    }

    companion object {
        val Saver = Saver<WebViewReaderScrollState, Int>(
            save = { it.scrollOffset },
            restore = { WebViewReaderScrollState(it) },
        )
    }
}
