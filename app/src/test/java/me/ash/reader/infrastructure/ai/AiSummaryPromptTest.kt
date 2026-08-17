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

        assertTrue(prompt.contains("release/news"))
        assertTrue(prompt.contains("research/report/analysis"))
        assertTrue(prompt.contains("## 主要内容"))
        assertTrue(prompt.contains("不要因为采用 STANDARD 就削掉复杂文章的论证、方法或限制"))
        assertTrue(prompt.contains("<article>"))
        assertFalse(prompt.contains("以下是摘要"))
    }

    @Test
    fun `system prompt separates facts opinions and evidence`() {
        val prompt = buildAiSummarySystemPrompt("zh-CN")

        assertTrue(prompt.contains("是否值得摘要"))
        assertTrue(prompt.contains("文章形态 × 内容领域"))
        assertTrue(prompt.contains("不得仅因为篇幅中等或偏短就判定无需摘要"))
        assertTrue(prompt.contains("shouldSummarize=false 是高置信度动作"))
        assertTrue(prompt.contains("只要存在疑问，一律返回 true"))
        assertTrue(prompt.contains("可核对事实"))
        assertTrue(prompt.contains("作者观点/判断"))
        assertTrue(prompt.contains("禁止使用原文之外的知识"))
        assertTrue(prompt.contains("origread-summary-v1"))
        assertTrue(prompt.contains("\"v\":1"))
    }

    @Test
    fun `three summary lengths use materially different output contracts`() {
        val brief = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.BRIEF)
        val standard = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.STANDARD)
        val detailed = buildAiSummaryUserPrompt("标题", "正文", AiSummaryLength.DETAILED)

        assertTrue(brief.contains("不要列要点"))
        assertFalse(brief.contains("## 主要内容"))
        assertTrue(standard.contains("research/report/analysis"))
        assertTrue(standard.contains("## 主要内容"))
        assertTrue(detailed.contains("## 论证结构"))
        assertTrue(detailed.contains("研究问题 / 方法或样本 / 关键数据 / 结论 / 限制"))
        assertTrue(detailed.contains("复杂文章原有的多层摘要能力必须保留"))
        assertTrue(detailed.contains("## 值得关注"))
    }

    @Test
    fun `standard prompt derives a concrete output ceiling from article length`() {
        val prompt = buildAiSummaryUserPrompt("新品发布", "正文".repeat(1_000), AiSummaryLength.STANDARD)

        assertTrue(prompt.contains("跨语言等效长度约 2000 单位"))
        assertTrue(prompt.contains("硬上限约为 600 个等效长度单位"))
        assertTrue(prompt.contains("文章形态上限参考"))
        assertTrue(prompt.contains("48%"))
    }
}
