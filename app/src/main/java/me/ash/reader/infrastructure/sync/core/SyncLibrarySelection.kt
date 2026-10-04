package me.ash.reader.infrastructure.sync.core

import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 导入可保留整账户模式；日常编辑通过确定的实体/来源范围捕获。 */
data class SyncLibrarySelection(val groupIds: Set<String> = emptySet(), val feedIds: Set<String> = emptySet(),
    val articleIds: Set<String> = emptySet(), val articleFeedIds: Set<String> = emptySet(),
    val subscriptionsOnly: Boolean = false, val cascade: Boolean = false)

internal data class SyncLibraryRows(val groups: List<Group>, val feeds: List<Feed>, val articles: List<Article>)

/** 业务行是否真实删除与读取范围是否变化分别判定，避免移动父关系时误发全局删除。 */
internal fun libraryRowExists(database: AndroidDatabase, key: SyncLibraryRowKey): Boolean {
    val table = when (key.type) {
        "group" -> "\"group\""
        "feed" -> "feed"
        "article" -> "article"
        else -> error("Unsupported captured entity ${key.type}")
    }
    return database.openHelper.writableDatabase.query(
        "SELECT 1 FROM $table WHERE id=? AND accountId=? LIMIT 1", arrayOf(key.id, key.accountId),
    ).use { it.moveToFirst() }
}

internal data class SyncLibraryRowKey(val accountId: Int, val type: String, val id: String)

/** 先查询轻量依赖 ID，父行与受影响业务行共同进入同一个 before/after 事务。 */
internal suspend fun selectedLibraryRows(database: AndroidDatabase, account: Int, scope: SyncLibrarySelection?): SyncLibraryRows {
    if (scope == null) return SyncLibraryRows(database.groupDao().queryAll(account), database.feedDao().queryAll(account),
        database.articleDao().queryAllByAccountId(account))
    if (scope.subscriptionsOnly) return SyncLibraryRows(database.groupDao().queryAll(account), database.feedDao().queryAll(account), emptyList())
    val direct = scope.articleIds.mapNotNull { database.articleDao().queryById(it)?.article }.filter { it.accountId == account }
    val children = scope.groupIds.flatMap { id -> database.openHelper.writableDatabase.query(
        "SELECT id FROM feed WHERE accountId=? AND groupId=?", arrayOf(account, id)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    } }
    val feedIds = scope.feedIds + scope.articleFeedIds + direct.map { it.feedId } + children
    val feeds = database.feedDao().queryByIds(feedIds.toList()).filter { it.accountId == account }
    val articleFeeds = if (scope.cascade) feedIds else scope.articleFeedIds
    val articles = (direct + articleFeeds.flatMap { database.articleDao().queryAllByFeedId(account, it) }).distinctBy { it.id }
    val groupIds = scope.groupIds + feeds.map { it.groupId }
    return SyncLibraryRows(database.groupDao().queryByIds(groupIds.toList()).filter { it.accountId == account }, feeds, articles)
}
