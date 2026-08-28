package me.ash.reader.infrastructure.share

import android.content.Context

/**
 * Notion 阅读分享目标的本机可用性。
 *
 * Notion 页面创建本身走 API，但“专用分享目标”属于已安装应用入口；因此只有本机安装且对当前进程可见时才展示。
 */
object NotionShareTarget {
    const val packageName = "notion.id"

    /** Notion 不依赖私有 URI Scheme，安装可见即可视为专用目标可用。 */
    fun availability(context: Context): ShareTargetAvailability {
        val installed = context.isVisiblePackage(packageName)
        return ShareTargetAvailability(
            detected = installed,
            available = installed,
        )
    }
}
