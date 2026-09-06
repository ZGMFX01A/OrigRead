package me.ash.reader.ui.component.webview

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.ash.reader.ui.component.reader.ReaderEvidenceAnchorTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WebViewReaderScrollStateDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingUserScroll_updatesOverlayOffsetAndIsNotOverwrittenWhenReady() {
        lateinit var webView: ArticleSelectionWebView
        lateinit var state: WebViewReaderScrollState
        val loaded = AtomicBoolean(false)

        compose.setContent {
            Box(Modifier.width(384.dp).height(420.dp)) {
                AndroidView(
                    modifier = Modifier.width(384.dp).height(420.dp),
                    factory = { context ->
                        ArticleSelectionWebView(context).also { view ->
                            webView = view
                            state = WebViewReaderScrollState(initialScrollOffset = 120)
                            state.bind(view)
                            state.prepareReload()
                            view.onReaderScrollChanged = { state.update(view) }
                            view.webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loaded.set(true)
                                    }
                                }
                            view.loadDataWithBaseURL(
                                "https://example.test/",
                                longDocumentHtml(),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            loaded.get() && webView.readerContentHeight > webView.height + 800
        }

        var draggedOffset = 0
        compose.runOnIdle {
            state.update(webView)
            assertEquals(
                "temporary WebView scrollY=0 must not erase the saved overlay position",
                120,
                state.scrollOffset,
            )

            // ReaderScrollWebView reports user takeover before applying the first drag delta.
            state.beginUserScroll()
            webView.scrollTo(0, 600)
            state.update(webView)
            draggedOffset = webView.scrollY
            assertTrue("test document must be scrollable", draggedOffset > 120)
            assertEquals(
                "Compose overlays must follow the real WebView even before document-ready",
                draggedOffset,
                state.scrollOffset,
            )

            state.markDocumentReady(webView)
        }

        compose.runOnIdle {
            assertEquals(
                "late ready callback must not restore an old offset after a loading-time user drag",
                draggedOffset,
                webView.scrollY,
            )
            assertEquals(draggedOffset, state.scrollOffset)
            assertTrue(state.contentSize >= state.viewportSize)
        }
    }

    @Test
    fun firstReady_withoutUserInteractionStillRestoresSavedOffset() {
        lateinit var webView: ArticleSelectionWebView
        lateinit var state: WebViewReaderScrollState
        val loaded = AtomicBoolean(false)

        compose.setContent {
            Box(Modifier.width(384.dp).height(420.dp)) {
                AndroidView(
                    modifier = Modifier.width(384.dp).height(420.dp),
                    factory = { context ->
                        ArticleSelectionWebView(context).also { view ->
                            webView = view
                            state = WebViewReaderScrollState(initialScrollOffset = 240)
                            state.bind(view)
                            state.prepareReload()
                            view.onReaderScrollChanged = { state.update(view) }
                            view.webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loaded.set(true)
                                    }
                                }
                            view.loadDataWithBaseURL(
                                "https://example.test/",
                                longDocumentHtml(),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            loaded.get() && webView.readerContentHeight > webView.height + 800
        }

        compose.runOnIdle { state.markDocumentReady(webView) }
        compose.waitUntil(timeoutMillis = 3_000) { webView.scrollY == 240 }
        compose.runOnIdle {
            assertEquals(240, state.scrollOffset)
            assertEquals(240, webView.scrollY)
        }
    }

    @Test
    fun reload_preservesCurrentPositionUntilUserTakesOver() {
        lateinit var webView: ArticleSelectionWebView
        lateinit var state: WebViewReaderScrollState
        val loaded = AtomicBoolean(false)

        compose.setContent {
            Box(Modifier.width(384.dp).height(420.dp)) {
                AndroidView(
                    modifier = Modifier.width(384.dp).height(420.dp),
                    factory = { context ->
                        ArticleSelectionWebView(context).also { view ->
                            webView = view
                            state = WebViewReaderScrollState()
                            state.bind(view)
                            view.onReaderScrollChanged = { state.update(view) }
                            view.webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loaded.set(true)
                                    }
                                }
                            view.loadDataWithBaseURL(
                                "https://example.test/",
                                longDocumentHtml(),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            loaded.get() && webView.readerContentHeight > webView.height + 800
        }

        compose.runOnIdle {
            state.markDocumentReady(webView)
            webView.scrollTo(0, 600)
            state.update(webView)
            assertEquals(600, state.scrollOffset)

            state.prepareReload()
            // loadDataWithBaseURL/new document can transiently reset the browser to the top.
            webView.scrollTo(0, 0)
            state.update(webView)
            assertEquals(
                "reload must keep the stable reader position while restoration is pending",
                600,
                state.scrollOffset,
            )

            // A real vertical drag cancels restoration and makes the browser position authoritative.
            state.beginUserScroll()
            webView.scrollTo(0, 180)
            state.update(webView)
            state.markDocumentReady(webView)
        }

        compose.runOnIdle {
            assertEquals(180, webView.scrollY)
            assertEquals(180, state.scrollOffset)
        }
    }

    @Test
    fun pendingCitation_afterRestoreReadyStillNavigatesAndSynchronizesOverlayOffset() {
        lateinit var webView: ArticleSelectionWebView
        lateinit var state: WebViewReaderScrollState
        val anchorState = WebViewReaderAnchorState()
        val loaded = AtomicBoolean(false)
        val result = AtomicReference<WebViewReaderAnchorNavigationResult?>()
        val generation = 9L
        val prepared =
            prepareWebViewReaderContent(
                (1..45).joinToString("") { index ->
                    "<p style='height:120px;margin:0'>Citation state regression $index.</p>"
                },
                "https://example.test/article",
                true,
            )
        val block = prepared.evidenceDocument.blocks[35]
        val target =
            ReaderEvidenceAnchorTarget(
                articleId = "article-1",
                stableLocatorKey = block.stableLocatorKey,
                normalizedHash = block.normalizedSha256,
                headingPath = block.headingPath,
                quote = block.content,
            )

        compose.setContent {
            val scope = rememberCoroutineScope()
            Box(Modifier.width(384.dp).height(420.dp)) {
                AndroidView(
                    modifier = Modifier.width(384.dp).height(420.dp),
                    factory = { context ->
                        ArticleSelectionWebView(context).also { view ->
                            webView = view
                            state = WebViewReaderScrollState(initialScrollOffset = 240)
                            state.bind(view)
                            state.prepareReload()
                            view.settings.javaScriptEnabled = true
                            view.onReaderScrollChanged = { state.update(view) }
                            anchorState.bindRender(
                                articleId = "article-1",
                                originalContent = true,
                                evidenceDocument = prepared.evidenceDocument,
                                renderGeneration = generation,
                                webView = view,
                                highlightColorCss = "rgb(80, 120, 200)",
                                markerForegroundCss = "#333333",
                                markerBackgroundCss = "#DDDDDD",
                                highlightDurationMillis = 200L,
                                viewportScrollHost =
                                    WebViewReaderViewportScrollHost(
                                        coroutineScope = scope,
                                        readableTopInsetPx = { 0 },
                                    ),
                            )
                            view.webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loaded.set(true)
                                    }
                                }
                            view.loadDataWithBaseURL(
                                "https://example.test/",
                                "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0'>${prepared.html}</body></html>",
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            loaded.get() && webView.readerContentHeight > webView.height + 800
        }

        compose.runOnIdle {
            state.update(webView)
            assertEquals(240, state.scrollOffset)

            val pending = anchorState.navigateTo(target) { result.set(it) }
            assertTrue(pending is WebViewReaderAnchorNavigationResult.Pending)

            // Keep the same ordering as OrigReadWebView's visual-state callback: restore the
            // reader position first, then release pending Citation navigation for this generation.
            state.markDocumentReady(webView)
            assertTrue(anchorState.markRenderReady(webView, generation))
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            result.get() is WebViewReaderAnchorNavigationResult.Located
        }
        compose.runOnIdle {
            assertTrue("Citation should move beyond the restored reader position", webView.scrollY > 240)
            assertEquals(
                "Citation's programmatic WebView scroll must keep Compose overlays synchronized",
                webView.scrollY,
                state.scrollOffset,
            )
        }
    }

    private fun longDocumentHtml(): String =
        buildString {
            append("<html><body style='margin:0'>")
            repeat(40) { index ->
                append("<p style='height:120px;margin:0'>Reader block ")
                append(index)
                append("</p>")
            }
            append("</body></html>")
        }
}
