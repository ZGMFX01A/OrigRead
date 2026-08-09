package me.ash.reader.ui.page.home.reading

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.ash.reader.R
import me.ash.reader.infrastructure.content.BrowserUserAgentPolicy
import me.ash.reader.infrastructure.website.DynamicWebsiteRenderPolicy

@Composable
fun InteractiveArticleVerificationDialog(
    url: String,
    onDismiss: () -> Unit,
    onCapture: (html: String, finalUrl: String) -> Unit,
) {
    val controller = remember(url) { VerificationController(url) }
    SideEffect { controller.updateCallbacks(onDismiss, onCapture) }
    BackHandler(onBack = controller::close)

    Dialog(
        onDismissRequest = controller::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        VerificationContent(controller)
    }
    DisposableEffect(controller) {
        onDispose(controller::dispose)
    }
}

@Composable
private fun VerificationContent(controller: VerificationController) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            VerificationToolbar(controller)
            VerificationHint(controller)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> createVerificationWebView(context, controller) },
            )
        }
    }
}

@Composable
private fun VerificationToolbar(controller: VerificationController) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = controller::close) { Text(stringResource(R.string.cancel)) }
        Text(
            text = stringResource(R.string.web_verification),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (controller.isLoading || controller.isCapturing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        TextButton(
            enabled = controller.canCapture,
            onClick = controller::captureCurrentPage,
        ) {
            Text(stringResource(R.string.parse_current_page))
        }
    }
}

@Composable
private fun VerificationHint(controller: VerificationController) {
    val message = when {
        isWeChatVerificationUrl(controller.currentUrl) -> R.string.wechat_verification_hint
        isWeChatArticleUrl(controller.currentUrl) && controller.isCapturing ->
            R.string.wechat_article_parsing
        else -> null
    } ?: return
    Text(
        text = stringResource(message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createVerificationWebView(
    context: Context,
    controller: VerificationController,
): WebView = WebView(context).apply webViewApply@{
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
        // 微信通过 UA 中的 `wv` 特征识别嵌入式 WebView并弹人机验证。
        // 这里同样换成浏览器风格 UA，避免验证页一打开就再次被风控识别。
        userAgentString =
            BrowserUserAgentPolicy.normalize(
                runCatching { WebSettings.getDefaultUserAgent(context) }
                    .getOrElse { System.getProperty("http.agent").orEmpty() },
            )
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@webViewApply, true)
    }
    webChromeClient = WebChromeClient()
    webViewClient = VerificationWebViewClient(controller)
    controller.attach(this)
    loadUrl(controller.initialUrl)
}

private class VerificationWebViewClient(
    private val controller: VerificationController,
) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        controller.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        controller.onPageFinished(view, url)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val target = request?.url?.toString() ?: return true
        return !isHttpUrl(target)
    }
}

private class VerificationController(
    val initialUrl: String,
) {
    var isLoading by mutableStateOf(true)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    var currentUrl by mutableStateOf(initialUrl)
        private set
    private var webView: WebView? = null
    private var captureCompleted = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAutoCapture: Runnable? = null
    private var onDismiss: () -> Unit = {}
    private var onCapture: (String, String) -> Unit = { _, _ -> }

    val canCapture: Boolean
        get() = !isLoading && !isCapturing && webView != null &&
            !isWeChatVerificationUrl(currentUrl)

    fun updateCallbacks(dismiss: () -> Unit, capture: (String, String) -> Unit) {
        onDismiss = dismiss
        onCapture = capture
    }

    fun attach(target: WebView) {
        webView = target
    }

    fun onPageStarted(url: String?) {
        cancelPendingCapture()
        isLoading = true
        currentUrl = url ?: currentUrl
    }

    fun onPageFinished(target: WebView?, url: String?) {
        isLoading = false
        target ?: return
        val resolvedUrl = url ?: target.url ?: currentUrl
        currentUrl = resolvedUrl
        if (isWeChatArticleUrl(resolvedUrl)) scheduleAutoCapture(target, resolvedUrl)
    }

    fun captureCurrentPage() {
        webView?.let(::capturePage)
    }

    fun close() {
        webView?.stopLoading()
        onDismiss()
    }

    fun dispose() {
        cancelPendingCapture()
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
    }

    private fun capturePage(target: WebView, onEmpty: (() -> Unit)? = null) {
        if (captureCompleted || isCapturing) return
        val pageUrl = target.url ?: currentUrl
        if (isWeChatVerificationUrl(pageUrl)) return
        isCapturing = true
        val script = if (isWeChatArticleUrl(pageUrl)) WECHAT_CAPTURE_SCRIPT else DOM_CAPTURE_SCRIPT
        target.evaluateJavascript(script) { encodedHtml ->
            val html = decodeHtml(encodedHtml)
            isCapturing = false
            if (html.isNullOrBlank()) {
                onEmpty?.invoke()
                return@evaluateJavascript
            }
            captureCompleted = true
            cancelPendingCapture()
            CookieManager.getInstance().flush()
            onCapture(html, target.url ?: pageUrl)
        }
    }

    private fun scheduleAutoCapture(target: WebView, pageUrl: String, attempt: Int = 0) {
        cancelPendingCapture()
        if (captureCompleted) return
        val task = Runnable {
            if (webView !== target || captureCompleted) return@Runnable
            capturePage(target) {
                if (attempt < MAX_CAPTURE_ATTEMPTS) {
                    scheduleAutoCapture(target, target.url ?: pageUrl, attempt + 1)
                }
            }
        }
        pendingAutoCapture = task
        handler.postDelayed(task, CAPTURE_RETRY_DELAY_MS)
    }

    private fun cancelPendingCapture() {
        pendingAutoCapture?.let(handler::removeCallbacks)
        pendingAutoCapture = null
    }

    private fun decodeHtml(encodedHtml: String?): String? = runCatching {
        DynamicWebsiteRenderPolicy.decodeJavascriptString(encodedHtml)
    }.getOrNull()
}
