package me.ash.reader.infrastructure.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCandidateScorerTest {
    @Test
    fun `article content should outrank navigation and advertising blocks`() {
        val article = """
            <article><h1>稳定正文评分</h1>
            <p>${"这是结构清晰的正文段落，用于验证文本密度、段落数量和链接密度。".repeat(8)}</p>
            <p>${"第二段继续提供有效信息，避免候选仅依赖单一超长文本节点。".repeat(8)}</p>
            <img src="https://example.com/image.jpg"></article>
        """.trimIndent()
        val navigation = """
            <nav>${(1..30).joinToString { "<a href='/tag/$it'>分类广告推广相关推荐</a>" }}</nav>
        """.trimIndent()

        val articleScore = ContentCandidateScorer.score(article, "稳定正文评分", "稳定正文评分")
        val navigationScore = ContentCandidateScorer.score(navigation)

        assertTrue(articleScore > navigationScore)
        // 该样本只有两个长段落，仍应稳定达到可接受正文阈值。
        assertTrue(articleScore >= 50)
    }

    @Test
    fun `title match should provide a bounded confidence bonus`() {
        val html = "<article><p>${"正文内容用于比较标题匹配前后的评分变化。".repeat(20)}</p></article>"
        val withoutTitle = ContentCandidateScorer.score(html)
        val withTitle = ContentCandidateScorer.score(html, "测试标题", "测试标题 - 站点名")

        assertTrue(withTitle > withoutTitle)
        assertTrue(withTitle - withoutTitle <= 15)
    }

    @Test
    fun `duplicate paragraphs and ad keywords should be diagnosed`() {
        val repeated = "重复推广广告段落内容足够长，用于触发重复比例和广告关键词惩罚。"
        val html = "<article>${List(5) { "<p>$repeated</p>" }.joinToString("")}</article>"

        val metrics = ContentCandidateScorer.evaluate(html)

        assertTrue(metrics.duplicateParagraphRatio >= 0.5)
        assertTrue(metrics.adKeywordHits > 0)
        assertEquals(5, metrics.paragraphCount)
    }
}
