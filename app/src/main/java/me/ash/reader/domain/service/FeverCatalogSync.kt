package me.ash.reader.domain.service

import me.ash.reader.infrastructure.exception.FeverAPIException
import me.ash.reader.R
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.rss.provider.fever.FeverAPI
import me.ash.reader.infrastructure.rss.provider.fever.FeverDTO
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.spacerDollar

internal data class FeverCatalog(val groups: List<Group>, val feeds: List<Feed>)

/** Fever 两个目录接口的分组映射仍按原顺序合并，无分类来源固定到任务账户默认分组。 */
internal suspend fun FeverRssService.fetchFeverCatalog(api: FeverAPI, accountId: Int): FeverCatalog {
    val groupBody = api.getGroups()
    val feedBody = api.getFeeds()
    val remoteGroups = groupBody.groups ?: throw FeverAPIException("Fever groups 缺失")
    val remoteFeeds = feedBody.feeds ?: throw FeverAPIException("Fever feeds 缺失")
    val mapping = buildFeedGroupMapping(groupBody.feeds_groups.orEmpty() + feedBody.feeds_groups.orEmpty())
    val icons = api.getFavicons().favicons.orEmpty().associateBy { it.id }
    val defaultGroup = accountService.getDefaultGroup(accountId)
    val groups = remoteGroups.map {
        Group(accountId.spacerDollar(requireNotNull(it.id)), it.title ?: context.getString(R.string.empty), accountId)
    }
    val feeds = remoteFeeds.map {
        Feed(
            id = accountId.spacerDollar(requireNotNull(it.id)),
            name = it.title.decodeHTML() ?: context.getString(R.string.empty),
            url = requireNotNull(it.url),
            groupId = FeverRssService.resolveFeedGroupId(
                accountId = accountId, remoteFeedId = requireNotNull(it.id).toString(),
                feedsGroupsMap = mapping, defaultGroupId = defaultGroup.id,
            ),
            accountId = accountId, icon = icons[it.favicon_id]?.data,
        )
    }
    val orphaned = remoteFeeds.any { it.id.toString() !in mapping }
    return FeverCatalog(if (orphaned) (groups + defaultGroup).distinctBy { it.id } else groups, feeds)
}

/** feeds 接口中的后出现分组映射覆盖 groups 接口中的同来源映射，保留原有协议处理顺序。 */
private fun buildFeedGroupMapping(items: List<FeverDTO.FeedsGroupsItem>): Map<String, String> =
    buildMap {
        items.forEach { item ->
            val groupId = item.group_id?.toString() ?: return@forEach
            item.feed_ids?.split(",")?.forEach { put(it, groupId) }
        }
    }
