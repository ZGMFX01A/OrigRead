package me.ash.reader.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSummaryPolicyTest {
    @Test
    fun `obviously concise prose is skipped locally`() {
        val metrics = measureAiSummaryInput("英伟达盘中涨超 10%，受财报超预期影响。")
        assertEquals(AiSummarySkipReason.LOCAL_SOURCE_ALREADY_CONCISE, localSummarySkipReason(metrics))
        assertEquals(
            AiSummarySkipReason.LOCAL_SOURCE_ALREADY_CONCISE,
            localSummarySkipReasonForRequest(metrics, forceRefresh = false),
        )
        assertNull(localSummarySkipReasonForRequest(metrics, forceRefresh = true))
    }

    @Test
    fun `short structured research stays eligible for complex summarization`() {
        val metrics =
            measureAiSummaryInput(
                """
                ## 方法

                - 样本 120 例
                - 对照组 60 例
                - 实验组 60 例

                ## 结论

                主要终点改善，但样本量有限。
                """.trimIndent()
            )
        assertTrue(metrics.effectiveLength < 280)
        assertNull(localSummarySkipReason(metrics))
    }

    @Test
    fun `cjk characters and latin words use comparable effective length units`() {
        val chinese = "这是用于验证跨语言摘要长度估算的一段中文内容，包含若干事实和说明。".repeat(4)
        val english = (0 until 55).joinToString(" ") { "word$it" }

        assertTrue(measureEffectiveLength(chinese) > 100)
        assertEquals(110, measureEffectiveLength(english))
        assertEquals(6, measureEffectiveLength("hello world 2026"))
    }

    @Test
    fun `parses v2 summary metadata`() {
        val result =
            parseAiSummaryModelOutput(
                """<!-- origread-summary-v2: {"v":2,"form":"news","domain":"technology"} -->
                正文摘要""".trimIndent()
            )

        assertEquals(AiArticleForm.NEWS, result.articleForm)
        assertEquals("technology", result.domain)
        assertEquals("正文摘要", result.summary)
    }

    @Test
    fun `parses multiline v2 summary metadata without leaking protocol comment`() {
        val result =
            parseAiSummaryModelOutput(
                """
                <!-- origread-summary-v2: {
                  "v": 2,
                  "form": "news",
                  "domain": "technology"
                } -->
                多行元数据后的正文摘要
                """.trimIndent()
            )

        assertEquals(AiArticleForm.NEWS, result.articleForm)
        assertEquals("technology", result.domain)
        assertEquals("多行元数据后的正文摘要", result.summary)
        assertTrue(!result.summary.contains("origread-summary-v2"))
    }

    @Test
    fun `model that ignores metadata fails open to normal summary`() {
        val result = parseAiSummaryModelOutput("普通 Markdown 摘要")
        assertNull(result.articleForm)
        assertNull(result.domain)
        assertEquals("普通 Markdown 摘要", result.summary)
    }
}
