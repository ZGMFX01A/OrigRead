package me.ash.reader.infrastructure.website

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import org.jsoup.nodes.Document

/**
 * 普通网站文章列表解析器。
 */
interface WebsiteParser {

    /** 判断当前解析器是否支持指定网站。 */
    fun supports(url: String): Boolean

    /** 将网站页面解析为应用内部文章模型。 */
    fun parse(document: Document, feed: Feed, fetchedAt: Date): List<Article>

    /**
     * 根据本次权威列表识别需要清理的旧文章。
     * 默认不清理，只有能明确判断列表边界的网站解析器才实现。
     */
    fun findObsoleteArticleIds(
        existingArticles: List<Article>,
        fetchedArticles: List<Article>,
    ): List<String> = emptyList()
}
