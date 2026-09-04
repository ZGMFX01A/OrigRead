package me.ash.reader.ui.component.reader

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.lazy.LazyListState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ash.reader.ui.component.webview.WebViewReaderAnchorNavigationResult
import me.ash.reader.ui.component.webview.WebViewReaderAnchorState
import me.ash.reader.ui.component.webview.WebViewHtml
import me.ash.reader.ui.component.webview.prepareWebViewReaderContent
import me.ash.reader.ui.component.webview.webViewReaderBaseUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** R07.7: exercise Native/WebView citation anchors on a real Android runtime, not only JVM tests. */
@RunWith(AndroidJUnit4::class)
class ReaderCitationDeviceTest {
    @Test
    fun nativeReader_recoversHistoricalAnchorAfterSlightContentChange() {
        val historical =
            buildReaderEvidenceDocument(
                Jsoup.parse(
                    "<h2>Results</h2><p>Revenue increased by twenty percent in 2025.</p>"
                ).body()
            )
        val historicalBlock = historical.blocks.single { it.kind == ReaderEvidenceBlockKind.PARAGRAPH }
        val current =
            buildReaderEvidenceDocument(
                Jsoup.parse(
                    "<h2>Results</h2><p>Revenue increased by twenty percent in 2025, according to the update.</p>"
                ).body()
            )
        val currentBlock = current.blocks.single { it.kind == ReaderEvidenceBlockKind.PARAGRAPH }
        val anchorMap = NativeReaderAnchorMap.Builder().apply {
            beginPass()
            recordItem(
                listOf(
                    ReaderTextAnchorRange(
                        stableLocatorKey = currentBlock.stableLocatorKey,
                        start = 0,
                        endExclusive = currentBlock.content.length,
                    )
                )
            )
            commitPass()
        }
        val state = NativeReaderAnchorState()
        state.bind(
            articleId = "article-1",
            originalContent = true,
            evidenceDocument = current,
            anchorMapBuilder = anchorMap,
            listState = LazyListState(),
            topInsetPx = 0,
        )
        state.markRenderReady()

        val target =
            ReaderEvidenceAnchorTarget(
                articleId = "article-1",
                stableLocatorKey = historicalBlock.stableLocatorKey,
                normalizedHash = historicalBlock.normalizedSha256,
                headingPath = historicalBlock.headingPath,
                quote = "Revenue increased by twenty percent in 2025",
            )
        val resolved = current.resolveReaderEvidenceAnchor(target)

        requireNotNull(resolved)
        assertEquals(currentBlock.stableLocatorKey, resolved.block.stableLocatorKey)
        assertEquals(ReaderEvidenceResolveStrategy.UNIQUE_HEADING_AND_QUOTE, resolved.strategy)
        assertEquals(
            0,
            anchorMap.snapshot().placements(resolved.block.stableLocatorKey).single().itemIndex,
        )
        assertEquals("article-1", state.readyArticleId)
    }

    @Test
    fun webViewReader_pendingCitationLocatesAfterRealPageBecomesReady() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prepared =
            prepareWebViewReaderContent(
                content = "<h2>Section</h2><p>Device WebView citation evidence.</p>",
                sourceUrl = "https://example.com/article",
                originalContent = true,
            )
        val block = prepared.evidenceDocument.blocks.single { it.kind == ReaderEvidenceBlockKind.PARAGRAPH }
        val state = WebViewReaderAnchorState()
        val webView =
            withContext(Dispatchers.Main) {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                }
            }
        val pageFinished = CompletableDeferred<Unit>()
        val navigationResult = CompletableDeferred<WebViewReaderAnchorNavigationResult>()
        val generation = 7L

        try {
            val initial =
                withContext(Dispatchers.Main) {
                    state.bindRender(
                        articleId = "article-1",
                        originalContent = true,
                        evidenceDocument = prepared.evidenceDocument,
                        renderGeneration = generation,
                        webView = webView,
                        highlightColorCss = "rgba(255, 235, 59, 0.45)",
                        markerColorCss = "#666666",
                        highlightDurationMillis = 500,
                    )
                    val pending =
                        state.navigateTo(
                            target =
                                ReaderEvidenceAnchorTarget(
                                    articleId = "article-1",
                                    stableLocatorKey = block.stableLocatorKey,
                                    normalizedHash = block.normalizedSha256,
                                    headingPath = block.headingPath,
                                    quote = block.content,
                                ),
                            onResult = { result -> navigationResult.complete(result) },
                        )
                    webView.webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageFinished.complete(Unit)
                            }
                        }
                    webView.loadDataWithBaseURL(
                        webViewReaderBaseUrl("https://example.com/article", generation),
                        WebViewHtml.HTML.format(
                            "",
                            "https://example.com/article",
                            prepared.html,
                            "",
                        ),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                    pending
                }

            assertEquals(WebViewReaderAnchorNavigationResult.Pending, initial)
            withTimeout(10_000) { pageFinished.await() }
            val renderedStableKey =
                withTimeout(10_000) {
                    webView.evaluateForTest(
                        "document.querySelector('[data-origread-block-id=\"${block.stableLocatorKey}\"]')?.getAttribute('data-origread-block-id') || ''"
                    )
                }
            assertEquals(block.stableLocatorKey, renderedStableKey.trim('"'))
            withContext(Dispatchers.Main) {
                assertTrue(state.markRenderReady(webView, generation))
            }
            val located = withTimeout(10_000) { navigationResult.await() }

            assertTrue("Expected WebView citation to locate, got $located", located is WebViewReaderAnchorNavigationResult.Located)
            assertEquals(block.stableLocatorKey, (located as WebViewReaderAnchorNavigationResult.Located).stableLocatorKey)
            assertEquals("article-1", state.readyArticleId)
        } finally {
            withContext(Dispatchers.Main) {
                state.unbind(webView)
                webView.destroy()
            }
        }
    }
}

private suspend fun WebView.evaluateForTest(script: String): String =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result.orEmpty())
            }
        }
    }
