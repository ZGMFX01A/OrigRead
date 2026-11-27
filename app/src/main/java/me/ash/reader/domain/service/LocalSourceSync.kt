package me.ash.reader.domain.service

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.website.ConfigurableWebsiteParser

/** 沿用既有来源并发数；不扩大动态页面并发，也不新设性能限制。 */
private const val SOURCE_SYNC_CONCURRENCY = 16

/** 单来源同步结果，区分成功抓取、HTTP 无变化和真实失败。 */
private enum class SourceSyncStatus {
    /** 200 响应及文章内容已提交。 */
    UPDATED,
    /** 304 响应沿用已提交内容。 */
    UNCHANGED,
    /** 网络、解析或持久化失败已记录日志。 */
    FAILED,
}

/** 每个来源独立完成后才汇总，失败不会中断其他来源，也不会虚报全部成功。 */
internal suspend fun LocalRssService.syncLocalSources(
    accountId: Int, feedId: String?, groupId: String?,
): ListenableWorker.Result = supervisorScope {
    try {
        val account = requireNotNull(accountService.getAccountById(accountId)) { "同步账户不存在" }
        require(account.type.id == AccountType.Local.id) { "同步账户类型不是本地账户" }
        val feeds = when {
            feedId != null -> listOf(requireNotNull(feedDao.queryById(feedId)) { "同步来源不存在" })
            groupId != null -> feedDao.queryByGroupId(accountId, groupId)
            else -> feedDao.queryAll(accountId)
        }
        require(feeds.all { it.accountId == accountId }) { "同步来源不属于任务账户" }
        val semaphore = Semaphore(SOURCE_SYNC_CONCURRENCY)
        val fetchedAt = Date()
        val statuses = feeds.map { feed ->
            async(ioDispatcher) { semaphore.withPermit { syncLocalSource(feed, fetchedAt) } }
        }.awaitAll()
        val failedCount = statuses.count { it == SourceSyncStatus.FAILED }
        val resultData = workDataOf(
            "updatedSources" to statuses.count { it == SourceSyncStatus.UPDATED },
            "unchangedSources" to statuses.count { it == SourceSyncStatus.UNCHANGED },
            "failedSources" to failedCount,
            "sourcesProcessed" to true,
        )
        if (failedCount > 0) return@supervisorScope ListenableWorker.Result.failure(resultData)
        accountService.updateSyncTime(accountId, Date())
        ListenableWorker.Result.success(resultData)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // 账户或聚合步骤本身失败时沿用任务重试；单来源失败已在内部独立统计。
        syncLogger.log(error)
        ListenableWorker.Result.retry()
    }
}

/** 抓取、过滤、提交和通知按顺序完成；站点图标不再进入数据刷新必经路径。 */
private suspend fun LocalRssService.syncLocalSource(feed: Feed, fetchedAt: Date): SourceSyncStatus {
    return try {
        val fetched = localSourceService.fetchForSync(feed, fetchedAt)
        fetched.failure?.let { throw it }
        if (fetched.notModified) return SourceSyncStatus.UNCHANGED
        val effectiveFeed = fetched.feedWithArticle.feed
        removeObsoleteWebsiteArticles(fetched)
        val archives = feedDao.queryArchivedArticles(effectiveFeed.id)
        val archivedLinks = archives.map { it.link }.filter { it.isNotBlank() }.toSet()
        val archivedIds = archives.mapNotNull { it.articleId }.toSet()
        val filtered = articleFilterEngine.filterBeforeInsert(
            fetched.feedWithArticle.articles.filterNot { it.id in archivedIds || it.link in archivedLinks }, effectiveFeed.name,
        )
        val newArticles = when (effectiveFeed.sourceType) {
            SourceType.JSON -> updateJsonArticlesAndInsertNew(effectiveFeed, filtered)
            SourceType.WEBSITE -> updateWebsiteArticlesAndInsertNew(effectiveFeed, filtered)
            SourceType.RSS -> articleDao.insertRssWithCache(filtered, effectiveFeed, fetched.pendingCache)
        }
        if (effectiveFeed.isNotification && newArticles.isNotEmpty()) {
            notificationHelper.notify(fetched.feedWithArticle.copy(articles = newArticles))
        }
        SourceSyncStatus.UPDATED
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // 保留来源名称和原始异常，用户可在现有同步日志中定位具体失败项。
        syncLogger.log(java.io.IOException("来源 [${feed.name}] 刷新失败：${error.message}", error))
        SourceSyncStatus.FAILED
    }
}

/** 网站规则原有误收清理保留，RSS 和 JSON 不使用网站清理策略。 */
private suspend fun LocalRssService.removeObsoleteWebsiteArticles(fetched: LocalSourceService.SyncFetchResult) {
    val feed = fetched.feedWithArticle.feed
    if (feed.sourceType != SourceType.WEBSITE) return
    val rule = fetched.websiteCleanupRule ?: return
    val parser = ConfigurableWebsiteParser(rule)
    val existing = articleDao.queryCleanupMetadataByFeedId(feed.accountId, feed.id)
    val obsolete = parser.findObsoleteArticleIds(existing, fetched.feedWithArticle.articles)
    if (obsolete.isNotEmpty()) articleDao.deleteByIds(obsolete)
}
