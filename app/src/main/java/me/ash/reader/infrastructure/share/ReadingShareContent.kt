package me.ash.reader.infrastructure.share

import me.ash.reader.infrastructure.content.ContentHtmlSanitizer
import me.ash.reader.infrastructure.preference.ReadingSharePreference
import me.ash.reader.infrastructure.translation.TranslationContentProcessor
import me.ash.reader.infrastructure.translation.TranslationDisplayMode

data class ReadingShareLabels(
    val sourceUrl: String = "Source URL",
    val translation: String = "Translation",
    /** 分享内容中显示的摘要标题。 */
    val summary: String = "Summary",
)

data class ReadingSharePayload(
    val html: String,
    val markdown: String,
    val plainText: String,
)

/** 阅读页分享内容构建器：HTML 给支持富文本的目标，纯文本给兼容性回退。 */
object ReadingShareContentBuilder {
    fun build(
        title: String?,
        link: String?,
        body: String?,
        translatedTitle: String?,
        translatedContent: String?,
        translatedDisplayMode: TranslationDisplayMode = TranslationDisplayMode.TRANSLATED,
        summary: String?,
        preference: ReadingSharePreference,
        labels: ReadingShareLabels = ReadingShareLabels(),
        /**
         * 标题在正文中的显示值。默认使用原文标题；专有笔记渠道可以传入译文标题，
         * 或传 null 以避免与笔记标题重复。
         */
        bodyTitle: String? = title,
    ): ReadingSharePayload {
        val sourceUrl = link.orEmpty().trim()
        val sections = mutableListOf<String>()

        if (preference.includeTitle && !bodyTitle.isNullOrBlank()) {
            sections += "<h1>${escapeHtml(bodyTitle.trim())}</h1>"
        }
        if (preference.includeSummary && !summary.isNullOrBlank()) {
            val summaryHtml = MarkdownToHtml.convert(summary.trim(), preserveHeadings = false)
            if (summaryHtml.isNotBlank()) {
                sections +=
                    "<blockquote class=\"origread-ai-summary\" style=\"" +
                        "margin:16px 0;padding:12px 16px;" +
                        "border-left:4px solid #B8C0CC;border-radius:6px;" +
                        "background-color:#F3F4F6;color:#64748B;" +
                        "font-size:0.9em;line-height:1.65;" +
                        "font-family:Georgia, 'Noto Serif SC', 'Noto Serif CJK SC', serif;\">" +
                        "<p class=\"origread-ai-summary-title\" style=\"margin:0 0 8px;" +
                        "font-size:1.1em;line-height:1.35;color:#475569;" +
                        "font-family:inherit;font-weight:700;\">${escapeHtml(labels.summary.ifBlank { "Summary" })}</p>" +
                        "<div>" +
                        summaryHtml +
                        "</div></blockquote>"
            }
        }
        val hasTranslatedBody = preference.includeTranslation && !translatedContent.isNullOrBlank()
        if (hasTranslatedBody) {
            sections +=
                ContentHtmlSanitizer.sanitize(
                    translatedBody(
                        body = body,
                        translatedContent = translatedContent!!,
                        displayMode = translatedDisplayMode,
                    ),
                    sourceUrl,
                )
        } else if (preference.includeBody && !body.isNullOrBlank()) {
            sections += ContentHtmlSanitizer.sanitize(body, sourceUrl)
        }

        sections +=
            "<hr><p><strong>${escapeHtml(labels.sourceUrl)}:</strong> " +
                "<a href=\"${escapeHtml(sourceUrl)}\">${escapeHtml(sourceUrl)}</a></p>"

        val html = sections.filter(String::isNotBlank).joinToString("\n")
        return ReadingSharePayload(
            html = html,
            markdown = HtmlToMarkdown.convert(html),
            plainText = toPlainText(html),
        )
    }

    private fun toPlainText(html: String): String = HtmlToPlainText.convert(html)

    private fun translatedBody(
        body: String?,
        translatedContent: String,
        displayMode: TranslationDisplayMode,
    ): String {
        if (body.isNullOrBlank()) return translatedContent
        if (displayMode == TranslationDisplayMode.BILINGUAL) return translatedContent
        return TranslationContentProcessor()
            .renderBilingual(body, translatedContent)
            ?: translatedContent
    }

    private fun escapeHtml(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(character)
                }
            }
        }
}
