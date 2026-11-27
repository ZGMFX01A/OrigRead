package me.ash.reader.domain.service

import java.util.Date
import androidx.work.ListenableWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.remoteId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.shortId
import me.ash.reader.ui.ext.dollarLast

/** 单来源刷新与整账户刷新复用同一状态差集实现，API 固定为任务账户。 */
internal suspend fun GoogleReaderRssService.syncGoogleReaderFeed(
    accountId: Int, feedId: String,
): ListenableWorker.Result = coroutineScope {
    val account = requireNotNull(accountService.getAccountById(accountId)) { "cannot find account" }
    require(account.type.id in setOf(AccountType.GoogleReader.id, AccountType.FreshRSS.id)) {
        "account type is invalid"
    }
    val feed = requireNotNull(feedDao.queryById(feedId)) { "同步来源不存在" }
    require(feed.accountId == accountId) { "同步来源不属于任务账户" }
    val api = getGoogleReaderAPI(account)
    val unreadSnapshot = articleDao.queryMetadataByFeedId(accountId, feedId, isUnread = true)
    val readSnapshot = articleDao.queryMetadataByFeedId(accountId, feedId, isUnread = false)
    val localUnread = unreadSnapshot.map { it.id.remoteId }.toSet()
    val localRead = readSnapshot.map { it.id.remoteId }.toSet()
    // 收藏与已读状态独立；需要从两份快照中按真正的收藏标记取 ID。
    val localStarred = (unreadSnapshot + readSnapshot).filter { it.isStarred }.map { it.id.remoteId }.toSet()
    val unread = async {
        fetchReaderItemIds {
            api.getItemIdsForFeed(feedId = feedId.dollarLast(), filterRead = true, continuationId = it)
        }.map { it.shortId }.toSet()
    }
    val all = async {
        fetchReaderItemIds {
            api.getItemIdsForFeed(feedId = feedId.dollarLast(), filterRead = false, continuationId = it)
        }.map { it.shortId }.toSet()
    }
    val starred = async {
        fetchReaderItemIds { api.getStarredItemIds(continuationId = it) }.map { it.shortId }.toSet()
    }
    val read = async { all.await() - unread.await() }
    val local = localRead + localUnread
    val state = GoogleReaderSyncState(account, api, Date(), local, localUnread, localStarred, localRead, unread, starred, read)
    val articles = fetchGoogleReaderContents(
        GoogleReaderContentRequest(all.await() - local, api, accountId, unread.await(), starred.await()), this,
    ).awaitAll().flatten()
    if (feed.isNotification) notificationHelper.notify(feed, articles.filter { it.isUnread })
    reconcileGoogleReaderRead(state, this)
    reconcileGoogleReaderStars(state)
    articleDao.insert(*articles.toTypedArray())
    ListenableWorker.Result.success()
}
