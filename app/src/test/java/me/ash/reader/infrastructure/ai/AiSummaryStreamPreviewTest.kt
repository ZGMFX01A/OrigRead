package me.ash.reader.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiSummaryStreamPreviewTest {
    @Test
    fun `metadata 未闭合时不泄露协议文本`() {
        assertEquals(
            "",
            extractAiSummaryStreamPreview("<!-- origread-summary-v1: {\"v\":1"),
        )
    }

    @Test
    fun `metadata 闭合后只展示摘要正文`() {
        assertEquals(
            "## 主要内容\n正文",
            extractAiSummaryStreamPreview(
                "<!-- origread-summary-v1: {\"v\":1} -->\n## 主要内容\n正文"
            ),
        )
    }

    @Test
    fun `不带 metadata 的兼容响应直接展示`() {
        assertEquals(
            "直接摘要正文",
            extractAiSummaryStreamPreview("直接摘要正文"),
        )
    }
}
