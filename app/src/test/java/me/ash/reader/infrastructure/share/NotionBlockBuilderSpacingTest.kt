package me.ash.reader.infrastructure.share

import org.junit.Assert.assertEquals
import org.junit.Test

class NotionBlockBuilderSpacingTest {
    @Test
    fun `keeps spaces between inline rich text runs`() {
        val blocks =
            NotionBlockBuilder.fromHtml(
                "<p><strong>Hello</strong> <em>World</em> <a href=\"https://example.com\">here</a></p>",
            )

        val richText = blocks.single().getJSONObject("paragraph").getJSONArray("rich_text")

        assertEquals("Hello", richText.getJSONObject(0).getJSONObject("text").getString("content"))
        assertEquals(" ", richText.getJSONObject(1).getJSONObject("text").getString("content"))
        assertEquals("World", richText.getJSONObject(2).getJSONObject("text").getString("content"))
        assertEquals(" ", richText.getJSONObject(3).getJSONObject("text").getString("content"))
        assertEquals("here", richText.getJSONObject(4).getJSONObject("text").getString("content"))
    }
}
