package me.ash.reader.domain.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.whileSelect
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.ofCategoryStreamIdToId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.ofFeedStreamIdToId
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.spacerDollar

/** 远端目录映射到任务账户；没有分类的来源使用该账户的默认分组。 */
internal suspend fun GoogleReaderRssService.fetchGoogleReaderCatalog(state: GoogleReaderSyncState): Map<Group, List<Feed>> {
    val subscriptions = state.api.getSubscriptionList()
    val subscriptionItems = requireNotNull(subscriptions.subscriptions) { "Google Reader subscriptions 缺失" }
    val accountId = state.accountId
    return subscriptionItems.groupBy { it.categories?.firstOrNull() }.mapKeys { (category, _) ->
        category?.id?.let { id ->
            Group(accountId spacerDollar id.ofCategoryStreamIdToId(), category.label.toString(), accountId)
        } ?: accountService.getDefaultGroup(accountId)
    }.mapValues { (group, feeds) ->
        feeds.map {
            val id = requireNotNull(it.id) { "feed id is null" }
            val url = requireNotNull(it.url ?: it.htmlUrl) { "feed url is null" }
            Feed(
                id = accountId spacerDollar id.ofFeedStreamIdToId(),
                name = it.title.decodeHTML() ?: context.getString(R.string.empty),
                url = url, groupId = group.id, accountId = accountId, icon = it.iconUrl,
            )
        }
    }.toSortedMap { first, second -> first.name.compareTo(second.name) }
}

/** 远端目录的图标补全沿用原有并行任务，来源身份来自固定目录而非界面。 */
internal fun GoogleReaderRssService.refreshGoogleReaderIcons(feeds: List<Feed>, scope: CoroutineScope) {
    scope.launch {
        if (feeds.isEmpty()) return@launch
        val localIds = feedDao.queryAll(feeds.first().accountId).mapTo(hashSetOf()) { it.id }
        val updated = feeds.filter { it.id !in localIds && it.icon == null }.map { feed ->
            async { feed.copy(icon = rssHelper.queryRssIconLink(feed.url)) }
        }.awaitAll().filterNot { it.icon.isNullOrEmpty() }
        feedDao.update(*updated.toTypedArray())
    }
}

/** 内容按完成顺序提交，通知仍仅覆盖本次新增未读文章。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun GoogleReaderRssService.commitGoogleReaderContents(
    pending: List<Deferred<List<Article>>>, accountId: Int, scope: CoroutineScope,
) {
    if (pending.isEmpty()) return
    val remaining = pending.toMutableList()
    val notificationFeeds = feedDao.queryNotificationEnabled(accountId).associateBy { it.id }
    val articlesToNotify = mutableListOf<Article>()
    scope.launch {
        whileSelect {
            for (deferred in remaining) {
                deferred.onAwait { articles ->
                    articleDao.insertList(articles)
                    articlesToNotify.addAll(articles.filter { it.isUnread && it.feedId in notificationFeeds })
                    remaining.remove(deferred)
                    remaining.isNotEmpty()
                }
            }
        }
    }.invokeOnCompletion {
        scope.launch {
            articlesToNotify.groupBy { it.feedId }.forEach { (feedId, articles) ->
                notificationHelper.notify(requireNotNull(notificationFeeds[feedId]), articles)
            }
        }
    }
}
