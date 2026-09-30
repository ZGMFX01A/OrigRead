package me.ash.reader.domain.service

import java.util.Date
import javax.inject.Inject
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.feed.normalizeRssReadingMode
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.RssHttpCache
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteHelper
import kotlinx.coroutines.CancellationException

/**
 * 本地资讯来源同步入口，根据来源类型分派具体抓取逻辑。
 */
class LocalSourceService @Inject constructor(
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
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
        val failure: Exception? = null,
    )

    /**
     * 抓取单个来源。当前完整支持 RSS，网站来源将在后续接入独立解析器。
     */
    suspend fun fetch(feed: Feed, preDate: Date = Date()): FeedWithArticle =
        when (feed.sourceType) {
            SourceType.RSS -> fetchRss(feed, preDate)
            SourceType.WEBSITE -> fetchWebsiteWithRssRecovery(feed, preDate)
            SourceType.JSON -> jsonSourceHelper.fetch(feed, preDate)
        }

    suspend fun fetchForSync(feed: Feed, preDate: Date = Date()): SyncFetchResult =
        when (feed.sourceType) {
            SourceType.RSS -> fetchRssForSync(feed, preDate)
            SourceType.WEBSITE -> SyncFetchResult(fetchWebsiteWithRssRecovery(feed, preDate))
            SourceType.JSON -> SyncFetchResult(jsonSourceHelper.fetch(feed, preDate))
        }

    /** 保留成熟的 RSS 抓取及图标补全逻辑。 */
    private suspend fun fetchRss(feed: Feed, preDate: Date): FeedWithArticle {
        if (isRssHubFeed(feed)) {
            val result = fetchRssForSync(feed, preDate)
            result.failure?.let { throw it }
            return result.feedWithArticle
        }
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
                effectiveFeed = effectiveFeed.copy(icon = iconLink)
            }
        }
        return FeedWithArticle(
            feed = effectiveFeed.copy(isNotification = feed.isNotification && articles.isNotEmpty()),
            articles = articles,
        )
    }

    private suspend fun fetchRssForSync(feed: Feed, preDate: Date): SyncFetchResult {
        val rssHub = isRssHubFeed(feed)
        val cache = rssHttpCacheDao.query(feed.id)?.takeIf { it.feedUrl == feed.url }
        val queried =
            if (rssHub) rssHelper.queryRssHubXmlConditional(
                feed = feed,
                latestLink = "",
                preDate = preDate,
                etag = cache?.etag,
                lastModified = cache?.lastModified,
            ) else rssHelper.queryRssXmlConditional(
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
        var recoveredSuccessfully = false
        if (articles.isEmpty() && !queried.successful) {
            recoverRssHubFeed(feed, preDate)?.let { recovered ->
                effectiveFeed = recovered.feed
                articles = recovered.articles
                recoveredSuccessfully = true
            }
        }

        // 只有成功完成 HTTP/XML/文章转换后才更新 validator；临时请求/解析失败保留旧缓存。
        // 若 RSSHub 恢复到了新 URL，则用空 validator 重建缓存归属，下一次 200 再建立条件请求。
        if (queried.successful || recoveredSuccessfully) {
            rssHttpCacheDao.upsert(
                RssHttpCache(
                    feedId = feed.id,
                    feedUrl = effectiveFeed.url,
                    etag = queried.etag.takeIf { queried.successful },
                    lastModified = queried.lastModified.takeIf { queried.successful },
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

        if (feed.icon == null && !rssHub) {
            rssHelper.queryRssIconLink(effectiveFeed.url)?.let { iconLink ->
                effectiveFeed = effectiveFeed.copy(icon = iconLink)
            }
        }
        return SyncFetchResult(
            failure = queried.failure.takeIf { rssHub && !queried.successful && !recoveredSuccessfully },
            feedWithArticle =
                FeedWithArticle(
                    feed = effectiveFeed,
                    articles = articles,
                )
        )
    }

    private fun isRssHubFeed(feed: Feed): Boolean =
        rssHubSubscriptionRepository.descriptor(feed.id) != null

    /** 已固定的 RSSHub 地址失效时按 logical route 重选实例；旧版只有 sourceUrl 时仍兼容 Radar 重匹配。 */
    private suspend fun recoverRssHubFeed(feed: Feed, preDate: Date): FeedWithArticle? {
        val descriptor =
            rssHubSubscriptionRepository.descriptor(feed.id)
                ?: return null
        val results =
            if (!descriptor.routePath.isNullOrBlank()) {
                rssHubResolver.probeRouteForRecovery(
                    routePath = descriptor.routePath,
                    boundInstance = descriptor.lastResolvedInstance ?: descriptor.preferredInstance,
                )
            } else {
                rssHubResolver.probe(descriptor.originalInput)
            }
        val recovered =
            results
                .firstOrNull { result -> result.available }
                ?: return null
        val recoveredUrl = recovered.match.feedUrl ?: return null
        val recoveredFeed = feed.copy(
            url = recoveredUrl,
            name = feed.name.ifBlank { recovered.feed?.title?.takeIf(String::isNotBlank) ?: feed.name },
        )
        rssHubSubscriptionRepository.record(
            feed.id,
            descriptor.copy(
                routePath = recovered.routePath ?: descriptor.routePath,
                lastResolvedInstance = recovered.instanceBaseUrl ?: descriptor.lastResolvedInstance,
                lastResolvedUrl = recoveredUrl,
            ),
        )
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
            feed = feed,
            articles = articles,
        )
    }

    /**
     * 兼容旧版把真实 RSS/Atom URL 错存成 Website 的空来源。
     *
     * 正常 Website 先按原逻辑刷新；只有刷新失败且该来源当前 0 篇文章时，才对同一 URL
     * 做一次 direct RSS 解析。解析出非空结构化 Feed 后原地改为 RSS，已有文章的网站绝不改类型。
     */
    private suspend fun fetchWebsiteWithRssRecovery(feed: Feed, preDate: Date): FeedWithArticle =
        try {
            fetchWebsite(feed, preDate)
        } catch (error: CancellationException) {
            throw error
        } catch (websiteError: Exception) {
            recoverMisclassifiedWebsiteRss(feed, preDate) ?: throw websiteError
        }

    private suspend fun recoverMisclassifiedWebsiteRss(feed: Feed, preDate: Date): FeedWithArticle? {
        if (articleDao.countByFeedId(feed.accountId, feed.id) > 0) return null

        val discovered =
            try {
                rssHelper.parseFeedDirect(feedUrl = feed.url, iconSourceUrl = "")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
        if (discovered.entries.isNullOrEmpty()) return null

        val recoveredFeed =
            feed.copy(
                name = discovered.title?.takeIf { it.isNotBlank() } ?: feed.name,
                icon = discovered.icon?.url ?: feed.icon,
                sourceType = SourceType.RSS,
            ).normalizeRssReadingMode()
        val articles =
            rssHelper.buildArticlesFromSyndEntries(
                feed = recoveredFeed,
                accountId = feed.accountId,
                entries = discovered.entries,
                preDate = preDate,
            )

        return FeedWithArticle(
            feed = recoveredFeed,
            articles = articles,
        )
    }
}
