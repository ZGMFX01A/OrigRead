package me.ash.reader.infrastructure.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** 使用 Logseq 官方 x-callback-url quickCapture 协议写入阅读页 Markdown。 */
object LogseqShare {
    private const val QUICK_CAPTURE_BASE = "logseq://x-callback-url/quickCapture"

    /** Logseq 没有依赖包名判断；能解析官方 quickCapture URI 即视为可用。 */
    fun availability(context: Context): ShareTargetAvailability {
        val intent = createIntent(buildQuickCaptureUri("OrigRead", "https://example.com", "OrigRead"))
        val available = context.canResolveShareIntent(intent)
        return ShareTargetAvailability(detected = available, available = available)
    }

    /** 打开 Logseq quickCapture；启动失败时返回 false，由调用方回退系统分享。 */
    fun share(
        context: Context,
        title: String?,
        url: String?,
        markdown: String,
    ): Boolean {
        if (markdown.isBlank()) return false
        val intent = createIntent(buildQuickCaptureUri(title, url, markdown))
        if (!context.canResolveShareIntent(intent)) return false
        return runCatching {
            context.startActivity(intent)
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

    private fun createIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** URLEncoder 的 `+` 更偏表单语义，这里改成 URI 查询参数更明确的 `%20`。 */
    private fun encodeQueryParameter(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
