package me.ash.reader.infrastructure.content

import javax.inject.Inject
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 使用网站规则中明确配置的正文选择器提取详情页正文。 */
class WebsiteRuleContentExtractor private constructor(
    private val ruleProvider: (String) -> List<WebsiteRule>,
) : ContentExtractor {

    @Inject
    constructor(repository: WebsiteRuleRepository) : this(repository::findRules)

    internal constructor(rules: List<WebsiteRule>) : this({ rules })

    override fun extract(document: Document, sourceUrl: String): List<ContentExtractionCandidate> {
        return ruleProvider(sourceUrl)
            .asSequence()
            .filter { it.contentSelectors.isNotEmpty() }
            .mapNotNull { rule ->
                val element = selectBestWebsiteContentElement(
                    document = document,
                    sourceUrl = sourceUrl,
                    selectors = rule.contentSelectors,
                    rejectBroadShell = rule.id.startsWith("ai-website-"),
                )
                    ?.element
                    ?: return@mapNotNull null

                val html = element.outerHtml()
                ContentExtractionCandidate(
                    source = ContentExtractionSource.WEBSITE_RULE,
                    html = html,
                    score = ContentCandidateScorer.score(html),
                )
            }
            .toList()
    }
}

/** 规则验证和运行时必须使用同一套选择器排序，避免预览通过但实际选中错误容器。 */
internal data class WebsiteContentSelection(
    val element: Element,
    val score: Int,
)

internal fun selectBestWebsiteContentElement(
    document: Document,
    sourceUrl: String,
    selectors: List<String>,
    rejectBroadShell: Boolean = false,
): WebsiteContentSelection? =
    selectors.mapIndexedNotNull { index, selector ->
        if (rejectBroadShell && isBroadWebsiteContentSelector(selector)) return@mapIndexedNotNull null
        val element = runCatching { document.selectFirst(selector) }.getOrNull() ?: return@mapIndexedNotNull null
        if (element.text().isBlank()) return@mapIndexedNotNull null
        // 页面根节点会把导航、推荐和其他外围内容一起认证为正文，必须回退到其他选择器。
        if (
            rejectBroadShell &&
            (
                element == document ||
                    element == document.body() ||
                    element == document.children().firstOrNull() ||
                    isBroadWebsiteContentElement(element)
            )
        ) {
            return@mapIndexedNotNull null
        }
        val sanitized = ContentHtmlSanitizer.sanitize(element.outerHtml(), sourceUrl)
        WebsiteContentSelection(element = element, score = ContentCandidateScorer.score(sanitized)) to index
    }
        .sortedWith(
            compareByDescending<Pair<WebsiteContentSelection, Int>> { it.first.score }
                // 分数相同仍遵循模型返回的选择器优先级。
                .thenBy { it.second },
        )
        .firstOrNull()
        ?.first

/** 明确指向页面壳的选择器不能作为正文规则，否则会吞入评论、推荐和导航。 */
internal fun isBroadWebsiteContentSelector(selector: String): Boolean {
    val normalized = selector.trim().lowercase().replace(Regex("\\s+"), "")
    return normalized in BROAD_CONTENT_SELECTORS ||
        normalized.startsWith("body>") ||
        normalized.startsWith("html>")
}

/** 处理 div#app、[id=app]、main.page-container 等与页面壳等价的 CSS 写法。 */
private fun isBroadWebsiteContentElement(element: Element): Boolean {
    if (element.tagName().lowercase() in setOf("html", "body", "main")) return true
    if (element.attr("role").lowercase() == "main") return true
    val identifiers = buildSet {
        element.id().trim().lowercase().takeIf(String::isNotBlank)?.let(::add)
        element.classNames().map(String::lowercase).forEach(::add)
    }
    return identifiers.any(PAGE_SHELL_IDENTIFIERS::contains)
}

private val BROAD_CONTENT_SELECTORS = setOf(
    "html",
    "body",
    "main",
    "[role=main]",
    "[role=\"main\"]",
    "[role='main']",
    "#app",
    "#root",
    "#page",
    ".app",
    ".root",
    ".page",
    ".page-container",
    ".layout",
    ".layout-container",
    ".wrapper",
    ".site-wrapper",
)

private val PAGE_SHELL_IDENTIFIERS = setOf(
    "app",
    "root",
    "page",
    "page-container",
    "layout",
    "layout-container",
    "wrapper",
    "site-wrapper",
)
