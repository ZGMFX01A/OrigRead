package me.ash.reader.ui.component.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import me.ash.reader.ui.component.webview.WebViewReaderAnchorNavigationResult
import me.ash.reader.ui.component.webview.WebViewReaderAnchorState
import me.ash.reader.ui.component.webview.WebViewReaderOuterScrollHost
import me.ash.reader.ui.component.webview.buildWebViewReaderAnchorScript
import me.ash.reader.ui.component.webview.prepareWebViewReaderContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CitationMotionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nativeReader_scrollCentersVariableHeightItemsInBothDirections() {
        lateinit var list: LazyListState
        var target by mutableStateOf(-1)
        var completed = -1
        compose.setContent {
            MaterialTheme {
                // Start around one third into a long article. The first target is deliberately
                // near the end, far outside the initial measured LazyColumn window.
                list = rememberLazyListState(initialFirstVisibleItemIndex = 90)
                Box(Modifier.width(384.dp).height(420.dp)) {
                    LazyColumn(state = list) {
                        items(300) { index ->
                            Text("Evidence paragraph $index", Modifier.height((40 + index % 4 * 35).dp))
                        }
                    }
                }
                LaunchedEffect(target) {
                    if (target >= 0) {
                        list.animateCitationScrollToItem(target) { layout ->
                            nativeReaderCenteredScrollOffset(
                                layout.viewportStartOffset, layout.viewportEndOffset, 0,
                                layout.visibleItemsInfo.firstOrNull { it.index == target }?.size ?: 0,
                            )
                        }
                        completed = target
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue("Far citation target must begin outside the measured viewport",
                list.layoutInfo.visibleItemsInfo.none { it.index == 270 })
        }
        // Both targets have enough surrounding content to center without hitting a list edge.
        // 90 -> 270 is the regression for "read about one third, citation is near article end".
        for (index in listOf(270, 30)) {
            compose.runOnIdle { target = index }
            compose.waitUntil(10_000) { completed == index }
            compose.runOnIdle {
                val item = list.layoutInfo.visibleItemsInfo.single { it.index == index }
                val center = (list.layoutInfo.viewportStartOffset + list.layoutInfo.viewportEndOffset) / 2f
                assertTrue("Native target $index at ${item.offset} with size ${item.size} must center at $center",
                    abs(item.offset + item.size / 2f - center) < 2f)
            }
        }
    }

    @Test
    fun nativeReader_shortArticleCanReachOffscreenLastParagraphWithoutExactCenter() {
        lateinit var list: LazyListState
        var target by mutableStateOf(-1)
        var completed = -1
        compose.setContent {
            MaterialTheme {
                // Typical article scale: only a dozen blocks, with the user already around 1/3.
                // Under the old visibleItemCount * 3 heuristic, 3 -> 11 would usually miss the
                // native off-screen traversal branch even though the citation is not measured.
                list = rememberLazyListState(initialFirstVisibleItemIndex = 3)
                Box(Modifier.width(384.dp).height(420.dp)) {
                    LazyColumn(state = list) {
                        items(12) { index ->
                            Text("Short article paragraph $index", Modifier.height(180.dp))
                        }
                    }
                }
                LaunchedEffect(target) {
                    if (target >= 0) {
                        list.animateCitationScrollToItem(target) { layout ->
                            nativeReaderCenteredScrollOffset(
                                layout.viewportStartOffset,
                                layout.viewportEndOffset,
                                0,
                                layout.visibleItemsInfo.firstOrNull { it.index == target }?.size ?: 0,
                            )
                        }
                        completed = target
                    }
                }
            }
        }

        compose.runOnIdle {
            assertTrue(
                "Last paragraph must begin outside the measured viewport",
                list.layoutInfo.visibleItemsInfo.none { it.index == 11 },
            )
        }
        compose.runOnIdle { target = 11 }
        compose.waitUntil(10_000) { completed == 11 }
        compose.runOnIdle {
            assertTrue(
                "Last citation paragraph must be visible even though it cannot be centered",
                list.layoutInfo.visibleItemsInfo.any { it.index == 11 },
            )
            assertTrue("Navigation should be allowed to settle at the physical list end", !list.canScrollForward)
        }
    }

    @Test
    fun webView_scrollFinishesBeforeTwoSecondHighlight_andNewTargetCancelsOldPulse() {
        lateinit var web: WebView
        var ready = false
        val prepared = prepareWebViewReaderContent(
            (1..35).joinToString("") { "<p style='height:100px'>Evidence paragraph $it.</p>" },
            "https://example.com/article", true,
        )
        val firstKey = prepared.evidenceDocument.blocks[20].stableLocatorKey
        val secondKey = prepared.evidenceDocument.blocks[4].stableLocatorKey
        compose.setContent {
            AndroidView(
                modifier = Modifier.width(384.dp).height(420.dp),
                factory = { context ->
                    WebView(context).also { view ->
                        web = view
                        view.settings.javaScriptEnabled = true
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) { ready = true }
                        }
                        view.loadDataWithBaseURL("https://example.com/", "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body>${prepared.html}</body></html>", "text/html", "UTF-8", null)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
        compose.waitUntil(10_000) { ready }
        fun evaluate(script: String): String {
            val result = AtomicReference<String?>()
            compose.runOnIdle { web.evaluateJavascript(script) { result.set(it) } }
            compose.waitUntil(5_000) { result.get() != null }
            return requireNotNull(result.get())
        }
        fun navigate(key: String) = evaluate(buildWebViewReaderAnchorScript(key, "rgb(80, 120, 200)", CitationMotion.HighlightMillis))

        navigate(firstKey)
        Thread.sleep(250)
        assertEquals("false", evaluate("Boolean(document.__origreadCitationAnimation)"))
        Thread.sleep(650)
        assertEquals("true", evaluate("document.__origreadCitationAnimation.currentTime < 1000"))
        assertEquals("true", evaluate("Math.abs(document.querySelector('[data-origread-block-id=\"$firstKey\"]').getBoundingClientRect().y + 50 - innerHeight / 2) < 3"))
        Thread.sleep(1200)
        assertEquals("true", evaluate("document.__origreadCitationAnimation.playState === 'running'"))
        assertEquals("\"rgb(80, 120, 200)\"", evaluate("getComputedStyle(document.querySelector('[data-origread-block-id=\"$firstKey\"]')).backgroundColor"))
        navigate(secondKey)
        assertEquals("\"idle\"", evaluate("document.__origreadCitationAnimation.playState"))
        Thread.sleep(850)
        assertEquals("true", evaluate("document.__origreadCitationAnimation.effect.target.getAttribute('data-origread-block-id') === '$secondKey'"))
        navigate(secondKey)
        Thread.sleep(850)
        assertEquals("true", evaluate("document.__origreadCitationAnimation.currentTime < 1000"))
    }

    @Test
    fun webViewReaderAnchor_scrollsOuterComposeHostWhenWebViewIsExpanded() {
        lateinit var web: WebView
        lateinit var outerScroll: ScrollState
        val anchorState = WebViewReaderAnchorState()
        val webViewTopInScrollContent = AtomicInteger(0)
        val viewportTopInWindow = AtomicInteger(0)
        val viewportHeightPx = AtomicInteger(0)
        val navigationResult = AtomicReference<WebViewReaderAnchorNavigationResult?>()
        var ready = false
        val generation = 11L
        val prepared =
            prepareWebViewReaderContent(
                (1..30).joinToString("") {
                    "<p style='height:100px;margin:0'>Evidence paragraph $it.</p>"
                },
                "https://example.com/article",
                true,
            )
        val block = prepared.evidenceDocument.blocks[25]

        compose.setContent {
            outerScroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            Column(
                Modifier
                    .width(384.dp)
                    .height(420.dp)
                    .onGloballyPositioned { coordinates ->
                        viewportTopInWindow.set(coordinates.positionInWindow().y.roundToInt())
                        viewportHeightPx.set(coordinates.size.height)
                    }
                    .verticalScroll(outerScroll)
            ) {
                Spacer(Modifier.height(180.dp))
                AndroidView(
                    modifier =
                        Modifier
                            .width(384.dp)
                            .height(3600.dp)
                            .onGloballyPositioned { coordinates ->
                                webViewTopInScrollContent.set(
                                    coordinates.positionInParent().y.roundToInt()
                                )
                            },
                    factory = { context ->
                        WebView(context).also { view ->
                            web = view
                            view.settings.javaScriptEnabled = true
                            anchorState.bindRender(
                                articleId = "article-1",
                                originalContent = true,
                                evidenceDocument = prepared.evidenceDocument,
                                renderGeneration = generation,
                                webView = view,
                                highlightColorCss = "rgb(80, 120, 200)",
                                markerForegroundCss = "#333333",
                                markerBackgroundCss = "#DDDDDD",
                                highlightDurationMillis = CitationMotion.HighlightMillis,
                                outerScrollHost =
                                    WebViewReaderOuterScrollHost(
                                        scrollState = outerScroll,
                                        webViewTopInScrollContentPx = {
                                            webViewTopInScrollContent.get()
                                        },
                                        coroutineScope = scope,
                                    ),
                            )
                            view.webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        ready = true
                                    }
                                }
                            view.loadDataWithBaseURL(
                                "https://example.com/",
                                "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0'>${prepared.html}</body></html>",
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    onRelease = {
                        anchorState.unbind(it)
                        it.destroy()
                    },
                )
            }
        }
        compose.waitUntil(10_000) { ready }
        compose.runOnIdle {
            assertTrue(anchorState.markRenderReady(web, generation))
            anchorState.navigateTo(
                ReaderEvidenceAnchorTarget(
                    articleId = "article-1",
                    stableLocatorKey = block.stableLocatorKey,
                    normalizedHash = block.normalizedSha256,
                    headingPath = block.headingPath,
                    quote = block.content,
                )
            ) { navigationResult.set(it) }
        }
        compose.waitUntil(10_000) {
            navigationResult.get() is WebViewReaderAnchorNavigationResult.Located
        }
        compose.runOnIdle {
            assertTrue(
                "Citation must move the outer Compose scroll host, got ${outerScroll.value}",
                outerScroll.value > 1_000,
            )
        }

        val internalScroll = AtomicReference<String?>()
        compose.runOnIdle {
            web.evaluateJavascript("window.scrollY") { internalScroll.set(it) }
        }
        compose.waitUntil(5_000) { internalScroll.get() != null }
        assertEquals("0", internalScroll.get()?.trim()?.trim('"'))

        val domTargetTopPx = AtomicReference<String?>()
        compose.runOnIdle {
            web.evaluateJavascript(
                "document.querySelector('[data-origread-block-id=\"${block.stableLocatorKey}\"]')" +
                    ".getBoundingClientRect().top * window.devicePixelRatio"
            ) { domTargetTopPx.set(it) }
        }
        compose.waitUntil(5_000) { domTargetTopPx.get() != null }
        val targetTopInsideWebView =
            requireNotNull(domTargetTopPx.get()).trim().trim('"').toFloat()
        compose.runOnIdle {
            val webLocation = IntArray(2)
            web.getLocationInWindow(webLocation)
            val targetTopInWindow = webLocation[1] + targetTopInsideWebView
            val viewportTop = viewportTopInWindow.get().toFloat()
            val viewportBottom = viewportTop + viewportHeightPx.get()
            assertTrue(
                "Citation DOM target must actually enter the reader viewport: " +
                    "target=$targetTopInWindow viewport=$viewportTop..$viewportBottom",
                targetTopInWindow in viewportTop..viewportBottom,
            )
        }
    }
}
