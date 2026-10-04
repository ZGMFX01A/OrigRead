package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity

/** Reader 固定视图逐条导出标准 ENTITY 字段，文章和映射不装入全库集合。 */
class SyncReaderSnapshotSource @Inject constructor(
    database: AndroidDatabase,
    private val rows: SyncSnapshotSqlRows,
) {
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val liveDatabase = database
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    data class Entity(val lane: String, val localId: String, val value: SyncGenesisProjectionEntity)
    data class Options(val syncSpaceId: String, val accountId: Int, val consume: suspend (Entity) -> Unit)

    /** 按父实体优先遍历，关联身份从数据库逐条查询；未完成 backfill 的记录显式失败。 */
    suspend fun forEach(options: Options) {
        val sql = database.openHelper.writableDatabase
        for (table in listOf("group", "feed", "article")) {
            rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql,
                "SELECT rowid FROM \"$table\" WHERE accountId=? ORDER BY rowid", arrayOf(options.accountId))) { id ->
                // 正文 Blob 在前置阶段已准备，Reader 导出只读取当前 wire 实际消费的列。
                val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, table, id), readerColumns.getValue(table))
                val localId = row.getValue("id").jsonPrimitive.content
                val mapping = mapping(options.syncSpaceId, table, localId)
                val fields = when (table) {
                    "group" -> JsonObject(mapOf("name" to row.getValue("name")))
                    "feed" -> feedFields(row, mapping(options.syncSpaceId, "group", row.getValue("groupId").jsonPrimitive.content))
                    else -> articleFields(row, mapping(options.syncSpaceId, "feed", row.getValue("feedId").jsonPrimitive.content))
                }
                options.consume(Entity(if (table == "article") "ARTICLE_STATE" else "LIBRARY", localId,
                    SyncGenesisProjectionEntity(table, mapping.syncId, mapping.generation, fields.toString())))
            }
        }
    }

    /** 生成跨端一致的 Feed 字段，布尔值不沿用 SQLite 的整数表示。 */
    private fun feedFields(row: JsonObject, group: SyncIdentityMappingEntity): JsonObject = buildJsonObject {
        put("groupSyncId", group.syncId)
        put("groupGeneration", group.generation)
        for (field in listOf("name", "icon", "url")) put(field, row.getValue(field))
        put("sourceType", row.getValue("sourceType").jsonPrimitive.content.lowercase())
        for (field in listOf("isNotification", "isFullContent", "isBrowser")) put(field, row.getValue(field).jsonPrimitive.long != 0L)
    }

    /** Android 列名映射为共享 Article 协议字段，正文只保留当前记录。 */
    private fun articleFields(row: JsonObject, feed: SyncIdentityMappingEntity): JsonObject = buildJsonObject {
        put("feedSyncId", feed.syncId)
        put("feedGeneration", feed.generation)
        val columns = mapOf("title" to "title", "author" to "author", "url" to "link", "publishedAt" to "date",
            "description" to "shortDescription", "contentHtml" to "rawDescription", "imageUrl" to "img")
        for ((field, column) in columns) put(field, row.getValue(column))
        for (field in listOf("isUnread", "isStarred", "isReadLater")) put(field, JsonPrimitive(row.getValue(field).jsonPrimitive.long != 0L))
    }

    /** 身份查找只返回当前实体，缺失属于捕获前置条件失败而不是可跳过的数据。 */
    private suspend fun mapping(space: String, type: String, localId: String): SyncIdentityMappingEntity =
        database.syncIdentityMappingDao().findByLocalId(space, type, localId)
            ?: error("Missing Genesis Reader mapping: $type/$localId")

    companion object {
        /** Reader 共享协议投影，不读取本机缓存、更新时间或另由 Blob 传输的文章全文。 */
        private val readerColumns = mapOf(
            "group" to listOf("id", "name"),
            "feed" to listOf("id", "groupId", "name", "icon", "url", "sourceType", "isNotification", "isFullContent", "isBrowser"),
            "article" to listOf("id", "feedId", "title", "author", "link", "date", "shortDescription", "rawDescription", "img", "isUnread", "isStarred", "isReadLater"),
        )
    }
}
