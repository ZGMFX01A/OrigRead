package me.ash.reader.infrastructure.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSummaryPromptTest {
    @Test
    fun `article preprocessing preserves headings lists and quotes`() {
        val source =
            prepareArticleForSummary(
                """
                <article>
                  <h2>核心变化</h2>
                  <p>规则正在从路径约束变成成功判据。</p>
                  <ul><li>验收必须可机械判断</li></ul>
                  <blockquote>路径放开，验收收紧。</blockquote>
                </article>
                """.trimIndent()
            )

        assertTrue(source.contains("## 核心变化"))
        assertTrue(source.contains("- 验收必须可机械判断"))
        assertTrue(source.contains("> 路径放开，验收收紧。"))
    }

    @Test
    fun `standard prompt requests thesis argument chain and markdown structure`() {
        val prompt =
            buildAiSummaryUserPrompt(
                title = "模型越来越强，Harness 该留下什么",
                content = "## 第一部分\n正文",
                length = AiSummaryLength.STANDARD,
            )

        assertTrue(prompt.contains("## 摘要"))
        assertTrue(prompt.contains("## 主要内容"))
        assertTrue(prompt.contains("4～6 个编号要点"))
        assertTrue(prompt.contains("核心问题/主题"))
        assertTrue(prompt.contains("<article>"))
        assertFalse(prompt.contains("以下是摘要"))
    }

    @Test
    fun `system prompt separates facts opinions and evidence`() {
        val prompt = buildAiSummarySystemPrompt("zh-CN")

        assertTrue(prompt.contains("核心问题"))
        assertTrue(prompt.contains("核心结论"))
        assertTrue(prompt.contains("论证"))
        assertTrue(prompt.contains("可核对事实"))
        assertTrue(prompt.contains("作者观点/判断"))
        assertTrue(prompt.contains("规范 Markdown"))
    }

    @Test
    fun `three summary lengths use materially different output contracts`() {
        val brief = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.BRIEF)
        val standard = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.STANDARD)
        val detailed = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.DETAILED)

        assertTrue(brief.contains("不要列要点"))
        assertFalse(brief.contains("## 主要内容"))
        assertTrue(standard.contains("4～6 个编号要点"))
        assertTrue(standard.contains("## 主要内容"))
        assertTrue(detailed.contains("## 论证结构"))
        assertTrue(detailed.contains("5～8 个编号要点"))
        assertTrue(detailed.contains("## 值得关注"))
    }
}
