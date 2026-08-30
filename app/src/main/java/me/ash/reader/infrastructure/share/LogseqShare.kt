package me.ash.reader.infrastructure.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** Logseq Android 定向分享：优先系统文本分享，短文本保留官方 quickCapture URI 兼容。 */
object LogseqShare {
    const val packageName = "com.logseq.app"
    private const val QUICK_CAPTURE_BASE = "logseq://x-callback-url/quickCapture"
    private const val QUICK_CAPTURE_FALLBACK_MAX_CHARS = 4_096

    /** 优先识别 Android 官方文本分享入口；旧版只暴露 URI 时再保留 quickCapture 兼容。 */
    fun availability(context: Context): ShareTargetAvailability {
        val textShareAvailable = context.canResolveShareIntent(createTextShareIntent("OrigRead", "OrigRead"))
        val quickCaptureAvailable =
            context.canResolveShareIntent(
                createQuickCaptureIntent(buildQuickCaptureUri("OrigRead", "https://example.com", "OrigRead"))
            )
        return ShareTargetAvailability(
            detected = context.isVisiblePackage(packageName) || textShareAvailable || quickCaptureAvailable,
            available = textShareAvailable || quickCaptureAvailable,
        )
    }

    /**
     * Android 上整篇文章优先走 Logseq 自己的 ACTION_SEND 文本入口。
     *
     * quickCapture URI 官方定位是短文本/链接，不适合把整篇 Markdown 编进 URL；仅为旧版 Logseq
     * 保留短文本兼容。两种方式都失败时返回 false，由阅读页回退系统分享面板。
     */
    fun share(
        context: Context,
        title: String?,
        url: String?,
        markdown: String,
    ): Boolean {
        if (markdown.isBlank()) return false
        val textShareIntent = createTextShareIntent(title, markdown)
        if (context.canResolveShareIntent(textShareIntent)) {
            val sent = runCatching {
                context.startActivity(textShareIntent)
                true
            }.getOrDefault(false)
            if (sent) return true
        }

        if (!shouldUseQuickCaptureFallback(markdown)) return false
        val quickCaptureIntent = createQuickCaptureIntent(buildQuickCaptureUri(title, url, markdown))
        if (!context.canResolveShareIntent(quickCaptureIntent)) return false
        return runCatching {
            context.startActivity(quickCaptureIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * 构造官方 quickCapture URI。使用显式 UTF-8 百分号编码，避免 `&`、`#`、中文和换行破坏参数边界。
     */
    internal fun buildQuickCaptureUri(
        title: String?,
        url: String?,
        markdown: String,
    ): String =
        buildString {
            append(QUICK_CAPTURE_BASE)
            append("?url=")
            append(encodeQueryParameter(url.orEmpty()))
            append("&title=")
            append(encodeQueryParameter(title.orEmpty()))
            append("&content=")
            append(encodeQueryParameter(markdown))
        }

    private fun createTextShareIntent(title: String?, markdown: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(packageName)
            putExtra(Intent.EXTRA_TEXT, markdown)
            title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun createQuickCaptureIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    internal fun shouldUseQuickCaptureFallback(markdown: String): Boolean =
        markdown.length <= QUICK_CAPTURE_FALLBACK_MAX_CHARS

    /** URLEncoder 的 `+` 更偏表单语义，这里改成 URI 查询参数更明确的 `%20`。 */
    private fun encodeQueryParameter(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
