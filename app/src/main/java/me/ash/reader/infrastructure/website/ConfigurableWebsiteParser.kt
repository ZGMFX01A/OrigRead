package me.ash.reader.infrastructure.website

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.ui.ext.spacerDollar
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 使用导入规则执行通用网页列表解析。 */
class ConfigurableWebsiteParser(
    private val rule: WebsiteRule,
) : WebsiteParser {

    override fun supports(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return rule.hosts.any { host == it.lowercase() || host.endsWith(".${it.lowercase()}") }
    }

    override fun parse(document: Document, feed: Feed, fetchedAt: Date): List<Article> {
        val items =
            rule.articleSelectors
                .asSequence()
                .map { selector -> document.select(selector) }
                .firstOrNull { it.isNotEmpty() }
                ?: error("规则 ${rule.name} 未匹配到文章节点")

        val includeUrl = rule.includeUrlRegex?.let(::Regex)
        val automaticDateExtractor =
            rule.automaticDateExtraction.takeIf { it }
                ?.let { AutomaticArticleDateExtractor.create(document, fetchedAt) }
        val sourceHost = runCatching { URI(feed.url).host.orEmpty() }.getOrDefault("")
        val excludeTitles = rule.excludeTitleRegexes.map(::Regex)
        val seenLinks = linkedSetOf<String>()

        return items.asSequence()
            .mapNotNull { item -> buildArticle(item, feed, fetchedAt, automaticDateExtractor) }
            .filter { article -> includeUrl == null || includeUrl.matches(article.link) }
            .filter { article ->
                rule.automaticUrlPattern == null ||
                    ArticleUrlPatternNormalizer.normalize(article.link, sourceHost)?.key == rule.automaticUrlPattern
            }
            .filterNot { article -> excludeTitles.any { it.matches(article.title) } }
            .filter { article -> seenLinks.add(article.link) }
            .take(rule.maxItems)
            .toList()
            .also { require(it.isNotEmpty()) { "规则 ${rule.name} 未解析出有效文章" } }
    }

    override fun findObsoleteArticleIds(
        existingArticles: List<Article>,
        fetchedArticles: List<Article>,
    ): List<String> {
        if (rule.cleanupMode != WebsiteCleanupMode.URL_ID_RANGE) return emptyList()
        val idRegex = rule.urlIdRegex?.let(::Regex) ?: return emptyList()
        val fetchedIds = fetchedArticles.mapNotNull { idRegex.find(it.link)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        val oldestFetchedId = fetchedIds.minOrNull() ?: return emptyList()
        val fetchedLinks = fetchedArticles.mapTo(hashSetOf()) { it.link }

        return existingArticles.asSequence()
            .filterNot { it.isStarred }
            .filter { article ->
                val id = idRegex.find(article.link)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    ?: return@filter false
                id >= oldestFetchedId && article.link !in fetchedLinks
            }
            .map { it.id }
            .toList()
    }

    private fun buildArticle(
        item: Element,
        feed: Feed,
        fetchedAt: Date,
        automaticDateExtractor: AutomaticArticleDateExtractor?,
    ): Article? {
        val titleElement = item.selectFirst(rule.titleSelector) ?: return null
        val linkElement = item.selectFirst(rule.linkSelector) ?: return null
        val title = titleElement.text().trim().takeIf { it.isNotBlank() } ?: return null
        val link = linkElement.absUrl(rule.linkAttribute).takeIf { it.isNotBlank() } ?: return null
        val image = rule.imageSelector
            ?.let(item::selectFirst)
            ?.let { element ->
                rule.imageAttributes.asSequence()
                    .map { attr -> element.absUrl(attr) }
                    .firstOrNull { it.isNotBlank() }
            }

        return Article(
            id = feed.accountId.spacerDollar(UUID.randomUUID().toString()),
            date = automaticDateExtractor?.extract(item, link) ?: parseDate(item, fetchedAt),
            title = title,
            rawDescription = "",
            shortDescription = "",
            img = image,
            link = link,
            feedId = feed.id,
            accountId = feed.accountId,
            updateAt = fetchedAt,
        )
    }

    /** 支持首页常见的 HH:mm 与 MM-dd 两类相对日期。 */
    private fun parseDate(item: Element, fetchedAt: Date): Date {
        rule.dateRules.forEach { dateRule ->
            val text = item.selectFirst(dateRule.selector)?.text()?.trim().orEmpty()
            if (text.isBlank()) return@forEach
            runCatching {
                val parsed = SimpleDateFormat(dateRule.pattern, Locale.getDefault()).apply {
                    isLenient = false
                }.parse(text) ?: return@runCatching
                val source = Calendar.getInstance().apply { time = parsed }
                return Calendar.getInstance().apply {
                    time = fetchedAt
                    when (dateRule.pattern) {
                        "HH:mm" -> {
                            set(Calendar.HOUR_OF_DAY, source.get(Calendar.HOUR_OF_DAY))
                            set(Calendar.MINUTE, source.get(Calendar.MINUTE))
                        }
                        "MM-dd" -> {
                            set(Calendar.MONTH, source.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, source.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                        }
                        else -> time = parsed
                    }
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time
            }
        }
        return fetchedAt
    }
}
