package me.ash.reader.infrastructure.share

import android.content.ClipData
import android.content.Intent

/**
 * 构建标准 Android 富文本分享 Intent。
 *
 * 大量接收方只读取 EXTRA_TEXT，并且按 String 读取；因此这里必须放可读纯文本。
 * HTML 通过 EXTRA_HTML_TEXT 和 ClipData 额外提供给支持富文本的接收方。
 */
object ReadingShareIntent {
    fun create(title: String?, payload: ReadingSharePayload): Intent {
        val label = title.orEmpty().trim().ifBlank { "OrigRead" }
        val plainText = payload.plainText.ifBlank { label }

        return Intent(Intent.ACTION_SEND).apply {
            // 兼容只调用 getStringExtra(EXTRA_TEXT) 的接收方，不能放 SpannedString。
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, plainText)
            putExtra(Intent.EXTRA_HTML_TEXT, payload.html)
            putExtra(Intent.EXTRA_TITLE, label)
            putExtra(Intent.EXTRA_SUBJECT, label)
            clipData = ClipData.newHtmlText(label, payload.plainText, payload.html)
        }
    }
}
