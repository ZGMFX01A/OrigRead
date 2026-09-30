package me.ash.reader.ui.component.webview

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WebViewReaderImageDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun legacyHttpCdnImageLoadsInsideHttpsArticleWithoutAllowingMixedContent() {
        val loadedWidth = AtomicReference<String>()
        val requestedUrl = AtomicReference<String>()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val prepared = prepareWebViewReaderContent(
            "<p>Article</p><img src='http://i2.hdslb.com/reader-test.png' referrerpolicy='no-referrer'>",
            "https://t.bilibili.com/1", true,
        )
        lateinit var webView: WebView
        compose.setContent {
            AndroidView(factory = { context ->
                WebView(context).also { view ->
                    webView = view
                    view.settings.javaScriptEnabled = true
                    view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            if (url.endsWith("/reader-test.png")) {
                                requestedUrl.set(url)
                                return WebResourceResponse("image/png", null, ByteArrayInputStream(png))
                            }
                            return null
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("document.images[0].naturalWidth") { loadedWidth.set(it) }
                        }
                    }
                    view.loadDataWithBaseURL("https://t.bilibili.com/1",
                        WebViewHtml.HTML.format("", "https://t.bilibili.com/1", prepared.html, ""),
                        "text/html", "UTF-8", null)
                }
            }, onRelease = { it.destroy() })
        }
        compose.waitUntil(10_000) { loadedWidth.get() != null }
        assertEquals("2", loadedWidth.get())
        assertEquals("https://i2.hdslb.com/reader-test.png", requestedUrl.get())
        compose.runOnIdle { assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, webView.settings.mixedContentMode) }
    }
}
