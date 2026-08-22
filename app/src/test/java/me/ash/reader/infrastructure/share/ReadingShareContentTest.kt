package me.ash.reader.infrastructure.share

import me.ash.reader.infrastructure.preference.ReadingSharePreference
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingShareContentTest {
    private val preference = ReadingSharePreference(
        isConfigured = true,
        includeTitle = true,
        includeBody = true,
        includeTranslation = true,
        includeSummary = true,
    )

    @Test
    fun `rich payload keeps html images and always appends source url`() {
        val payload = ReadingShareContentBuilder.build(
            title = "原文标题",
            link = "https://example.com/articles/1",
            body = "<p>正文</p><img src=\"/images/cover.jpg\"><script>alert(1)</script>",
            translatedTitle = "Translated title",
            translatedContent = "<p>Translated body</p>",
            translatedDisplayMode = TranslationDisplayMode.TRANSLATED,
            summary = "## 要点\n\n- **结论** 解释",
            preference = preference,
            labels = ReadingShareLabels(sourceUrl = "原文链接", translation = "翻译", summary = "摘要"),
        )

        assertTrue(payload.html.contains("<h1>原文标题</h1>"))
        assertTrue(payload.html.contains("https://example.com/images/cover.jpg"))
        assertFalse(payload.html.contains("<script>"))
        assertFalse(payload.html.contains("<h2>翻译</h2>"))
        assertTrue(payload.html.contains("<blockquote class=\"origread-ai-summary\" style=\""))
        assertTrue(payload.html.contains("class=\"origread-ai-summary-title\""))
        assertTrue(payload.html.contains(">摘要</p>"))
        assertTrue(payload.html.contains("font-size:0.9em"))
        assertTrue(payload.html.contains("background-color:#F3F4F6"))
        assertTrue(payload.html.contains("font-family:Georgia"))
        assertFalse(payload.html.contains("<strong>摘要</strong>"))
        assertTrue(payload.html.contains("<strong>结论</strong>"))
        assertTrue(payload.html.contains("<a href=\"https://example.com/articles/1\">"))
        assertTrue(payload.html.indexOf("<h1>原文标题</h1>") < payload.html.indexOf("<blockquote class=\"origread-ai-summary\""))
        assertTrue(payload.html.indexOf("<blockquote class=\"origread-ai-summary\"") < payload.html.indexOf("Translated body"))
        assertTrue(payload.markdown.contains("# 原文标题"))
        assertTrue(payload.markdown.contains("摘要"))
        assertTrue(payload.markdown.contains("要点"))
        assertFalse(payload.markdown.contains("## 摘要"))
        assertTrue(payload.markdown.contains("> 摘要"))
        assertTrue(payload.markdown.contains("> 要点"))
        assertTrue(payload.markdown.contains("**结论** 解释"))
        assertTrue(payload.markdown.contains("![image](https://example.com/images/cover.jpg)"))
        assertTrue(payload.markdown.contains("**原文链接:** [https://example.com/articles/1](https://example.com/articles/1)"))
        assertTrue(payload.plainText.contains("https://example.com/articles/1"))
        assertTrue(payload.plainText.contains("摘要"))
        assertTrue(payload.plainText.contains("要点"))
        assertFalse(payload.plainText.contains("##"))
        assertFalse(payload.plainText.contains(">"))
        assertFalse(payload.plainText.contains("**"))
        assertFalse(payload.plainText.contains("已生成的 AI 摘要"))
    }

    @Test
    fun `translation share interleaves original and translated blocks`() {
        val payload = ReadingShareContentBuilder.build(
            title = "标题",
            link = "https://example.com/article",
            body = "<p>原文一</p><p>原文二</p>",
            translatedTitle = "Translated title",
            translatedContent = "<p>译文一</p><p>译文二</p>",
            translatedDisplayMode = TranslationDisplayMode.TRANSLATED,
            summary = null,
            preference = preference.copy(includeSummary = false),
        )

        assertTrue(payload.html.indexOf("原文一") < payload.html.indexOf("译文一"))
        assertTrue(payload.html.indexOf("译文一") < payload.html.indexOf("原文二"))
        assertTrue(payload.html.indexOf("原文二") < payload.html.indexOf("译文二"))
        assertTrue(payload.markdown.indexOf("原文一") < payload.markdown.indexOf("译文一"))
        assertTrue(payload.markdown.indexOf("译文一") < payload.markdown.indexOf("原文二"))
    }

    @Test
    fun `summary is below title without an artificial label or translation heading`() {
        val payload = ReadingShareContentBuilder.build(
            title = "标题",
            link = "https://example.com/article",
            body = "<p>正文</p>",
            translatedTitle = null,
            translatedContent = null,
            summary = "## 主要内容\n\n- 重点一",
            preference = preference.copy(includeTranslation = false),
        )

        assertTrue(payload.html.indexOf("<h1>标题</h1>") < payload.html.indexOf("主要内容"))
        assertTrue(payload.html.indexOf("主要内容") < payload.html.indexOf("正文"))
        assertTrue(payload.html.contains(">Summary</p>"))
        assertTrue(payload.markdown.contains("> Summary"))
        assertFalse(payload.markdown.contains("> ## Summary"))
        assertFalse(payload.html.contains("<h2>翻译</h2>"))
        assertFalse(payload.plainText.contains("##"))
        assertTrue(payload.plainText.contains("摘要") || payload.plainText.contains("主要内容"))
    }

    @Test
    fun `dedicated note share omits duplicate original title and keeps translated title`() {
        val payload = ReadingShareContentBuilder.build(
            title = "Original title",
            link = "https://example.com/article",
            body = "<p>Article body</p>",
            translatedTitle = "Translated title",
            translatedContent = "<p>Translated body</p>",
            summary = null,
            preference = preference.copy(includeSummary = false),
            bodyTitle = "Translated title",
        )

        assertTrue(payload.html.contains("<h1>Translated title</h1>"))
        assertFalse(payload.html.contains("<h1>Original title</h1>"))
        assertFalse(payload.markdown.contains("# Original title"))
        assertTrue(payload.markdown.contains("# Translated title"))
    }

    @Test
    fun `dedicated note share omits body title when there is no active translation`() {
        val payload = ReadingShareContentBuilder.build(
            title = "Original title",
            link = "https://example.com/article",
            body = "<p>Article body</p>",
            translatedTitle = null,
            translatedContent = null,
            summary = null,
            preference = preference.copy(includeSummary = false),
            bodyTitle = null,
        )

        assertFalse(payload.html.contains("<h1>Original title</h1>"))
        assertFalse(payload.markdown.contains("# Original title"))
        assertTrue(payload.html.contains("Article body"))
    }

    @Test
    fun `translation share keeps external media in the original block only`() {
        val payload = ReadingShareContentBuilder.build(
            title = "标题",
            link = "https://example.com/article",
            body = "<p>原文<img src=\"https://example.com/image.jpg\"></p>",
            translatedTitle = null,
            translatedContent = "<p>译文<img src=\"https://example.com/image.jpg\"></p>",
            translatedDisplayMode = TranslationDisplayMode.TRANSLATED,
            summary = null,
            preference = preference.copy(includeSummary = false),
        )

        assertEquals(1, Regex("<img ").findAll(payload.html).count())
        assertTrue(payload.html.indexOf("原文") < payload.html.indexOf("译文"))
    }

    @Test
    fun `summary markdown keeps consecutive quote lines together`() {
        val html = MarkdownToHtml.convert("> 第一行\n> 第二行\n\n普通段落")

        assertTrue(html.contains("<blockquote>第一行<br>第二行</blockquote>"))
        assertTrue(html.contains("<p>普通段落</p>"))
    }

    @Test
    fun `plain text keeps readable blocks without markdown markers`() {
        val plainText =
            HtmlToPlainText.convert(
                "<h1>标题</h1><h2>摘要</h2><blockquote><p>主要内容</p><p><strong>重点</strong></p></blockquote>" +
                    "<p>正文第一段</p><ul><li>第一项</li><li>第二项</li></ul>",
            )

        assertEquals("标题\n\n摘要\n\n主要内容\n\n重点\n\n正文第一段\n\n第一项\n\n第二项", plainText)
        assertFalse(plainText.contains("#"))
        assertFalse(plainText.contains(">"))
        assertFalse(plainText.contains("**"))
    }

    @Test
    fun `bilingual translation content is not paired a second time`() {
        val payload = ReadingShareContentBuilder.build(
            title = "标题",
            link = "https://example.com/article",
            body = "<p>原文一</p>",
            translatedTitle = "Translated title",
            translatedContent =
                "<p>原文一</p><div class=\"origread-translation\">译文一</div>",
            translatedDisplayMode = TranslationDisplayMode.BILINGUAL,
            summary = null,
            preference = preference.copy(includeSummary = false),
        )

        assertTrue(payload.html.count { it == '译' } == 1)
        assertTrue(payload.html.indexOf("原文一") < payload.html.indexOf("译文一"))
    }

    @Test
    fun `selected but missing generated content is silently omitted`() {
        val payload = ReadingShareContentBuilder.build(
            title = "标题",
            link = "https://example.com/article",
            body = "<p>正文</p>",
            translatedTitle = null,
            translatedContent = null,
            summary = null,
            preference = preference,
        )

        assertFalse(payload.html.contains("Translation"))
        assertFalse(payload.html.contains("AI summary"))
        assertTrue(payload.html.contains("<h1>标题</h1>"))
        assertTrue(payload.html.contains("https://example.com/article"))
    }

    @Test
    fun `link only still produces a useful html payload`() {
        val payload = ReadingShareContentBuilder.build(
            title = "不会被分享的标题",
            link = "https://example.com/only-link",
            body = "<p>不会被分享的正文</p>",
            translatedTitle = "不会被分享的翻译",
            translatedContent = "<p>不会被分享的译文</p>",
            summary = "不会被分享的摘要",
            preference = ReadingSharePreference(
                isConfigured = true,
                includeTitle = false,
                includeBody = false,
                includeTranslation = false,
                includeSummary = false,
            ),
        )

        assertFalse(payload.html.contains("不会被分享的标题"))
        assertFalse(payload.html.contains("不会被分享的正文"))
        assertTrue(payload.html.contains("https://example.com/only-link"))
    }
}
