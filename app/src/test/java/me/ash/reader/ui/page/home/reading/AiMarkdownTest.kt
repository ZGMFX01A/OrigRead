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
    fun `keeps chinese and english colon on leading bold bullet title`() {
        assertEquals(
            "软件工程被逐层拆解：" to "Agent 先接走编码。",
            splitLeadingBoldBullet("**软件工程被逐层拆解**：Agent 先接走编码。"),
        )
        assertEquals(
            "Software engineering changes:" to "Agents take over coding first.",
            splitLeadingBoldBullet("**Software engineering changes**: Agents take over coding first."),
        )
    }

    @Test
    fun `merges malformed colon continuation back into previous bullet`() {
        val chinese = parseAiMarkdown("- **软件工程被逐层拆解**\n：Agent 先接走编码。")
        val english = parseAiMarkdown("- **Software engineering changes**\n: Agents take over coding first.")

        assertEquals(
            listOf(AiMarkdownBlock.Bullet("**软件工程被逐层拆解**：Agent 先接走编码。")),
            chinese,
        )
        assertEquals(
            listOf(AiMarkdownBlock.Bullet("**Software engineering changes**: Agents take over coding first.")),
            english,
        )
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

    @Test
    fun `keeps fenced code language for rich code block`() {
        val code =
            parseAiMarkdown(
                """
                ```kotlin
                val answer = 42
                ```
                """.trimIndent(),
            ).single() as AiMarkdownBlock.Code

        assertEquals("kotlin", code.language)
        assertEquals("val answer = 42", code.text)
    }

    @Test
    fun `parses fenced and display latex as math blocks without mistaking prices`() {
        val fenced =
            parseAiMarkdown(
                """
                ```latex
                E = mc^2
                ```
                """.trimIndent(),
            ).single()
        val display = parseAiMarkdown("${'$'}${'$'}E = mc^2${'$'}${'$'}").single()
        val price = parseAiMarkdown("这张显卡售价 $1600，属于普通正文。").single()

        assertEquals(AiMarkdownBlock.Math("E = mc^2"), fenced)
        assertEquals(AiMarkdownBlock.Math("E = mc^2"), display)
        assertTrue(price is AiMarkdownBlock.Paragraph)
    }

    @Test
    fun `parses mermaid fence as a dedicated special block`() {
        val block =
            parseAiMarkdown(
                """
                ```mermaid
                graph TD
                  A --> B
                ```
                """.trimIndent(),
            ).single()

        assertEquals(AiMarkdownBlock.Mermaid("graph TD\n  A --> B"), block)
    }

    @Test
    fun `serializes table back to markdown for block copy`() {
        val table =
            AiMarkdownBlock.Table(
                headers = listOf("项目", "说明"),
                rows = listOf(listOf("A|B", "测试")),
            )

        assertEquals(
            "| 项目 | 说明 |\n| --- | --- |\n| A\\|B | 测试 |",
            table.toMarkdown(),
        )
    }

    @Test
    fun `keeps escaped pipes inside github flavored markdown table cells`() {
        val table =
            parseAiMarkdown(
                """
                | 名称 | 说明 |
                | --- | --- |
                | A\|B | 竖线属于单元格正文 |
                """.trimIndent(),
            ).filterIsInstance<AiMarkdownBlock.Table>().single()

        assertEquals(listOf("A|B", "竖线属于单元格正文"), table.rows.single())
    }

    @Test
    fun `pads short table rows and ignores extra body cells`() {
        val table =
            parseAiMarkdown(
                """
                | A | B | C |
                | --- | --- | --- |
                | 1 | 2 |
                | 3 | 4 | 5 | extra |
                """.trimIndent(),
            ).filterIsInstance<AiMarkdownBlock.Table>().single()

        assertEquals(listOf("1", "2", ""), table.rows[0])
        assertEquals(listOf("3", "4", "5"), table.rows[1])
    }
}
