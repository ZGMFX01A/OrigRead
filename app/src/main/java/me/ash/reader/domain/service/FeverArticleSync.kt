package me.ash.reader.domain.service

import java.util.Date
import me.ash.reader.infrastructure.exception.FeverAPIException
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.infrastructure.rss.provider.fever.FeverAPI
import me.ash.reader.infrastructure.rss.provider.fever.FeverDTO
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar

/** Fever 既有单页数量，不足一页时结束增量获取。 */
private const val FEVER_PAGE_SIZE = 50
private const val MAX_FEVER_PAGES = 1000
/** 远端秒级时间与文章摘要显示长度。 */
private const val MILLIS_PER_SECOND = 1_000L
private const val ARTICLE_PREVIEW_LENGTH = 280

internal data class FeverArticleRequest(
    val api: FeverAPI, val accountId: Int, val lastArticleId: String, val fetchedAt: Date,
)
internal data class FeverArticles(val articles: List<Article>, val lastSeenId: String)

/** 沿用增量起点与短页结束条件，字段缺失或异常分页必须中止同步。 */
internal suspend fun FeverRssService.fetchFeverArticles(request: FeverArticleRequest): FeverArticles {
    val articles = mutableListOf<Article>()
    var lastSeen = request.lastArticleId
    val seenCursors = hashSetOf(lastSeen)
    var pageCount = 0
    while (true) {
        val items = request.api.getItemsSince(lastSeen).items
            ?: throw FeverAPIException("Fever items 缺失")
        if (items.isEmpty()) break
        val nextId = items.lastOrNull()?.id?.takeIf { it.isNotBlank() }
        if (items.size >= FEVER_PAGE_SIZE &&
            (nextId == null || !seenCursors.add(nextId) || ++pageCount >= MAX_FEVER_PAGES)
        ) {
            throw FeverAPIException("Fever 分页游标异常，已中止同步")
        }
        articles.addAll(items.map { mapFeverArticle(it, request) })
        lastSeen = requireNotNull(nextId) { "Fever item id 缺失" }
        if (items.size < FEVER_PAGE_SIZE) break
    }
    return FeverArticles(articles, lastSeen)
}

/** 转换只使用请求上下文中的账户和抓取时间。 */
private fun FeverRssService.mapFeverArticle(item: FeverDTO.Item, request: FeverArticleRequest): Article =
    Article(
        id = request.accountId.spacerDollar(requireNotNull(item.id)),
        date = item.created_on_time?.let { Date(it * MILLIS_PER_SECOND) }
            ?.takeIf { !it.isFuture(request.fetchedAt) } ?: request.fetchedAt,
        title = item.title.decodeHTML() ?: context.getString(R.string.empty),
        author = item.author, rawDescription = item.html ?: "",
        shortDescription = Readability.parseToText(item.html, item.url).take(ARTICLE_PREVIEW_LENGTH),
        img = rssHelper.findThumbnail(item.html), link = item.url ?: "",
        feedId = request.accountId.spacerDollar(requireNotNull(item.feed_id)),
        accountId = request.accountId, isUnread = (item.is_read ?: 0) <= 0, isStarred = (item.is_saved ?: 0) > 0,
        updateAt = request.fetchedAt,
    )

/** 内容保存后才通知，通知来源集合只查询本次任务账户。 */
internal suspend fun FeverRssService.saveFeverArticles(accountId: Int, articles: List<Article>) {
    if (articles.isEmpty()) return
    articleDao.insert(*articles.toTypedArray())
    val enabledFeeds = feedDao.queryNotificationEnabled(accountId).associateBy { it.id }
    articles.filter { it.isUnread && it.feedId in enabledFeeds }.groupBy { it.feedId }.forEach { (id, items) ->
        notificationHelper.notify(requireNotNull(enabledFeeds[id]), items)
    }
}
