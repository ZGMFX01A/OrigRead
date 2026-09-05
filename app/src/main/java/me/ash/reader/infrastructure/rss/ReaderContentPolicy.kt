package me.ash.reader.infrastructure.rss

import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType

/**
 * Single content-source policy shared by Reader and any feature that freezes Reader text.
 *
 * This does not fetch content. It only decides whether the feed already embeds usable full text
 * or whether Reader is expected to resolve a readability/full-content snapshot.
 */
internal object ReaderContentPolicy {
    fun embeddedFullContent(article: Article, feed: Feed): String? =
        article.rawDescription.takeIf {
            feed.sourceType == SourceType.RSS &&
                EmbeddedRssContentPolicy.shouldUseAsFullContent(
                    link = article.link,
                    html = it,
                )
        }

    fun requiresFetchedFullContent(feed: Feed): Boolean =
        feed.sourceType == SourceType.WEBSITE || feed.isFullContent
}
