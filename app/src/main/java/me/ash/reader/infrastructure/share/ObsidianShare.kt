package me.ash.reader.infrastructure.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 使用 Obsidian 官方 URI 创建新笔记，避免进入通用“插入到文件”流程。 */
object ObsidianShare {
    const val packageName = "md.obsidian"

    fun isInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    /**
     * 将 Markdown 放入剪贴板，再通过 obsidian://new 创建并打开标题命名的笔记。
     * 返回 false 时由调用方回退到系统分享。
     */
    fun share(context: Context, title: String?, markdown: String): Boolean {
        if (!isInstalled(context) || markdown.isBlank()) return false

        val fileName = sanitizeFileName(title)
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("OrigRead", markdown))

        val uri =
            Uri.parse("obsidian://new")
                .buildUpon()
                .appendQueryParameter("name", fileName)
                .appendQueryParameter("clipboard", "true")
                .build()
        val intent =
            Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
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
