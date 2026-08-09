package me.ash.reader.infrastructure.content

import javax.inject.Inject
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository
import org.jsoup.nodes.Document

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
                val element = rule.contentSelectors
                    .asSequence()
                    .mapNotNull(document::selectFirst)
                    .firstOrNull { it.text().isNotBlank() }
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
