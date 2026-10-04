package me.ash.reader.infrastructure.sync.core

import java.util.Date
import javax.inject.Inject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 同步投影只持有当前文章，大文本按字节块读取，避免 Room 整行 CursorWindow 上限。 */
class SyncBoundedArticleRows @Inject constructor(private val database: AndroidDatabase, private val rows: SyncSnapshotSqlRows) {
    /** 与业务 DAO 返回相同真实模型，缺失文章/父 Feed 明确暴露。 */
    suspend fun queryById(id: String): ArticleWithFeed? {
        val sql = database.openHelper.writableDatabase
        val rowId = sql.query("SELECT rowid FROM article WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return null
        val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "article", rowId))
        val article = article(row)
        val feed = checkNotNull(database.feedDao().queryById(article.feedId)) { "Article parent Feed is missing" }
        return ArticleWithFeed(article, feed)
    }

    /** 保留本地非共享字段，字段更新或回滚不会清空旧 fullContent/updateAt。 */
    private fun article(row: JsonObject): Article = Article(id = text(row, "id"), date = Date(number(row, "date")),
        title = text(row, "title"), author = optionalText(row, "author"), rawDescription = text(row, "rawDescription"),
        shortDescription = text(row, "shortDescription"), fullContent = optionalText(row, "fullContent"), img = optionalText(row, "img"),
        link = text(row, "link"), feedId = text(row, "feedId"), accountId = number(row, "accountId").toInt(),
        isUnread = number(row, "isUnread") != 0L, isStarred = number(row, "isStarred") != 0L, isReadLater = number(row, "isReadLater") != 0L,
        updateAt = row["updateAt"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long?.let(::Date))

    private fun text(row: JsonObject, field: String): String = row.getValue(field).jsonPrimitive.content
    private fun number(row: JsonObject, field: String): Long = row.getValue(field).jsonPrimitive.long
    private fun optionalText(row: JsonObject, field: String): String? = row[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
}
