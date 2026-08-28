package me.ash.reader.infrastructure.share

import android.content.Context
import android.content.Intent

/**
 * 思源 Android 定向分享。
 *
 * 思源目前稳定暴露的是 Android 系统文本分享入口，因此这里使用 ACTION_SEND，而不自行假设未确认的 URI Scheme。
 */
object SiYuanShare {
    const val packageName = "org.b3log.siyuan"

    /** 同时核对包可见性和目标 ACTION_SEND 是否真的可解析。 */
    fun availability(context: Context): ShareTargetAvailability {
        val intent = createIntent(title = "OrigRead", markdown = "OrigRead")
        val available = context.canResolveShareIntent(intent)
        return ShareTargetAvailability(
            detected = context.isVisiblePackage(packageName) || available,
            available = available,
        )
    }

    /** 将现有 Markdown 直接定向发送给思源；失败时由阅读页统一回退系统分享。 */
    fun share(context: Context, title: String?, markdown: String): Boolean {
        if (markdown.isBlank()) return false
        val intent = createIntent(title, markdown)
        if (!context.canResolveShareIntent(intent)) return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 构造思源可接收的标准文本分享 Intent，不依赖内部 Activity 名称。 */
    private fun createIntent(title: String?, markdown: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(packageName)
            putExtra(Intent.EXTRA_TEXT, markdown)
            title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
