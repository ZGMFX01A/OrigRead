package me.ash.reader.ui.component.webview

import android.content.Context
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import me.ash.reader.R
import me.ash.reader.ui.ext.ExternalFonts
import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.URISyntaxException
import java.net.URI

const val INJECTION_TOKEN = "/android_asset_font/"

class WebViewClient(
    private val context: Context,
    private val refererDomain: String?,
    private val onOpenLink: (url: String) -> Unit,
    private val onPageFinishedReady: ((view: WebView, url: String?) -> Unit)? = null,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (url != null && isAppAssetsRequest(url)) {
            val target = resolveAppAssetFontTarget(url) ?: return missingFontResponse()
            return try {
                val stream = when (target) {
                    AppAssetFontTarget.External -> {
                        val root = context.filesDir.canonicalFile
                        val font = File(root, ExternalFonts.FontType.ReadingFont.value).canonicalFile
                        if (!font.toPath().startsWith(root.toPath()) || !font.isFile || !font.canRead())
                            return missingFontResponse()
                        font.inputStream()
                    }
                    AppAssetFontTarget.GoogleSans -> context.resources.openRawResource(R.font.google_sans_flex)
                    is AppAssetFontTarget.Asset -> context.assets.open(target.path)
                }
                WebResourceResponse(target.mimeType, null, 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "*"), stream)
            } catch (error: IOException) {
                Log.w("RLog", "Failed to load reader font", error)
                missingFontResponse()
            } catch (error: android.content.res.Resources.NotFoundException) {
                Log.w("RLog", "Reader font resource not found", error)
                missingFontResponse()
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val targetView = view ?: return
        targetView.evaluateJavascript(OnImgClickScript, null)
        onPageFinishedReady?.invoke(targetView, url)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (null == request?.url) return false
        val url = request.url.toString()
        if (url.isNotEmpty()) onOpenLink(url)
        return true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        Log.e("RLog", "OrigReadWebView onReceivedError: $error")
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
    }

    companion object {
        private const val OnImgClickScript = """
            javascript:(function() {
                var imgs = document.getElementsByTagName("img");
                for(var i = 0; i < imgs.length; i++){
                    imgs[i].pos = i;
                    imgs[i].onclick = function(event) {
                        event.preventDefault();
                        window.${JavaScriptInterface.NAME}.onImgTagClick(this.src, this.alt);
                    }
                }
            })()
            """
    }
}

const val APP_ASSETS_HOST = "appassets.androidplatform.net"
const val EXTERNAL_READING_FONT_URL = "https://appassets.androidplatform.net/origread-fonts/external/reading_font.ttf"
const val GOOGLE_SANS_FONT_URL = "https://appassets.androidplatform.net/origread-fonts/res/google_sans_flex.ttf"

internal sealed class AppAssetFontTarget(val mimeType: String) {
    data object External : AppAssetFontTarget("font/ttf")
    data object GoogleSans : AppAssetFontTarget("font/ttf")
    data class Asset(val path: String, val mime: String) : AppAssetFontTarget(mime)
}

internal fun isAppAssetsRequest(url: String): Boolean = try {
    val uri = URI(url)
    uri.host.equals(APP_ASSETS_HOST, ignoreCase = true) && uri.scheme?.lowercase() in setOf("http", "https")
} catch (_: URISyntaxException) { false }

internal fun resolveAppAssetFontTarget(url: String): AppAssetFontTarget? {
    val uri = try { URI(url) } catch (_: URISyntaxException) { return null }
    if (uri.scheme != "https" || uri.host != APP_ASSETS_HOST || uri.rawUserInfo != null ||
        uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
    return when (val path = uri.rawPath) {
        "/origread-fonts/external/reading_font.ttf" -> AppAssetFontTarget.External
        "/origread-fonts/res/google_sans_flex.ttf" -> AppAssetFontTarget.GoogleSans
        else -> {
            if (!path.startsWith(INJECTION_TOKEN)) return null
            val relative = path.removePrefix(INJECTION_TOKEN)
            // 只接收未编码的相对字体路径，拒绝所有空段、点段和路径混淆。
            if (!Regex("[A-Za-z0-9_/-]+(?:[.][A-Za-z0-9_-]+)*").matches(relative) ||
                relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
            val mime = when (relative.substringAfterLast('.').lowercase()) {
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> return null
            }
            AppAssetFontTarget.Asset(relative, mime)
        }
    }
}

private fun missingFontResponse() = WebResourceResponse(
    "font/ttf", null, 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"),
    ByteArrayInputStream(ByteArray(0)),
)
