package me.ash.reader.domain.service

import java.util.Date
import javax.inject.Inject
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteHelper

/**
 * 本地资讯来源同步入口，根据来源类型分派具体抓取逻辑。
 */
class LocalSourceService @Inject constructor(
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
    private val websiteHelper: WebsiteHelper,
    private val jsonSourceHelper: JsonSourceHelper,
    private val rssHubResolver: RssHubResolver,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
) {

    /**
     * 抓取单个来源。当前完整支持 RSS，网站来源将在后续接入独立解析器。
     */
    suspend fun fetch(feed: Feed, preDate: Date = Date()): FeedWithArticle =
        when (feed.sourceType) {
            SourceType.RSS -> fetchRss(feed, preDate)
            SourceType.WEBSITE -> fetchWebsite(feed, preDate)
            SourceType.JSON -> jsonSourceHelper.fetch(feed, preDate)
        }

    /**
     * 保留 Read You 原有 RSS 抓取及图标补全逻辑。
     */
    private suspend fun fetchRss(feed: Feed, preDate: Date): FeedWithArticle {
        var effectiveFeed = feed
        var articles = rssHelper.queryRssXml(feed, "", preDate)
        if (articles.isEmpty()) {
            recoverRssHubFeed(feed, preDate)?.let { recovered ->
                effectiveFeed = recovered.feed
                articles = recovered.articles
            }
        }
        if (feed.icon == null) {
            rssHelper.queryRssIconLink(effectiveFeed.url)?.let { iconLink ->
                rssHelper.saveRssIcon(feedDao, effectiveFeed, iconLink)
            }
        }
        return FeedWithArticle(
            feed = effectiveFeed.copy(isNotification = feed.isNotification && articles.isNotEmpty()),
            articles = articles,
        )
    }

    /**
     * 已固定的 RSSHub 地址失效时，使用原始页面 URL 重新匹配路由和可用实例。
     * 只更新 Feed.url，不删除历史文章；无可用候选时保持原订阅地址等待下次同步。
     */
    private suspend fun recoverRssHubFeed(feed: Feed, preDate: Date): FeedWithArticle? {
        val sourceUrl = rssHubSubscriptionRepository.sourceUrl(feed.id) ?: return null
        val recovered =
            rssHubResolver.probe(sourceUrl)
                .firstOrNull { result -> result.available && result.feed?.entries?.isNotEmpty() == true }
                ?: return null
        val recoveredUrl = recovered.match.feedUrl ?: return null
        val recoveredFeed = feed.copy(url = recoveredUrl)
        if (recoveredFeed.url != feed.url) feedDao.update(recoveredFeed)
        val articles =
            requireNotNull(recovered.feed).entries.map { entry ->
                rssHelper.buildArticleFromSyndEntry(recoveredFeed, feed.accountId, entry, preDate)
            }
        return FeedWithArticle(feed = recoveredFeed, articles = articles)
    }

    /** 抓取普通网站文章列表，并复用原有通知与入库链路。 */
    private suspend fun fetchWebsite(feed: Feed, preDate: Date): FeedWithArticle {
        val articles = websiteHelper.fetchArticles(feed, preDate)
        return FeedWithArticle(
            feed = feed.copy(isNotification = feed.isNotification && articles.isNotEmpty()),
            articles = articles,
        )
    }
}
