package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity

/** Reader 实体逐行安装，大文章只用绑定参数写入，绝不整行读回 CursorWindow。 */
class SyncPagedReaderRestore @Inject constructor(
    private val database: AndroidDatabase,
    private val identities: SyncPagedEntityIdentity,
    private val applier: AndroidSyncBusinessApplier,
) {
    data class Options(val accountId: Int, val space: String, val value: JsonObject, val now: Long)

    /** 父实体由外层按 group/feed/article 顺序安装。 */
    suspend fun materialize(options: Options) {
        when (options.value.getValue("entityType").jsonPrimitive.content) {
            "group" -> group(options)
            "feed" -> feed(options)
            "article" -> article(options)
            else -> error("SNAPSHOT_CORRUPTED: unsupported Reader entity")
        }
    }

    /** Group 没有设备私有字段，保留本地身份后覆盖共享名称。 */
    private suspend fun group(options: Options) {
        val fields = options.value.getValue("fields").jsonObject
        val mapping = identities.materialize(SyncPagedEntityIdentity.Options(options.space, options.value, options.now))
        checkAccount(options, "group", mapping.localId)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("INSERT OR IGNORE INTO \"group\"(id,name,accountId) VALUES(?,?,?)",
            arrayOf(mapping.localId, text(fields, "name"), options.accountId))
        sql.execSQL("UPDATE \"group\" SET name=? WHERE id=? AND accountId=?",
            arrayOf(text(fields, "name"), mapping.localId, options.accountId))
    }

    /** Feed 的父代次和 URL 共同决定 canonical 身份，设备本地抓取状态不被覆盖。 */
    private suspend fun feed(options: Options) {
        val fields = options.value.getValue("fields").jsonObject
        val parent = identities.parent(SyncPagedEntityIdentity.Parent(options.space, fields, "group"))
        val sourceType = SourceType.valueOf(text(fields, "sourceType").uppercase())
        val mapping = identities.materialize(SyncPagedEntityIdentity.Options(options.space, options.value, options.now,
            canonicalKey = SyncCanonicalIdentity.feedCandidateKey(sourceType, text(fields, "url"))))
        checkAccount(options, "feed", mapping.localId)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""INSERT OR IGNORE INTO feed(id,name,url,groupId,accountId,isNotification,isFullContent,isBrowser,sourceType)
            VALUES(?,?,?,?,?,?,?,?,?)""", arrayOf(mapping.localId, text(fields, "name"), text(fields, "url"),
            parent.localId, options.accountId, bool(fields, "isNotification"), bool(fields, "isFullContent"), bool(fields, "isBrowser"), sourceType.name))
        sql.execSQL("UPDATE feed SET name=?,url=?,groupId=?,icon=?,isNotification=?,isFullContent=?,isBrowser=?,sourceType=? WHERE id=? AND accountId=?",
            arrayOf(text(fields, "name"), text(fields, "url"), parent.localId, nullableText(fields, "icon"),
                bool(fields, "isNotification"), bool(fields, "isFullContent"), bool(fields, "isBrowser"), sourceType.name, mapping.localId, options.accountId))
    }

    /** 保留本地 updateAt/fullContent，只写协议共享正文及阅读状态。 */
    private suspend fun article(options: Options) {
        val fields = options.value.getValue("fields").jsonObject
        val parent = identities.parent(SyncPagedEntityIdentity.Parent(options.space, fields, "feed"))
        val feed = database.feedDao().queryById(parent.localId)
            ?: throw SnapshotDependencyMissingError("Snapshot article has no materialized Feed")
        val mapping = identities.materialize(SyncPagedEntityIdentity.Options(options.space, options.value, options.now,
            canonicalKey = SyncCanonicalIdentity.articleCandidateKey(SyncCanonicalIdentity.feedCandidateKey(feed.sourceType, feed.url), text(fields, "url"))))
        checkAccount(options, "article", mapping.localId)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""INSERT OR IGNORE INTO article(id,date,title,rawDescription,shortDescription,link,feedId,accountId,isUnread,isStarred,isReadLater)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)""", arrayOf(mapping.localId, fields.getValue("publishedAt").jsonPrimitive.long,
            text(fields, "title"), text(fields, "contentHtml"), text(fields, "description"), text(fields, "url"),
            parent.localId, options.accountId, bool(fields, "isUnread"), bool(fields, "isStarred"), bool(fields, "isReadLater")))
        sql.execSQL("""UPDATE article SET date=?,title=?,author=?,rawDescription=?,shortDescription=?,img=?,link=?,feedId=?,
            isUnread=?,isStarred=?,isReadLater=? WHERE id=? AND accountId=?""", arrayOf(fields.getValue("publishedAt").jsonPrimitive.long,
            text(fields, "title"), nullableText(fields, "author"), text(fields, "contentHtml"), text(fields, "description"),
            nullableText(fields, "imageUrl"), text(fields, "url"), parent.localId, bool(fields, "isUnread"), bool(fields, "isStarred"),
            bool(fields, "isReadLater"), mapping.localId, options.accountId))
    }

    /** 文章大正文属于文件阶段，禁止继承 Reader 写事务。 */
    suspend fun materializeAttachment(options: Options) {
        check(!database.inTransaction()) { "Snapshot article attachment cannot inherit Reader transaction" }
        val fields = options.value.getValue("fields").jsonObject
        val id = options.value.getValue("entitySyncId").jsonPrimitive.content
        val mapping = checkNotNull(database.syncIdentityMappingDao().findBySyncId(options.space, "article", id))
        nullableText(fields, "fullContentHash")?.let { hash ->
            applier.materializeSnapshotArticleFullContent(syncSpaceId = options.space, entitySyncId = mapping.syncId,
                generation = mapping.generation, localAccountId = options.accountId, localArticleId = mapping.localId, hash = hash)
        }
    }

    /** 只读取 accountId 校验归属，不读取可能超过 CursorWindow 的正文。 */
    private fun checkAccount(options: Options, table: String, id: String) {
        database.openHelper.writableDatabase.query("SELECT accountId FROM \"$table\" WHERE id=?", arrayOf(id)).use {
            check(!it.moveToFirst() || it.getInt(0) == options.accountId) { "Snapshot entity belongs to another account" }
        }
    }

    private fun text(fields: JsonObject, name: String): String = fields.getValue(name).jsonPrimitive.content
    private fun nullableText(fields: JsonObject, name: String): String? = fields[name]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    private fun bool(fields: JsonObject, name: String): Int = if (fields.getValue(name).jsonPrimitive.boolean) 1 else 0
}
