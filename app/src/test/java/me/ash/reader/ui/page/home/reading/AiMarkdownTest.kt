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
}
