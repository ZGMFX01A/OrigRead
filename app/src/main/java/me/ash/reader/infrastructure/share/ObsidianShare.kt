package me.ash.reader.infrastructure.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 使用 Obsidian 官方 URI 创建新笔记，避免进入通用“插入到文件”流程。 */
object ObsidianShare {
    const val packageName = "md.obsidian"

    /**
     * 安装状态只用于决定配置页是否需要展示入口；真正可用性以官方 obsidian://new Intent 的解析结果为准。
     */
    fun availability(context: Context): ShareTargetAvailability {
        val intent = createIntent(sanitizeFileName("OrigRead"))
        val available = context.canResolveShareIntent(intent)
        return ShareTargetAvailability(
            detected = context.isVisiblePackage(packageName) || available,
            available = available,
        )
    }

    /**
     * 将 Markdown 放入剪贴板，再通过 obsidian://new 创建并打开标题命名的笔记。
     * 返回 false 时由调用方回退到系统分享。
     */
    fun share(context: Context, title: String?, markdown: String): Boolean {
        if (markdown.isBlank()) return false

        val fileName = sanitizeFileName(title)
        val intent = createIntent(fileName)
        if (!context.canResolveShareIntent(intent)) return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("OrigRead", markdown))
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 构造与真正分享完全一致的官方 URI Intent，availability 与执行路径使用同一协议。 */
    private fun createIntent(fileName: String): Intent {
        val uri =
            Uri.parse("obsidian://new")
                .buildUpon()
                .appendQueryParameter("name", fileName)
                .appendQueryParameter("clipboard", "true")
                .build()
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun sanitizeFileName(title: String?): String =
        title
            .orEmpty()
            .replace(INVALID_FILENAME_CHARACTERS, " ")
            .replace(CONTROL_CHARACTERS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .trim('.', ' ')
            .take(MAX_FILENAME_LENGTH)
            .trim('.', ' ')
            .ifBlank { "OrigRead" }

    private const val MAX_FILENAME_LENGTH = 120
    private val INVALID_FILENAME_CHARACTERS = Regex("[\\\\/:*?\"<>|]")
    private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F]")
    private val WHITESPACE = Regex("\\s+")
}
