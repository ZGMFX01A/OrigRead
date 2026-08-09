package me.ash.reader.infrastructure.website

import kotlinx.serialization.Serializable

/** 可导入的网站解析规则文件。 */
@Serializable
data class WebsiteRuleBundle(
    val schemaVersion: Int = 1,
    val rules: List<WebsiteRule>,
)

/**
 * 基于 CSS Selector 的网站列表解析规则。
 * 选择器均遵循 Jsoup 语法，字段选择器相对于单个文章节点执行。
 */
@Serializable
data class WebsiteRule(
    val id: String,
    val name: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val hosts: List<String>,
    val articleSelectors: List<String>,
    val titleSelector: String,
    val linkSelector: String = titleSelector,
    val linkAttribute: String = "href",
    val dateRules: List<WebsiteDateRule> = emptyList(),
    val imageSelector: String? = null,
    val imageAttributes: List<String> = listOf("data-original", "src"),
    /** 文章详情页正文区域选择器，按顺序尝试；为空时回退通用正文抽取。 */
    val contentSelectors: List<String> = emptyList(),
    val includeUrlRegex: String? = null,
    /** 自动 DOM 规则保存的稳定 URL 模式；为空时不执行模式过滤。 */
    val automaticUrlPattern: String? = null,
    /** 自动 DOM 规则使用通用时间提取链，而不是固定格式 dateRules。 */
    val automaticDateExtraction: Boolean = false,
    /** 自动 DOM 首次识别时计算的区域权重，缓存复用后继续参与诊断展示。 */
    val automaticRegionScore: Int = 0,
    val excludeTitleRegexes: List<String> = emptyList(),
    val maxItems: Int = 50,
    val cleanupMode: WebsiteCleanupMode = WebsiteCleanupMode.NONE,
    val urlIdRegex: String? = null,
)

/** 单个发布时间提取规则。 */
@Serializable
data class WebsiteDateRule(
    val selector: String,
    val pattern: String,
)

/** 刷新时是否根据本次列表清理误收数据。 */
@Serializable
enum class WebsiteCleanupMode {
    NONE,
    URL_ID_RANGE,
}
