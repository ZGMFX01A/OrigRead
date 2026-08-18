package me.ash.reader.infrastructure.website

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.di.MainDispatcher

/** WebView 完成动态页面渲染后返回的最终地址和 DOM。 */
data class DynamicWebsiteRenderResult(
    val finalUrl: String,
    val html: String,
)

/** 动态页面未能安全完成渲染。 */
class DynamicWebsiteRenderException(message: String) : IllegalStateException(message)

/**
 * 使用独立、无界面的 WebView 执行页面自身 JavaScript，并提取渲染后的 DOM。
 * 仅允许 HTTP(S) 同站跳转，不注入 JavaScript 接口，也不处理登录、验证或跨站导航。
 */
@Singleton
class DynamicWebsiteHtmlRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    private val articleWebSessionManager: ArticleWebSessionManager,
) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(url: String): DynamicWebsiteRenderResult =
        render(url, articleWebSessionManager.httpUserAgent)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(
        url: String,
        userAgent: String,
    ): DynamicWebsiteRenderResult = withContext(mainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var pendingCapture: Runnable? = null
            var timeoutTask: Runnable? = null
            var mainFrameNavigationCount = 0
            var completed = false

            /** 所有 WebView 生命周期操作都收口到主线程，避免取消任务后泄漏页面。 */
            fun cleanup() {
                pendingCapture?.let(handler::removeCallbacks)
                pendingCapture = null
                timeoutTask?.let(handler::removeCallbacks)
                timeoutTask = null
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    clearHistory()
                    removeAllViews()
                    destroy()
                }
                webView = null
            }

            fun fail(message: String) {
                if (completed) return
                completed = true
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(DynamicWebsiteRenderException(message))
                }
            }

            fun capture(view: WebView, finalUrl: String) {
                if (completed) return
                view.evaluateJavascript(CAPTURE_DOM_SCRIPT) { encodedHtml ->
                    if (completed) return@evaluateJavascript
                    val html = runCatching {
                        DynamicWebsiteRenderPolicy.decodeJavascriptString(encodedHtml)
                    }.getOrElse {
                        fail("动态页面 DOM 读取失败")
                        return@evaluateJavascript
                    }
                    if (html.isBlank()) {
                        fail("动态页面未返回有效 DOM")
                        return@evaluateJavascript
                    }

                    completed = true
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resume(DynamicWebsiteRenderResult(finalUrl = finalUrl, html = html))
                    }
                }
            }

            val client =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val targetUrl = request?.url?.toString() ?: return true
                        return !DynamicWebsiteRenderPolicy.isAllowedNavigation(url, targetUrl)
                    }

                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                        pendingCapture?.let(handler::removeCallbacks)
                        pendingCapture = null
                        val targetUrl = pageUrl ?: return
                        // 微信已经明确跳到交互验证码时立即结束隐藏渲染。
                        // 继续后台等 15~18 秒没有任何收益，只会让用户误以为正文页卡死；
                        // 前台会转入可见 WebView，由用户正常完成验证。
                        if (DynamicWebsiteRenderPolicy.requiresInteractiveVerification(targetUrl)) {
                            fail("动态页面需要用户完成安全验证")
                            return
                        }
                        if (!DynamicWebsiteRenderPolicy.isAllowedNavigation(url, targetUrl)) {
                            fail("动态页面跳转到了其他站点")
                            return
                        }
                        mainFrameNavigationCount += 1
                        if (mainFrameNavigationCount > MAX_MAIN_FRAME_NAVIGATIONS) {
                            fail("动态页面重定向次数过多")
                        }
                    }

                    override fun onPageFinished(view: WebView?, finalUrl: String?) {
                        val targetView = view ?: return
                        val resolvedUrl = finalUrl ?: targetView.url ?: url
                        if (!DynamicWebsiteRenderPolicy.isAllowedNavigation(url, resolvedUrl)) {
                            fail("动态页面跳转到了其他站点")
                            return
                        }

                        // 页面完成后再等待短暂的 DOM 安静期，给常见 hydration/list 渲染留出时间。
                        pendingCapture?.let(handler::removeCallbacks)
                        pendingCapture = Runnable { capture(targetView, resolvedUrl) }
                        handler.postDelayed(requireNotNull(pendingCapture), DOM_SETTLE_DELAY_MS)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            fail("动态页面加载失败：${error?.description ?: "未知错误"}")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                            fail("动态页面请求失败：HTTP ${errorResponse?.statusCode}")
                        }
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.cancel()
                        fail("动态页面 SSL 校验失败")
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        fail("动态页面渲染进程异常退出")
                        return true
                    }
                }

            // 后台同步也直接调用渲染器，因此超时必须由渲染器自身保证，不能只依赖添加页外层超时。
            timeoutTask = Runnable { fail("动态页面渲染超时") }
            handler.postDelayed(requireNotNull(timeoutTask), RENDER_TIMEOUT_MS)

            webView =
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                    layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                    webViewClient = client
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        mediaPlaybackRequiresUserGesture = true
                        // 微信等站点会通过 UA 中的 `wv` 特征识别嵌入式 WebView 并触发人机验证。
                        // 与静态正文请求共用浏览器风格 UA，让隐藏 WebView 被当作普通浏览器对待。
                        userAgentString = userAgent
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    loadUrl(url)
                }

            continuation.invokeOnCancellation {
                handler.post {
                    if (!completed) {
                        completed = true
                        cleanup()
                    }
                }
            }
        }
    }

    private companion object {
        const val DOM_SETTLE_DELAY_MS = 1_200L
        const val RENDER_TIMEOUT_MS = 15_000L
        const val MAX_MAIN_FRAME_NAVIGATIONS = 8
        const val MAX_RENDERED_HTML_CHARS = 750_000
        const val VIEWPORT_WIDTH = 1080
        const val VIEWPORT_HEIGHT = 1920

        val CAPTURE_DOM_SCRIPT =
            """
            (function() {
                // 微信文章整页脚本和样式非常大，优先只回传正文节点，避免 DOM 上限截断正文。
                var wechatContent = document.querySelector('#js_content');
                if (wechatContent) {
                    return '<html><body>' + wechatContent.outerHTML + '</body></html>';
                }
                var root = document.documentElement;
                if (!root) return "";
                var html = root.outerHTML || "";
                return html.length > $MAX_RENDERED_HTML_CHARS
                    ? html.substring(0, $MAX_RENDERED_HTML_CHARS)
                    : html;
            })();
            """.trimIndent()
    }
}

/** 与 Android WebView 无关的导航和 JavaScript 返回值处理规则，便于 JVM 回归测试。 */
internal object DynamicWebsiteRenderPolicy {
    private val json = Json

    fun requiresInteractiveVerification(targetUrl: String): Boolean =
        runCatching {
            val uri = URI(targetUrl)
            uri.host.equals("mp.weixin.qq.com", ignoreCase = true) &&
                uri.path.orEmpty().contains("wappoc_appmsgcaptcha", ignoreCase = true)
        }.getOrDefault(false)

    fun isAllowedNavigation(initialUrl: String, targetUrl: String): Boolean =
        runCatching {
            val initial = URI(initialUrl)
            val target = URI(targetUrl)
            if (target.scheme?.lowercase() !in setOf("http", "https")) return false
            val initialHost = normalizeHost(initial.host)
            val targetHost = normalizeHost(target.host)
            initialHost.isNotBlank() &&
                targetHost.isNotBlank() &&
                (initialHost == targetHost ||
                    initialHost.endsWith(".$targetHost") ||
                    targetHost.endsWith(".$initialHost"))
        }.getOrDefault(false)

    fun decodeJavascriptString(encodedValue: String?): String {
        val encoded = encodedValue?.takeIf { it != "null" }
            ?: throw IllegalArgumentException("JavaScript 未返回字符串")
        return json.decodeFromString<String>(encoded)
    }

    private fun normalizeHost(host: String?): String =
        host.orEmpty().trim().trimEnd('.').lowercase().removePrefix("www.")
}
