package me.ash.reader.domain.service

import java.util.Date
import javax.inject.Inject
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.RssHttpCache
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteHelper

/**
 * 本地资讯来源同步入口，根据来源类型分派具体抓取逻辑。
 */
class LocalSourceService @Inject constructor(
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
    private val rssHttpCacheDao: RssHttpCacheDao,
    private val websiteHelper: WebsiteHelper,
    private val jsonSourceHelper: JsonSourceHelper,
    private val rssHubResolver: RssHubResolver,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
) {

    data class SyncFetchResult(
        val feedWithArticle: FeedWithArticle,
        val notModified: Boolean = false,
    )

    /**
     * 抓取单个来源。当前完整支持 RSS，网站来源将在后续接入独立解析器。
     */
    suspend fun fetch(feed: Feed, preDate: Date = Date()): FeedWithArticle =
        when (feed.sourceType) {
            SourceType.RSS -> fetchRss(feed, preDate)
            SourceType.WEBSITE -> fetchWebsite(feed, preDate)
            SourceType.JSON -> jsonSourceHelper.fetch(feed, preDate)
        }

    suspend fun fetchForSync(feed: Feed, preDate: Date = Date()): SyncFetchResult =
        when (feed.sourceType) {
            SourceType.RSS -> fetchRssForSync(feed, preDate)
            SourceType.WEBSITE -> SyncFetchResult(fetchWebsite(feed, preDate))
            SourceType.JSON -> SyncFetchResult(jsonSourceHelper.fetch(feed, preDate))
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

    private suspend fun fetchRssForSync(feed: Feed, preDate: Date): SyncFetchResult {
        val cache = rssHttpCacheDao.query(feed.id)?.takeIf { it.feedUrl == feed.url }
        val queried =
            rssHelper.queryRssXmlConditional(
                feed = feed,
                latestLink = "",
                preDate = preDate,
                etag = cache?.etag,
                lastModified = cache?.lastModified,
            )
        if (queried.notModified) {
            return SyncFetchResult(
                feedWithArticle = FeedWithArticle(feed = feed, articles = emptyList()),
                notModified = true,
            )
        }

        var effectiveFeed = feed
        var articles = queried.articles
        if (articles.isEmpty()) {
            recoverRssHubFeed(feed, preDate)?.let { recovered ->
                effectiveFeed = recovered.feed
                articles = recovered.articles
            }
        }

        // 只有成功完成 HTTP/XML/文章转换后才更新 validator；临时请求/解析失败保留旧缓存。
        // 若 RSSHub 恢复到了新 URL，则用空 validator 重建缓存归属，下一次 200 再建立条件请求。
        if (queried.successful || effectiveFeed.url != feed.url) {
            rssHttpCacheDao.upsert(
                RssHttpCache(
                    feedId = feed.id,
                    feedUrl = effectiveFeed.url,
                    etag = queried.etag.takeIf { effectiveFeed.url == feed.url },
                    lastModified = queried.lastModified.takeIf { effectiveFeed.url == feed.url },
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

        if (feed.icon == null) {
            rssHelper.queryRssIconLink(effectiveFeed.url)?.let { iconLink ->
                rssHelper.saveRssIcon(feedDao, effectiveFeed, iconLink)
            }
        }
        return SyncFetchResult(
            feedWithArticle =
                FeedWithArticle(
                    feed = effectiveFeed.copy(
                        isNotification = feed.isNotification && articles.isNotEmpty()
                    ),
                    articles = articles,
                )
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
            rssHelper.buildArticlesFromSyndEntries(
                feed = recoveredFeed,
                accountId = feed.accountId,
                entries = requireNotNull(recovered.feed).entries,
                preDate = preDate,
            )
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
