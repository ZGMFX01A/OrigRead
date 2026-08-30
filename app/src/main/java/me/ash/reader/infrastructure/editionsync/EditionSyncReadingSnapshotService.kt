package me.ash.reader.infrastructure.editionsync

import androidx.room.withTransaction
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.source.findFeedByComparisonUrl
import me.ash.reader.ui.ext.dollarLast
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.spacerDollar
import timber.log.Timber

/**
 * Standard / LLM 共同阅读主库的可移植快照服务。
 *
 * 不复制运行中的 SQLite 文件；导出时去掉 accountId 主键前缀，恢复时重新映射到目标当前账户，规避两个安装包
 * 账户自增 ID 不一致以及 WAL/Room 版本带来的整库复制风险。
 */
@Singleton
class EditionSyncReadingSnapshotService @Inject constructor(
    private val database: AndroidDatabase,
    private val accountService: AccountService,
    private val accountDao: AccountDao,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
) {
    /** 在任何目标数据写入前完成可静态验证的引用完整性与枚举校验。 */
    fun validate(snapshot: EditionSyncReadingSnapshot) {
        require(snapshot.sourceAccount.name.isNotBlank()) { "同步快照缺少账户名称" }
        require(snapshot.sourceAccount.typeId in 1..6) { "同步快照账户类型无效" }
        require(snapshot.groups.isNotEmpty()) { "同步快照缺少分组" }
        require(snapshot.groups.count(EditionSyncGroupSnapshot::isDefault) == 1) { "同步快照必须且只能包含一个默认分组" }
        require(snapshot.groups.map(EditionSyncGroupSnapshot::key).all(String::isNotBlank)) { "同步快照包含空分组 ID" }
        require(snapshot.groups.map(EditionSyncGroupSnapshot::key).distinct().size == snapshot.groups.size) { "同步快照包含重复分组 ID" }
        require(snapshot.feeds.map(EditionSyncFeedSnapshot::key).all(String::isNotBlank)) { "同步快照包含空订阅 ID" }
        require(snapshot.feeds.map(EditionSyncFeedSnapshot::key).distinct().size == snapshot.feeds.size) { "同步快照包含重复订阅 ID" }
        require(snapshot.articles.map(EditionSyncArticleSnapshot::key).all(String::isNotBlank)) { "同步快照包含空文章 ID" }
        require(snapshot.articles.map(EditionSyncArticleSnapshot::key).distinct().size == snapshot.articles.size) { "同步快照包含重复文章 ID" }

        val groupKeys = snapshot.groups.mapTo(hashSetOf(), EditionSyncGroupSnapshot::key)
        val feedKeys = snapshot.feeds.mapTo(hashSetOf(), EditionSyncFeedSnapshot::key)
        snapshot.feeds.forEach { feed ->
            require(feed.name.isNotBlank() && feed.url.isNotBlank()) { "同步快照包含无效订阅" }
            require(feed.groupKey in groupKeys) { "订阅 ${feed.name} 引用了不存在的分组" }
            SourceType.valueOf(feed.sourceType)
        }
        snapshot.articles.forEach { article ->
            require(article.feedKey in feedKeys) { "文章 ${article.title} 引用了不存在的订阅" }
            require(article.link.isNotBlank()) { "同步快照包含无链接文章" }
        }
        snapshot.archivedArticles.forEach { archived ->
            require(archived.feedKey in feedKeys) { "Archived Article 引用了不存在的订阅" }
            require(archived.link.isNotBlank()) { "同步快照包含空 archived link" }
        }
    }

    /** 导出当前账户的分组、订阅、文章、已读/星标/稍后读以及 archived link。 */
    suspend fun exportCurrentAccount(): EditionSyncReadingSnapshot {
        val accountId = accountService.getCurrentAccountId()
        val account = requireNotNull(accountDao.queryById(accountId)) { "当前账户不存在" }
        val defaultGroupId = accountId.getDefaultGroupId()
        val groups = groupDao.queryAll(accountId).toMutableList()
        if (groups.none { it.id == defaultGroupId }) {
            // 极少数旧开发版数据可能缺失默认组。同步发送端先修复本机完整性，
            // 接收端 validate() 仍保持严格校验，不接受真正破损的外部 Bundle。
            accountService.getDefaultGroup().also { defaultGroup ->
                groupDao.insert(defaultGroup)
                groups += defaultGroup
            }
        }
        val originalFeeds = feedDao.queryAll(accountId)
        val articles = articleDao.queryAllByAccountId(accountId)
        val groupById = groups.associateBy(Group::id)
        val normalizedFeeds = normalizeLegacyFeedGroups(originalFeeds, groupById, defaultGroupId)
        if (normalizedFeeds.repairedGroupIds.isNotEmpty()) {
            normalizedFeeds.repairedGroupIds.forEach { orphanGroupId ->
                feedDao.updateTargetGroupIdByGroupId(
                    accountId = accountId,
                    groupId = orphanGroupId,
                    targetGroupId = defaultGroupId,
                )
            }
            // 只记录修复数量，不记录 Feed 名称、URL 或其他用户内容。
            Timber.tag("EditionSync").i(
                "Repaired %d orphan feed group reference(s) before export",
                normalizedFeeds.repairedFeedCount,
            )
        }
        val feeds = normalizedFeeds.feeds

        val archived =
            feeds.flatMap { feed ->
                feedDao.queryArchivedArticles(feed.id).map { archivedArticle ->
                    EditionSyncArchivedArticleSnapshot(
                        feedKey = portableKey(feed.id),
                        link = archivedArticle.link,
                    )
                }
            }

        return EditionSyncReadingSnapshot(
            sourceAccount =
                EditionSyncAccountSnapshot(
                    name = account.name,
                    typeId = account.type.id,
                    // securityKey 可能含远端账户用户名/密码；它只会进入后续 AES-GCM 保护的显式 Edition 直传包。
                    securityKey = account.securityKey,
                    updatedAtEpochMillis = account.updateAt?.time,
                    lastArticleKey = account.lastArticleId?.let(::portableKey),
                ),
            groups =
                groups.map { group ->
                    EditionSyncGroupSnapshot(
                        key = portableKey(group.id),
                        name = group.name,
                        isDefault = group.id == defaultGroupId,
                    )
                },
            feeds =
                feeds.map { feed ->
                    EditionSyncFeedSnapshot(
                        key = portableKey(feed.id),
                        name = feed.name,
                        icon = feed.icon,
                        url = feed.url,
                        groupKey = portableKey(feed.groupId),
                        groupIsDefault = feed.groupId == defaultGroupId,
                        isNotification = feed.isNotification,
                        isFullContent = feed.isFullContent,
                        isBrowser = feed.isBrowser,
                        sourceType = feed.sourceType.name,
                    )
                },
            articles =
                articles.map { article ->
                    EditionSyncArticleSnapshot(
                        key = portableKey(article.id),
                        feedKey = portableKey(article.feedId),
                        dateEpochMillis = article.date.time,
                        title = article.title,
                        author = article.author,
                        rawDescription = article.rawDescription,
                        shortDescription = article.shortDescription,
                        fullContent = article.fullContent,
                        img = article.img,
                        link = article.link,
                        isUnread = article.isUnread,
                        isStarred = article.isStarred,
                        isReadLater = article.isReadLater,
                        updatedAtEpochMillis = article.updateAt?.time,
                    )
                },
            archivedArticles = archived,
        )
    }

    /**
     * 将快照恢复到目标当前账户。
     *
     * 同步采用“源数据覆盖同一业务项、目标独有数据保留”的非破坏合并：Feed 优先按可移植 ID，其次按 URL；Article
     * 优先按可移植 ID，其次按同 Feed 内 link。这样两边独立添加过相同来源/文章时不会简单制造重复，也不会删除目标额外数据。
     */
    suspend fun restoreCurrentAccount(snapshot: EditionSyncReadingSnapshot): EditionSyncReadingRestoreResult =
        restoreCurrentAccount(snapshot = snapshot, replaceExisting = false)

    /**
     * 精确恢复当前账户快照，只用于 Edition Sync 失败补偿。
     *
     * 与正常同步的非破坏 merge 不同，回滚必须删掉本次失败同步新增的数据，再还原同步前快照，否则“回滚成功”仍会残留 Feed/Article。
     */
    suspend fun replaceCurrentAccount(snapshot: EditionSyncReadingSnapshot): EditionSyncReadingRestoreResult =
        restoreCurrentAccount(snapshot = snapshot, replaceExisting = true)

    private suspend fun restoreCurrentAccount(
        snapshot: EditionSyncReadingSnapshot,
        replaceExisting: Boolean,
    ): EditionSyncReadingRestoreResult {
        validate(snapshot)
        val targetAccountId = accountService.getCurrentAccountId()
        val targetAccount = requireNotNull(accountDao.queryById(targetAccountId)) { "目标当前账户不存在" }
        require(targetAccount.type.id == snapshot.sourceAccount.typeId) {
            "源账户类型与目标当前账户类型不同，不能直接合并阅读主库"
        }

        var restoredGroups = 0
        var restoredFeeds = 0
        var restoredArticles = 0
        var restoredArchivedArticles = 0

        database.withTransaction {
            if (replaceExisting) {
                // 按外键依赖从叶子向上删除；ArchivedArticle 会随 Feed CASCADE 删除。
                articleDao.deleteByAccountId(targetAccountId)
                feedDao.deleteByAccountId(targetAccountId)
                groupDao.deleteByAccountId(targetAccountId)
            }
            val targetDefaultGroupId = targetAccountId.getDefaultGroupId()
            val existingGroups = groupDao.queryAll(targetAccountId)
            val groupIdByKey = linkedMapOf<String, String>()

            snapshot.groups.forEach { source ->
                val desiredId =
                    if (source.isDefault) targetDefaultGroupId
                    else targetAccountId.spacerDollar(source.key)
                val existing =
                    existingGroups.firstOrNull { it.id == desiredId }
                        ?: existingGroups.firstOrNull { it.name == source.name }
                val targetId = existing?.id ?: desiredId
                groupDao.insert(
                    Group(
                        id = targetId,
                        name = source.name,
                        accountId = targetAccountId,
                    )
                )
                groupIdByKey[source.key] = targetId
                restoredGroups++
            }

            // 兼容极端旧快照没有显式默认组记录的情况。
            val defaultSourceGroupKey = snapshot.groups.firstOrNull(EditionSyncGroupSnapshot::isDefault)?.key
            if (defaultSourceGroupKey != null) groupIdByKey[defaultSourceGroupKey] = targetDefaultGroupId

            val existingFeeds = feedDao.queryAll(targetAccountId)
            val feedIdByKey = linkedMapOf<String, String>()
            snapshot.feeds.forEach { source ->
                val desiredId = targetAccountId.spacerDollar(source.key)
                val existing =
                    existingFeeds.firstOrNull { it.id == desiredId }
                        // 可移植 ID 不一致时，复用统一比较键避免大小写、默认端口、fragment、tracking 等产生重复 Feed。
                        ?: findFeedByComparisonUrl(existingFeeds, source.url)
                val targetFeedId = existing?.id ?: desiredId
                val targetGroupId =
                    if (source.groupIsDefault) {
                        targetDefaultGroupId
                    } else {
                        groupIdByKey[source.groupKey]
                            ?: error("同步快照中的 Feed 引用了不存在的 Group：${source.groupKey}")
                    }
                feedDao.insert(
                    Feed(
                        id = targetFeedId,
                        name = source.name,
                        icon = source.icon,
                        url = source.url.trim(),
                        groupId = targetGroupId,
                        accountId = targetAccountId,
                        isNotification = source.isNotification,
                        isFullContent = source.isFullContent,
                        isBrowser = source.isBrowser,
                        sourceType = SourceType.valueOf(source.sourceType),
                    )
                )
                feedIdByKey[source.key] = targetFeedId
                restoredFeeds++
            }

            val existingArticlesByFeed =
                feedIdByKey.values.distinct().associateWith { feedId ->
                    articleDao.queryAllByFeedId(targetAccountId, feedId)
                }
            val restoredArticleIdByKey = linkedMapOf<String, String>()
            snapshot.articles.chunked(200).forEach { chunk ->
                val mapped =
                    chunk.map { source ->
                        val targetFeedId =
                            feedIdByKey[source.feedKey]
                                ?: error("同步快照中的 Article 引用了不存在的 Feed：${source.feedKey}")
                        val desiredId = targetAccountId.spacerDollar(source.key)
                        val existingInFeed = existingArticlesByFeed[targetFeedId].orEmpty()
                        val existing =
                            existingInFeed.firstOrNull { it.id == desiredId }
                                ?: existingInFeed.firstOrNull { it.link == source.link }
                        val targetArticleId = existing?.id ?: desiredId
                        restoredArticleIdByKey[source.key] = targetArticleId
                        Article(
                            id = targetArticleId,
                            date = Date(source.dateEpochMillis),
                            title = source.title,
                            author = source.author,
                            rawDescription = source.rawDescription,
                            shortDescription = source.shortDescription,
                            fullContent = source.fullContent,
                            img = source.img,
                            link = source.link,
                            feedId = targetFeedId,
                            accountId = targetAccountId,
                            isUnread = source.isUnread,
                            isStarred = source.isStarred,
                            isReadLater = source.isReadLater,
                            updateAt = source.updatedAtEpochMillis?.let(::Date),
                        )
                    }
                mapped.forEach { articleDao.insert(it) }
                restoredArticles += mapped.size
            }

            snapshot.archivedArticles.groupBy(EditionSyncArchivedArticleSnapshot::feedKey).forEach { (feedKey, entries) ->
                val targetFeedId = feedIdByKey[feedKey] ?: return@forEach
                val existingLinks = feedDao.queryArchivedArticles(targetFeedId).mapTo(hashSetOf(), ArchivedArticle::link)
                val missing =
                    entries.asSequence()
                        .map(EditionSyncArchivedArticleSnapshot::link)
                        .filter(String::isNotBlank)
                        .distinct()
                        .filterNot(existingLinks::contains)
                        .map { link -> ArchivedArticle(feedId = targetFeedId, link = link) }
                        .toList()
                if (missing.isNotEmpty()) feedDao.insertArchivedArticles(missing)
                restoredArchivedArticles += missing.size
            }

            accountDao.update(
                targetAccount.copy(
                    updateAt = snapshot.sourceAccount.updatedAtEpochMillis?.let(::Date),
                    lastArticleId = snapshot.sourceAccount.lastArticleKey?.let { key -> restoredArticleIdByKey[key] },
                )
            )
        }

        return EditionSyncReadingRestoreResult(
            targetAccountId = targetAccountId,
            restoredGroups = restoredGroups,
            restoredFeeds = restoredFeeds,
            restoredArticles = restoredArticles,
            restoredArchivedArticles = restoredArchivedArticles,
        )
    }

    /** 去掉数据库主键中的本机 accountId 前缀，保留远端 ID / UUID 业务部分。 */
    private fun portableKey(id: String): String = id.dollarLast().also { require(it.isNotBlank()) { "同步 ID 为空" } }
}

/** 发送端修复旧数据库孤儿 Group 后的 Feed 归一化结果。 */
internal data class EditionSyncFeedGroupNormalization(
    val feeds: List<Feed>,
    val repairedGroupIds: Set<String>,
    val repairedFeedCount: Int,
)

/**
 * 将本机旧数据中指向不存在 Group 的 Feed 安全归回默认组。
 *
 * 这里只处理发送端已经存在的历史遗留数据；正常 Group 映射完全保持不变，接收端完整性校验不会放宽。
 */
internal fun normalizeLegacyFeedGroups(
    feeds: List<Feed>,
    groupById: Map<String, Group>,
    defaultGroupId: String,
): EditionSyncFeedGroupNormalization {
    val repairedGroupIds = linkedSetOf<String>()
    var repairedFeedCount = 0
    val normalized =
        feeds.map { feed ->
            if (feed.groupId == defaultGroupId || groupById.containsKey(feed.groupId)) {
                feed
            } else {
                repairedGroupIds += feed.groupId
                repairedFeedCount++
                feed.copy(groupId = defaultGroupId)
            }
        }
    return EditionSyncFeedGroupNormalization(
        feeds = normalized,
        repairedGroupIds = repairedGroupIds,
        repairedFeedCount = repairedFeedCount,
    )
}
