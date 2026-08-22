package me.ash.reader.infrastructure.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotionBlockBuilderTest {
    @Test
    fun `html becomes rich blocks and keeps external image link`() {
        val blocks = NotionBlockBuilder.fromHtml(
            "<h1>标题</h1><blockquote><p>摘要</p><ul><li>重点一</li></ul></blockquote>" +
                "<p>正文 <a href=\"https://example.com\"><strong>链接</strong></a></p>" +
                "<img src=\"https://example.com/image.jpg\">",
        )

        assertEquals(listOf("heading_1", "quote", "quote", "paragraph", "image"), blocks.map { it.getString("type") })
        assertTrue(blocks[1].toString().contains("摘要"))
        assertTrue(blocks[2].toString().contains("重点一"))
        assertTrue(blocks[3].toString().contains("https://example.com"))
        assertTrue(blocks[4].toString().contains("https://example.com/image.jpg"))
    }

    @Test
    fun `long text is split below notion rich text limit`() {
        val blocks = NotionBlockBuilder.fromHtml("<p>${"字".repeat(4000)}</p>")
        val richText = blocks.single().getJSONObject("paragraph").getJSONArray("rich_text")

        assertTrue(richText.length() >= 3)
        for (index in 0 until richText.length()) {
            assertTrue(richText.getJSONObject(index).getJSONObject("text").getString("content").length <= 1900)
        }
    }

}
