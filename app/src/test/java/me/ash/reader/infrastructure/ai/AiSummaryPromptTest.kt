package me.ash.reader.infrastructure.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun `long article sampling keeps opening middle chapter and ending in source order`() {
        val source =
            buildLongArticle(
                chapterCount = 16,
                paragraphCharacters = 2_200,
                keyChapter = 8,
            )

        val prepared = prepareArticleForSummary(source, AiSummaryLength.STANDARD)

        val openingIndex = prepared.indexOf("OPENING-SENTINEL")
        val middleIndex = prepared.indexOf("MIDDLE-KEY-CHAPTER-SENTINEL")
        val endingIndex = prepared.indexOf("ENDING-SENTINEL")
        assertTrue("长文必须触发预算裁剪", prepared.length <= aiSummaryInputBudget(AiSummaryLength.STANDARD))
        assertTrue("开头必须保留", openingIndex >= 0)
        assertTrue("关键中段章节必须保留", middleIndex > openingIndex)
        assertTrue("结尾必须保留", endingIndex > middleIndex)
        assertTrue("裁剪后应插入中性缺失标记", prepared.contains("content omitted"))
    }

    @Test
    fun `single oversized middle chapter is sampled instead of skipped`() {
        val source =
            "<article><h2>Opening</h2><p>OPENING-SENTINEL</p>" +
                "<h2>Huge Middle</h2><p>MIDDLE-HEAD-${"中".repeat(8_000)}" +
                "MIDDLE-CENTER-${"央".repeat(8_000)}MIDDLE-TAIL</p>" +
                "<h2>Ending</h2><p>ENDING-SENTINEL</p></article>"

        val prepared =
            prepareArticleForSummary(
                source,
                AiSummaryLength.STANDARD,
                maxInputCharacters = 6_000,
            )

        assertTrue(prepared.contains("OPENING-SENTINEL"))
        assertTrue(prepared.contains("MIDDLE-HEAD"))
        assertTrue(prepared.contains("MIDDLE-CENTER"))
        assertTrue(prepared.contains("MIDDLE-TAIL"))
        assertTrue(prepared.contains("ENDING-SENTINEL"))
    }

    @Test
    fun `oversized first and last chapters retain representative middle content`() {
        val source =
            "<article><h2>First</h2><p>FIRST-HEAD-${"甲".repeat(7_000)}FIRST-MIDDLE-${"乙".repeat(7_000)}FIRST-TAIL</p>" +
                "<h2>Last</h2><p>LAST-HEAD-${"丙".repeat(7_000)}LAST-MIDDLE-${"丁".repeat(7_000)}LAST-TAIL</p></article>"

        val prepared =
            prepareArticleForSummary(
                source,
                AiSummaryLength.STANDARD,
                maxInputCharacters = 8_000,
            )

        assertTrue(prepared.contains("FIRST-HEAD"))
        assertTrue(prepared.contains("FIRST-MIDDLE"))
        assertTrue(prepared.contains("FIRST-TAIL"))
        assertTrue(prepared.contains("LAST-HEAD"))
        assertTrue(prepared.contains("LAST-MIDDLE"))
        assertTrue(prepared.contains("LAST-TAIL"))
    }

    @Test
    fun `clipping never leaves markdown code or table fence open`() {
        val hugeTable = (0 until 300).joinToString("") { "<tr><td>row-$it</td><td>${"value".repeat(30)}</td></tr>" }
        val source =
            "<article><h2>Start</h2><p>START</p><pre>${"code-line\n".repeat(2_000)}</pre>" +
                "<table>$hugeTable</table><h2>End</h2><p>END</p></article>"

        val prepared =
            prepareArticleForSummary(
                source,
                AiSummaryLength.STANDARD,
                maxInputCharacters = 3_000,
            )

        assertTrue(prepared.contains("START"))
        assertTrue(prepared.contains("END"))
        assertTrue("围栏必须成对出现", Regex("```").findAll(prepared).count() % 2 == 0)
    }

    @Test
    fun `summary budget obeys 4k and 8k provider windows for cjk detailed input`() {
        val systemPrompt = buildAiSummarySystemPrompt("zh-CN") + "\n" + "自定义规则".repeat(200)
        val fourK = planAiSummaryBudget(4_096, systemPrompt, "标题".repeat(40), AiSummaryLength.DETAILED)
        val eightK = planAiSummaryBudget(8_000, systemPrompt, "标题".repeat(40), AiSummaryLength.DETAILED)

        assertTrue(fourK.articleCharacterBudget > 0)
        assertTrue(eightK.articleCharacterBudget > fourK.articleCharacterBudget)
        assertTrue(
            fourK.estimatedFixedPromptTokens + fourK.articleCharacterBudget + fourK.outputReserveTokens +
                fourK.safetyMarginTokens <= 4_096
        )
        assertTrue(
            eightK.estimatedFixedPromptTokens + eightK.articleCharacterBudget + eightK.outputReserveTokens +
                eightK.safetyMarginTokens <= 8_000
        )
    }

    @Test
    fun `reasoning only completion is rejected instead of creating blank generated summary`() {
        val error =
            runCatching {
                requireAiSummaryModelDecision(
                    AiCompletionResult(content = "", reasoning = "已经完成分析与思考")
                )
            }.exceptionOrNull()

        assertTrue(error is AiException)
        assertEquals(AiErrorCode.INVALID_RESPONSE, (error as AiException).code)
        assertTrue(error.message.orEmpty().contains("没有返回摘要正文"))
    }

    @Test
    fun `4k summary rejects oversized skill custom instructions and title before request`() {
        val oversizedFixedPrompt =
            buildAiSummarySystemPrompt("zh-CN") +
                "\n<origread_user_skill>${"技能约束".repeat(2_000)}</origread_user_skill>" +
                "\n<origread_user_custom_instructions>${"长期偏好".repeat(1_000)}</origread_user_custom_instructions>"

        val error =
            runCatching {
                planAiSummaryBudget(
                    contextWindowTokens = 4_096,
                    systemPrompt = oversizedFixedPrompt,
                    title = "超长标题".repeat(600),
                    length = AiSummaryLength.DETAILED,
                )
            }.exceptionOrNull()

        assertTrue(error is AiException)
        assertEquals(AiErrorCode.INVALID_REQUEST, (error as AiException).code)
        assertTrue(error.message.orEmpty().contains("上下文窗口"))
    }

    @Test
    fun `summary rejects remaining article budget below meaningful minimum`() {
        val systemPrompt = buildAiSummarySystemPrompt("zh-CN")
        val fixedTokens =
            estimateAiSummaryFixedPromptTokens(
                systemPrompt = systemPrompt,
                title = "标题",
                length = AiSummaryLength.BRIEF,
            )
        val contextWindow = fixedTokens + 512 + 192 + 511

        val error =
            runCatching {
                planAiSummaryBudget(contextWindow, systemPrompt, "标题", AiSummaryLength.BRIEF)
            }.exceptionOrNull()

        assertTrue(error is AiException)
        assertEquals(AiErrorCode.INVALID_REQUEST, (error as AiException).code)
        assertTrue(error.message.orEmpty().contains("正文预算"))
    }

    @Test
    fun `summary input budgets increase from brief to standard to detailed`() {
        val source =
            buildLongArticle(
                chapterCount = 24,
                paragraphCharacters = 2_500,
                keyChapter = 12,
            )

        val brief = prepareArticleForSummary(source, AiSummaryLength.BRIEF)
        val standard = prepareArticleForSummary(source, AiSummaryLength.STANDARD)
        val detailed = prepareArticleForSummary(source, AiSummaryLength.DETAILED)

        assertTrue(aiSummaryInputBudget(AiSummaryLength.BRIEF) < aiSummaryInputBudget(AiSummaryLength.STANDARD))
        assertTrue(aiSummaryInputBudget(AiSummaryLength.STANDARD) < aiSummaryInputBudget(AiSummaryLength.DETAILED))
        assertTrue("Brief 实际输入应小于 Standard", brief.length < standard.length)
        assertTrue("Standard 实际输入应小于 Detailed", standard.length < detailed.length)
        assertTrue(brief.length <= aiSummaryInputBudget(AiSummaryLength.BRIEF))
        assertTrue(standard.length <= aiSummaryInputBudget(AiSummaryLength.STANDARD))
        assertTrue(detailed.length <= aiSummaryInputBudget(AiSummaryLength.DETAILED))
    }

    @Test
    fun `articles below brief budget stay byte for byte compatible across lengths`() {
        val source =
            """
            <article>
              <h2>Compatibility</h2>
              <p>普通文章不应因为分档预算而改变结构化正文。</p>
            </article>
            """.trimIndent()

        val brief = prepareArticleForSummary(source, AiSummaryLength.BRIEF)
        val standard = prepareArticleForSummary(source, AiSummaryLength.STANDARD)
        val detailed = prepareArticleForSummary(source, AiSummaryLength.DETAILED)

        assertTrue(brief == standard)
        assertTrue(standard == detailed)
        assertFalse(standard.contains("content omitted"))
    }

    @Test
    fun `long article clipping never leaves an unpaired surrogate`() {
        val emojiParagraph = "😀".repeat(16_000)
        val source = "<article><h1>Start</h1><p>$emojiParagraph</p><h2>End</h2><p>ENDING-SENTINEL</p></article>"

        AiSummaryLength.entries.forEach { length ->
            val prepared = prepareArticleForSummary(source, length)
            assertFalse("$length 不得产生孤立 surrogate", prepared.hasUnpairedSurrogate())
        }
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

    /** 构造章节边界稳定、关键事实只位于中段的超长 HTML 文章。 */
    private fun buildLongArticle(
        chapterCount: Int,
        paragraphCharacters: Int,
        keyChapter: Int,
    ): String =
        buildString {
            append("<article><h1>OPENING-SENTINEL</h1>")
            repeat(chapterCount) { chapter ->
                append("<h2>Chapter $chapter</h2><p>")
                append(('a'.code + chapter % 26).toChar().toString().repeat(paragraphCharacters))
                if (chapter == keyChapter) append(" MIDDLE-KEY-CHAPTER-SENTINEL ")
                append("</p>")
            }
            append("<h2>ENDING-SENTINEL</h2><p>final conclusion</p></article>")
        }

    /** 校验 UTF-16 文本没有因字符预算裁剪留下孤立代理项。 */
    private fun String.hasUnpairedSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val current = this[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return true
                    index += 2
                }
                Character.isLowSurrogate(current) -> return true
                else -> index += 1
            }
        }
        return false
    }
}
