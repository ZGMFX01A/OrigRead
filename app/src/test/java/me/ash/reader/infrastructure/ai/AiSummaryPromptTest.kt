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
                  <table><tr><th>指标</th><th>结果</th></tr><tr><td>准确率</td><td>92%</td></tr></table>
                </article>
                """.trimIndent()
            )

        assertTrue(source.contains("## 核心变化"))
        assertTrue(source.contains("- 验收必须可机械判断"))
        assertTrue(source.contains("> 路径放开，验收收紧。"))
        assertTrue(source.contains("| 指标 | 结果 |\n| 准确率 | 92% |"))
    }

    @Test
    fun `oversized table is sampled across its range without swallowing following prose`() {
        val rows =
            (0 until 120).joinToString("") { row ->
                "<tr>" +
                    (0 until 12).joinToString("") { column ->
                        "<td>row-$row-col-$column-representative-value</td>"
                    } +
                    "</tr>"
            }
        val source =
            prepareArticleForSummary(
                "<article><h2>Data</h2><table>$rows</table><h2>Conclusion</h2><p>正文结论必须保留，不能被巨型表格挤出摘要输入。</p></article>"
            )

        assertTrue(source.contains("表格过大：共 120 行"))
        assertTrue(source.contains("row-0-col-0"))
        assertTrue(source.contains("row-119-col-0"))
        assertTrue(source.contains("正文结论必须保留"))
    }

    @Test
    fun `standard prompt adapts structure to article type instead of forcing thesis chain`() {
        val prompt =
            buildAiSummaryUserPrompt(
                title = "模型越来越强，Harness 该留下什么",
                content = "## 第一部分\n正文",
                length = AiSummaryLength.STANDARD,
            )

        assertTrue(prompt.contains("localized level-2 Markdown heading meaning \"Key Points\""))
        assertTrue(prompt.contains("Start with one overview paragraph"))
        assertTrue(prompt.contains("multiple independent findings, arguments, methods, steps, data points, or limitations"))
        assertTrue(prompt.contains("<article>"))
        assertFalse(prompt.contains("是否值得摘要"))
    }

    @Test
    fun `system prompt is canonical English and separates facts opinions and evidence`() {
        val prompt = buildAiSummarySystemPrompt("zh-CN")

        assertTrue(prompt.contains("You are OrigRead's article summarization editor"))
        assertTrue(prompt.contains("verifiable facts"))
        assertTrue(prompt.contains("the author's judgments"))
        assertTrue(prompt.contains("Use only information contained in the article"))
        assertTrue(prompt.contains("research/report: research question, method/sample, key data, conclusions, and limitations"))
        assertTrue(prompt.contains("The summary should be materially shorter than the source"))
        assertTrue(prompt.contains("origread-summary-v2"))
        assertTrue(prompt.contains("\"v\":2"))
        assertTrue(prompt.contains("Output language: zh-CN"))
        assertFalse(prompt.contains("shouldSummarize"))
        assertFalse(prompt.contains("reason\""))
    }

    @Test
    fun `summary prompt keeps bullet title colon and explanation in one list item`() {
        val prompt = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.STANDARD)

        assertTrue(prompt.contains("- **Conclusion:** explanation"))
        assertTrue(prompt.contains("keep the label, colon, and explanation in the same item"))
    }

    @Test
    fun `three summary lengths use materially different output contracts`() {
        val brief = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.BRIEF)
        val standard = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.STANDARD)
        val detailed = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.DETAILED)

        assertTrue(brief.contains("Write one dense paragraph only"))
        assertTrue(brief.contains("Do not add a summary heading or bullet list"))
        assertTrue(standard.contains("Start with one overview paragraph"))
        assertTrue(standard.contains("localized level-2 Markdown heading meaning \"Key Points\""))
        assertTrue(standard.contains("Never start with a heading or list"))
        assertTrue(detailed.contains("preserve more of the source's meaningful structure and relevant details than STANDARD mode"))
        assertTrue(detailed.contains("Apply the article-form priorities from the system rules"))
        assertTrue(detailed.contains("Use localized level-2 Markdown headings only when the source actually supports those sections"))
    }

    @Test
    fun `summary prompt does not expose local length heuristics or summary eligibility decisions`() {
        val prompt = buildAiSummaryUserPrompt("新品发布", "正文".repeat(1_000), AiSummaryLength.STANDARD)

        assertFalse(prompt.contains("48%"))
        assertFalse(prompt.contains("CJK"))
        assertFalse(prompt.contains("equivalent length"))
        assertFalse(prompt.contains("shouldSummarize"))
        assertFalse(prompt.contains("是否值得摘要"))
    }
}
