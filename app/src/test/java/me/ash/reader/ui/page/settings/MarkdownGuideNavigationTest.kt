package me.ash.reader.ui.page.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownGuideNavigationTest {
    @Test
    fun `chinese and mixed headings produce the same anchors used by the user guide`() {
        assertEquals(
            "2-第一次使用添加第一个来源",
            markdownGuideAnchor("2. 第一次使用：添加第一个来源"),
        )
        assertEquals(
            "11-使用-website-rule--json-api-rule",
            markdownGuideAnchor("11. 使用 Website Rule / JSON API Rule"),
        )
    }

    @Test
    fun `guide sections keep fenced code headings inside their original section`() {
        val markdown =
            """
            # Manual

            ## First

            ```text
            # not a heading
            ```

            ## Second
            Body
            """.trimIndent()

        val sections = splitMarkdownGuideSections(markdown)

        assertEquals(listOf("manual", "first", "second"), sections.mapNotNull { it.anchor })
        assertTrue(sections[1].markdown.contains("# not a heading"))
    }

    @Test
    fun `fragment normalization supports encoded anchors`() {
        assertEquals(
            "4-使用-ai-摘要",
            normalizeMarkdownGuideFragment("#4-%E4%BD%BF%E7%94%A8-ai-%E6%91%98%E8%A6%81"),
        )
        assertEquals(null, normalizeMarkdownGuideFragment("https://example.com"))
    }

    @Test
    fun `explicit html anchor becomes an alias of the following heading`() {
        val markdown =
            """
            ## First
            Body

            <br>
            <a id="origread-x-guide"></a>
            # Part II
            More
            """.trimIndent()

        val sections = splitMarkdownGuideSections(markdown)
        val partTwo = sections.single { it.anchor == "part-ii" }

        assertTrue("origread-x-guide" in partTwo.anchorAliases)
        assertTrue(sections.none { it.markdown.contains("<br>") })
    }
}
