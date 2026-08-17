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
    fun `readable floor never breaks 48 percent compression cap`() {
        assertEquals(96, summaryOutputCeiling(200, AiSummaryLength.STANDARD))
        assertEquals(144, summaryOutputCeiling(300, AiSummaryLength.DETAILED))
        assertEquals(1_000, summaryOutputCeiling(3_000, AiSummaryLength.DETAILED))
    }

    @Test
    fun `parses no summary metadata from same ai request`() {
        val result =
            parseAiSummaryModelOutput(
                """<!-- origread-summary-v1: {"v":1,"shouldSummarize":false,"form":"flash","domain":"finance","reason":"source_already_concise"} -->"""
            )
        assertEquals(false, result.shouldSummarize)
        assertEquals(AiArticleForm.FLASH, result.articleForm)
        assertEquals("finance", result.domain)
        assertEquals(AiSummarySkipReason.SOURCE_ALREADY_CONCISE, result.reason)
        assertEquals("", result.summary)
    }

    @Test
    fun `legacy metadata marker remains compatible`() {
        val result =
            parseAiSummaryModelOutput(
                """<!-- origread-summary: {"shouldSummarize":true,"form":"news","domain":"technology","reason":null} -->
                正文摘要""".trimIndent()
            )

        assertTrue(result.shouldSummarize)
        assertEquals("正文摘要", result.summary)
    }

    @Test
    fun `model that ignores metadata fails open to normal summary`() {
        val result = parseAiSummaryModelOutput("普通 Markdown 摘要")
        assertTrue(result.shouldSummarize)
        assertEquals("普通 Markdown 摘要", result.summary)
    }
}
