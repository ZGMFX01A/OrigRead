package me.ash.reader.domain.service

import android.util.Log
import me.ash.reader.infrastructure.exception.FeverAPIException
import java.util.Date
import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.rss.provider.fever.FeverAPI
import me.ash.reader.ui.ext.spacerDollar
import me.ash.reader.ui.ext.dollarLast

/** Fever 的目录、内容与状态同步使用同一账户 API。 */
internal suspend fun FeverRssService.syncFeverAccount(accountId: Int): ListenableWorker.Result = coroutineScope {
    try {
        val account = requireNotNull(accountService.getAccountById(accountId)) { "同步账户不存在" }
        require(account.type.id == AccountType.Fever.id) { "account type is invalid" }
        val api = getFeverAPI(account)
        val catalog = fetchFeverCatalog(api, accountId)
        groupDao.insertOrUpdate(catalog.groups)
        feedDao.insertOrUpdate(catalog.feeds)
        refreshFeverIcons(accountId)
        val fetchedAt = Date()
        val articles = fetchFeverArticles(
            FeverArticleRequest(api, accountId, account.lastArticleId?.dollarLast().orEmpty(), fetchedAt)
        )
        saveFeverArticles(accountId, articles.articles)
        syncFeverArticleStates(api, accountId)
        removeOrphanedSubscriptions(accountId, catalog.groups, catalog.feeds)
        accountService.updateSyncMetadata(
            accountId, Date(), articles.lastSeenId.takeIf { it.isNotEmpty() }?.let { accountId.spacerDollar(it) },
        )
        ListenableWorker.Result.success()
    } catch (cancelled: CancellationException) {
        // 任务取消不转为普通同步失败。
        throw cancelled
    } catch (error: Exception) {
        // Fever 沿用失败结果，Worker 不执行成功后的清理。
        Log.e("RLog", "Fever 同步失败：${error.message}", error)
        ListenableWorker.Result.failure()
    }
}

/** 图标补全保留原有远端同步策略，查询范围固定为任务账户。 */
private suspend fun FeverRssService.refreshFeverIcons(accountId: Int) {
    val feeds = feedDao.queryNoIcon(accountId)
    feedDao.update(*feeds.map { it.copy(icon = rssHelper.queryRssIconLink(it.url)) }.toTypedArray())
}

/** 为账户和状态两个 SQL 参数保留空间。 */
private const val FEVER_STATUS_QUERY_CHUNK = 997

/** 先校验完整远端状态，再用哈希集合计算差集并批量更新。 */
private suspend fun FeverRssService.syncFeverArticleStates(api: FeverAPI, accountId: Int) {
    val unreadRaw = api.getUnreadItems().unread_item_ids ?: throw FeverAPIException("Fever unread_item_ids 缺失")
    val savedRaw = api.getSavedItems().saved_item_ids ?: throw FeverAPIException("Fever saved_item_ids 缺失")
    val remoteUnread = unreadRaw.split(",").map { it.trim() }.filterTo(hashSetOf()) { it.isNotEmpty() }
    val remoteStarred = savedRaw.split(",").map { it.trim() }.filterTo(hashSetOf()) { it.isNotEmpty() }
    val metadata = articleDao.queryMetadataAll(accountId)
    val toBeRead = mutableListOf<String>()
    val toBeUnread = mutableListOf<String>()
    val toBeStarred = mutableListOf<String>()
    val toBeUnstarred = mutableListOf<String>()
    metadata.forEach { meta ->
        val id = meta.id.dollarLast()
        if (meta.isUnread && id !in remoteUnread) toBeRead += meta.id
        if (!meta.isUnread && id in remoteUnread) toBeUnread += meta.id
        if (!meta.isStarred && id in remoteStarred) toBeStarred += meta.id
        if (meta.isStarred && id !in remoteStarred) toBeUnstarred += meta.id
    }
    toBeRead.chunked(FEVER_STATUS_QUERY_CHUNK).forEach { articleDao.markAsReadByIdSet(accountId, it.toSet(), false) }
    toBeUnread.chunked(FEVER_STATUS_QUERY_CHUNK).forEach { articleDao.markAsReadByIdSet(accountId, it.toSet(), true) }
    toBeStarred.chunked(FEVER_STATUS_QUERY_CHUNK).forEach { articleDao.markAsStarredByIdSet(accountId, it.toSet(), true) }
    toBeUnstarred.chunked(FEVER_STATUS_QUERY_CHUNK).forEach { articleDao.markAsStarredByIdSet(accountId, it.toSet(), false) }
}
