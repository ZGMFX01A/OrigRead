package me.ash.reader.llm.chat.data

import androidx.sqlite.db.SimpleSQLiteQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.ReaderContentPolicy
import me.ash.reader.infrastructure.rss.ReaderCacheHelper

/** 多文章选择器中的一条本地文章候选；正文只在用户明确附加后进入 LLM Context。 */
data class LlmArticleCandidate(
    val articleId: String,
    val title: String,
    val feedName: String,
    val link: String?,
    val publishedAt: Long,
)

/** 用户确认附加后才读取的单篇正文快照，避免候选列表把几十篇长正文同时塞入 Compose 状态。 */
data class LlmArticleCandidateSnapshot(
    val articleId: String,
    val title: String,
    val link: String?,
    val originalContent: String,
)

/**
 * P6.7.2 本地文章候选仓储。
 *
 * 该实现只存在于 LLM source set，并通过 Reader 的只读查询获取候选，不向共享 [ArticleDao] 增加
 * LLM 专属接口。项目当前没有“文章实际打开时间”的持久阅读历史，因此空查询明确返回“最近文章”
 * （按文章发布时间），不会把发布时间冒充“最近阅读”。
 */
@Singleton
class LlmArticleCandidateRepository @Inject constructor(
    private val readerDatabase: AndroidDatabase,
    private val accountService: AccountService,
    private val rssService: RssService,
    private val readerCacheHelper: ReaderCacheHelper,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** 最近文章用于打开选择器时快速给出候选；当前主文章会在 SQL 层排除。 */
    suspend fun recentArticles(currentArticleId: String, limit: Int = DEFAULT_RECENT_LIMIT): List<LlmArticleCandidate> =
        queryArticles(
            currentArticleId = currentArticleId,
            titleQuery = null,
            limit = limit.coerceIn(1, MAX_RESULT_LIMIT),
        )

    /** 只按标题搜索当前账户文章，避免把整篇正文 LIKE 扫描变成高成本操作。 */
    suspend fun searchArticles(
        currentArticleId: String,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<LlmArticleCandidate> {
        val normalized = query.trim()
        if (normalized.isBlank()) return recentArticles(currentArticleId)
        return queryArticles(
            currentArticleId = currentArticleId,
            titleQuery = normalized,
            limit = limit.coerceIn(1, MAX_RESULT_LIMIT),
        )
    }

    /** 用户明确选择候选后再加载这一篇正文；同时再次限制当前账户，防止账户切换后使用旧候选。 */
    suspend fun loadArticleSnapshot(articleId: String): LlmArticleCandidateSnapshot? =
        withContext(ioDispatcher) {
            val accountId = accountService.getCurrentAccountId()
            val normalizedId = articleId.trim()
            if (normalizedId.isBlank()) return@withContext null

            val articleWithFeed = rssService.get().findArticleById(normalizedId) ?: return@withContext null
            val article = articleWithFeed.article
            if (article.accountId != accountId) return@withContext null

            val readerContent =
                resolveRelatedArticleReaderContent(articleWithFeed, readerCacheHelper)
                    ?: return@withContext null

            LlmArticleCandidateSnapshot(
                articleId = article.id,
                title = article.title,
                link = article.link.trim().takeIf(String::isNotBlank),
                originalContent = readerContent,
            )
        }

    private suspend fun queryArticles(
        currentArticleId: String,
        titleQuery: String?,
        limit: Int,
    ): List<LlmArticleCandidate> =
        withContext(ioDispatcher) {
            val accountId = accountService.getCurrentAccountId()
            val normalizedCurrentId = currentArticleId.trim()
            val search = titleQuery?.trim()?.takeIf(String::isNotBlank)
            val sql =
                buildString {
                    append(
                        "SELECT a.id AS article_id, a.title AS article_title, a.link AS article_link, " +
                            "f.name AS feed_name, a.date AS published_at " +
                            "FROM article AS a INNER JOIN feed AS f ON f.id = a.feedId " +
                            "WHERE a.accountId = ? AND a.id != ? " +
                            "AND (TRIM(a.rawDescription) != '' OR f.sourceType = 'WEBSITE' OR f.isFullContent = 1) "
                    )
                    if (search != null) append("AND a.title LIKE ? ESCAPE '\\' COLLATE NOCASE ")
                    append("ORDER BY a.date DESC LIMIT ?")
                }
            val args =
                if (search == null) {
                    arrayOf<Any?>(accountId, normalizedCurrentId, limit)
                } else {
                    arrayOf<Any?>(accountId, normalizedCurrentId, "%${escapeLike(search)}%", limit)
                }
            val query = SimpleSQLiteQuery(sql, args)
            readerDatabase.openHelper.readableDatabase.query(query).use { cursor ->
                val articleIdColumn = cursor.getColumnIndexOrThrow("article_id")
                val titleColumn = cursor.getColumnIndexOrThrow("article_title")
                val linkColumn = cursor.getColumnIndexOrThrow("article_link")
                val feedNameColumn = cursor.getColumnIndexOrThrow("feed_name")
                val publishedAtColumn = cursor.getColumnIndexOrThrow("published_at")
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LlmArticleCandidate(
                                articleId = cursor.getString(articleIdColumn),
                                title = cursor.getString(titleColumn).orEmpty(),
                                feedName = cursor.getString(feedNameColumn).orEmpty(),
                                link =
                                    if (cursor.isNull(linkColumn)) null
                                    else cursor.getString(linkColumn)?.trim()?.takeIf(String::isNotBlank),
                                publishedAt = cursor.getLong(publishedAtColumn),
                            )
                        )
                    }
                }
            }
        }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val DEFAULT_RECENT_LIMIT = 24
        const val DEFAULT_SEARCH_LIMIT = 40
        const val MAX_RESULT_LIMIT = 80
    }
}

internal suspend fun resolveRelatedArticleReaderContent(
    articleWithFeed: ArticleWithFeed,
    readerCacheHelper: ReaderCacheHelper,
): String? {
    val article = articleWithFeed.article
    val feed = articleWithFeed.feed
    // Citation evidence must be frozen from the same content version that Reader will show.
    // For full-content / WEBSITE sources rawDescription is often only a list/RSS snapshot.
    val content =
        ReaderContentPolicy.embeddedFullContent(article, feed)
            ?: if (ReaderContentPolicy.requiresFetchedFullContent(feed)) {
                readerCacheHelper.readOrFetchFullContent(article).getOrNull()
            } else {
                article.rawDescription
            }
    return content?.trim()?.takeIf(String::isNotBlank)
}
