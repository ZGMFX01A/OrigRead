package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMarkdownTest {
    @Test
    fun `parses common ai summary markdown blocks`() {
        val blocks =
            parseAiMarkdown(
                """
                ## 摘要
                这是 **核心结论**。

                ## 主要内容
                1. **第一点。** 解释内容。
                - 补充条目
                > 关键引用

                ```text
                exit 0
                ```
                """.trimIndent()
            )

        assertEquals(AiMarkdownBlock.Heading(2, "摘要"), blocks[0])
        assertTrue(blocks.any { it is AiMarkdownBlock.Bullet && it.orderedIndex == 1 })
        assertTrue(blocks.any { it is AiMarkdownBlock.Bullet && it.orderedIndex == null })
        assertTrue(blocks.any { it is AiMarkdownBlock.Quote })
        assertTrue(blocks.any { it is AiMarkdownBlock.Code })
    }

    @Test
    fun `removes only leading summary heading for embedded panel`() {
        val blocks =
            parseAiMarkdown(
                """
                ## 摘要
                第一段内容。

                ## 主要内容
                - 第一项
                """.trimIndent()
            ).withoutLeadingSummaryHeading()

        assertEquals(AiMarkdownBlock.Paragraph("第一段内容。"), blocks[0])
        assertEquals(AiMarkdownBlock.Heading(2, "主要内容"), blocks[1])
    }

    @Test
    fun `keeps non summary leading heading`() {
        val blocks = parseAiMarkdown("## 核心结论\n正文").withoutLeadingSummaryHeading()

        assertEquals(AiMarkdownBlock.Heading(2, "核心结论"), blocks[0])
    }

    @Test
    fun `splits leading bold conclusion from bullet explanation`() {
        val split = splitLeadingBoldBullet("**验证能力才是自主度上界。** 后续说明应该另起一行。")

        assertEquals("验证能力才是自主度上界。", split?.first)
        assertEquals("后续说明应该另起一行。", split?.second)
        assertEquals(null, splitLeadingBoldBullet("普通列表内容不拆分"))
    }

    @Test
    fun `parses github flavored markdown tables as table blocks`() {
        val blocks =
            parseAiMarkdown(
                """
                ### 候选类型怎么选

                | 看到的候选 | 一般怎么选 |
                | --- | --- |
                | **RSS / Atom** | 通常优先，稳定且开销小 |
                | JSON/API | 接口稳定时优先 |
                """.trimIndent(),
            )

        val table = blocks.filterIsInstance<AiMarkdownBlock.Table>().single()
        assertEquals(listOf("看到的候选", "一般怎么选"), table.headers)
        assertEquals(
            listOf("**RSS / Atom**", "通常优先，稳定且开销小"),
            table.rows.first(),
        )
        assertTrue(blocks.none { it is AiMarkdownBlock.Paragraph && it.text.contains("---") })
    }

    @Test
    fun `does not treat a normal paragraph with pipes as a table`() {
        val blocks = parseAiMarkdown("状态 A | 状态 B\n下一行说明")

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is AiMarkdownBlock.Paragraph)
    }
}
