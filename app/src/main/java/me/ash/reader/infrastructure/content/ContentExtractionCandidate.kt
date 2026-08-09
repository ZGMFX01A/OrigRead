package me.ash.reader.infrastructure.content

/** 正文候选来源，优先级只用于同质量候选的稳定排序。 */
enum class ContentExtractionSource(val priority: Int) {
    PLATFORM_SPECIFIC(50),
    WEBSITE_RULE(40),
    STRUCTURED_DATA(30),
    READABILITY(20),
    META_DESCRIPTION(10),
}

/** 单个正文提取器生成的候选结果。 */
data class ContentExtractionCandidate(
    val source: ContentExtractionSource,
    val html: String,
    val title: String? = null,
    val author: String? = null,
    val publishedTime: String? = null,
    val score: Int = 0,
)

/** 最终正文提取结果。 */
data class ExtractedContent(
    val html: String,
    val source: ContentExtractionSource,
    val score: Int,
    val title: String? = null,
    val author: String? = null,
    val publishedTime: String? = null,
)
