package me.ash.reader.infrastructure.content

import org.jsoup.Jsoup

/** 使用确定性指标评估正文质量，不依赖 AI。 */
object ContentCandidateScorer {
    fun score(
        html: String,
        expectedTitle: String? = null,
        extractedTitle: String? = null,
    ): Int = evaluate(html, expectedTitle, extractedTitle).score

    /**
     * 返回可测试、可诊断的正文质量指标。
     * 分数只衡量内容本身，明确规则、结构化数据等来源可信度由上层额外加分。
     */
    fun evaluate(
        html: String,
        expectedTitle: String? = null,
        extractedTitle: String? = null,
    ): ContentQualityMetrics {
        val document = Jsoup.parseBodyFragment(html)
        val body = document.body()
        val text = body.text().trim()
        val textLength = text.length
        if (textLength < MIN_TEXT_LENGTH) {
            return ContentQualityMetrics.empty(textLength)
        }

        val paragraphTexts = body.select("p")
            .map { it.text().trim() }
            .filter { it.length >= MIN_PARAGRAPH_LENGTH }
        val paragraphs = paragraphTexts.size
        val headings = body.select("h1, h2, h3").count { it.hasText() }
        val images = body.select("img[src]").size
        val linkTextLength = body.select("a").sumOf { it.text().length }
        val linkDensity = linkTextLength.toDouble() / textLength.coerceAtLeast(1)
        val textDensity = textLength.toDouble() / body.html().length.coerceAtLeast(1)
        val duplicateParagraphRatio = calculateDuplicateRatio(paragraphTexts)
        val adKeywordHits = AD_KEYWORDS.sumOf { keyword ->
            Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE).findAll(text).count()
        }
        val titleMatch = titleMatches(expectedTitle, extractedTitle)

        var score = 0
        score += (textLength / 120).coerceAtMost(30)
        score += when {
            textDensity >= 0.50 -> 20
            textDensity >= 0.35 -> 16
            textDensity >= 0.20 -> 10
            else -> 4
        }
        score += (paragraphs * 4).coerceAtMost(20)
        score += (headings * 2).coerceAtMost(5)
        score += (images * 2).coerceAtMost(8)
        if (titleMatch) score += 15
        if (linkDensity < 0.12) score += 8
        if (linkDensity > 0.35) score -= 15
        if (linkDensity > 0.55) score -= 20
        if (duplicateParagraphRatio >= 0.25) {
            score -= (duplicateParagraphRatio * 30).toInt().coerceAtMost(15)
        }
        score -= (adKeywordHits * 3).coerceAtMost(12)
        if (paragraphs == 0 && textLength < 500) score -= 20

        return ContentQualityMetrics(
            score = score.coerceIn(0, 100),
            textLength = textLength,
            paragraphCount = paragraphs,
            imageCount = images,
            linkDensity = linkDensity,
            textDensity = textDensity,
            duplicateParagraphRatio = duplicateParagraphRatio,
            adKeywordHits = adKeywordHits,
            titleMatched = titleMatch,
        )
    }

    private fun calculateDuplicateRatio(paragraphs: List<String>): Double {
        if (paragraphs.size < 2) return 0.0
        val normalized = paragraphs.map { it.replace(WHITESPACE_REGEX, "").lowercase() }
        val duplicateCount = normalized.size - normalized.distinct().size
        return duplicateCount.toDouble() / normalized.size
    }

    private fun titleMatches(expectedTitle: String?, extractedTitle: String?): Boolean {
        val expected = expectedTitle.normalizeTitle()
        val extracted = extractedTitle.normalizeTitle()
        if (expected.isBlank() || extracted.isBlank()) return false
        return expected == extracted || expected.contains(extracted) || extracted.contains(expected)
    }

    private fun String?.normalizeTitle(): String =
        orEmpty().lowercase().replace(TITLE_NOISE_REGEX, "").trim()

    private const val MIN_TEXT_LENGTH = 80
    private const val MIN_PARAGRAPH_LENGTH = 20
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val TITLE_NOISE_REGEX = Regex("[\\s\\p{Punct}｜|_-]+")
    private val AD_KEYWORDS = listOf(
        "广告",
        "推广",
        "赞助",
        "相关推荐",
        "相关阅读",
        "advertisement",
        "sponsored",
        "affiliate",
    )
}

/** 正文候选的确定性质量指标，供回归测试和后续诊断页面复用。 */
data class ContentQualityMetrics(
    val score: Int,
    val textLength: Int,
    val paragraphCount: Int,
    val imageCount: Int,
    val linkDensity: Double,
    val textDensity: Double,
    val duplicateParagraphRatio: Double,
    val adKeywordHits: Int,
    val titleMatched: Boolean,
) {
    companion object {
        fun empty(textLength: Int = 0) = ContentQualityMetrics(
            score = 0,
            textLength = textLength,
            paragraphCount = 0,
            imageCount = 0,
            linkDensity = 0.0,
            textDensity = 0.0,
            duplicateParagraphRatio = 0.0,
            adKeywordHits = 0,
            titleMatched = false,
        )
    }
}
