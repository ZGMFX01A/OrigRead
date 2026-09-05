package me.ash.reader.ui.component.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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
                list = rememberLazyListState()
                Box(Modifier.width(384.dp).height(420.dp)) {
                    LazyColumn(state = list) {
                        items(40) { index ->
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
        // Both targets have enough surrounding content to center without hitting a list edge.
        for (index in listOf(28, 5)) {
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
}
