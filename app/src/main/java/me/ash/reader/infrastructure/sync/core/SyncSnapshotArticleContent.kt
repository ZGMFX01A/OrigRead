package me.ash.reader.infrastructure.sync.core

import java.io.FileNotFoundException
import javax.inject.Inject
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.rss.ReaderCacheHelper

/** 快照中的抓取正文沿用正式业务 Blob，页面传输不替代正文可用性契约。 */
class SyncSnapshotArticleContent @Inject constructor(
    private val cache: ReaderCacheHelper,
    private val blobs: SyncLocalBlobStore,
    private val state: SyncBlobStateService,
) {
    @Inject lateinit var database: AndroidDatabase
    private val sourceDatabase get() = SyncFrozenSourceContext.database(database)
    data class Options(val accountId: Int, val space: String, val entity: SyncReaderSnapshotSource.Entity, val now: Long)
    data class Preparation(val accountId: Int, val space: String, val now: Long)

    /** 仅为旧缓存补齐引用；文件 hash 与发布都发生在固定 cut 屏障之外。 */
    suspend fun prepare(input: Preparation) {
        val sql = sourceDatabase.openHelper.writableDatabase
        val query = """SELECT a.id,m.syncId,m.generation FROM article a JOIN sync_identity_mapping m
            ON m.localId=a.id AND m.entityType='article' AND m.syncSpaceId=?
            WHERE a.accountId=? AND NOT EXISTS(SELECT 1 FROM sync_blob_reference r
              WHERE r.syncSpaceId=m.syncSpaceId AND r.replicationLaneId='ARTICLE_STATE'
              AND r.ownerEntityType='article' AND r.ownerEntitySyncId=m.syncId
              AND r.ownerEntityGeneration=m.generation AND r.referenceKind=?) ORDER BY a.id"""
        sql.query(query, arrayOf(input.space, input.accountId, SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND)).use { cursor ->
            while (cursor.moveToNext()) {
                val entity = SyncReaderSnapshotSource.Entity("ARTICLE_STATE", cursor.getString(0),
                    SyncGenesisProjectionEntity("article", cursor.getString(1), cursor.getLong(2), "{}"))
                prepareMissing(Options(input.accountId, input.space, entity, input.now))
            }
        }
    }

    /** 捕获只读取事务内已登记引用，缓存文件不参与屏障内工作。 */
    suspend fun attachRegistered(options: Options): SyncGenesisProjectionEntity {
        if (options.entity.value.entityType != "article") return options.entity.value
        val hash = registeredHash(options)
        val fields = Json.parseToJsonElement(options.entity.value.fieldsJson).jsonObject
        return options.entity.value.copy(fieldsJson = JsonObject(fields +
            ("fullContentHash" to (hash?.let(::JsonPrimitive) ?: JsonNull))).toString())
    }

    /** 文件准备不持锁；入库时重新核对身份与引用，不能覆盖并发写入的正文。 */
    private suspend fun prepareMissing(options: Options) {
        if (registeredHash(options) != null) return
        val result = cache.readFullContentForAccount(options.accountId, options.entity.localId)
        val failure = result.exceptionOrNull()
        if (failure != null && failure !is FileNotFoundException) throw failure
        val content = result.getOrNull()?.takeIf(String::isNotBlank) ?: return
        val reference = SyncBlobPayloadCodec.articleFullContentReference(content)
        blobs.putUtf8Text(reference, content)
        sourceDatabase.syncProjectionMutex.withLock {
            sourceDatabase.withTransaction {
                if (registeredHash(options) != null) return@withTransaction
                val mapping = sourceDatabase.syncIdentityMappingDao().findByLocalId(options.space, "article", options.entity.localId)
                if (mapping?.syncId != options.entity.value.entitySyncId || mapping.generation != options.entity.value.generation) return@withTransaction
                state.registerManifest(reference.manifest, SyncBlobAvailabilityState.READY, options.now)
                state.replaceOwnerReference(syncSpaceId = options.space, lane = "ARTICLE_STATE", ownerEntityType = "article",
                    ownerEntitySyncId = mapping.syncId, ownerEntityGeneration = mapping.generation,
                    referenceKind = reference.referenceKind, hash = reference.manifest.hash, now = options.now)
            }
        }
    }

    /** 本文对应的当前代次和引用种类必须一致，不能沿用另一代次正文。 */
    private suspend fun registeredHash(options: Options): String? = sourceDatabase.syncBlobDao().listReferencesForOwner(options.space,
        "ARTICLE_STATE", "article", options.entity.value.entitySyncId, options.entity.value.generation)
        .firstOrNull { it.referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND }?.hash

    /** 每次处理当前文章的缓存正文；缺少缓存允许沿用已有引用，其他读取错误直接暴露。 */
    suspend fun attach(options: Options): SyncGenesisProjectionEntity {
        val entity = options.entity.value
        if (entity.entityType != "article") return entity
        val result = cache.readFullContentForAccount(options.accountId, options.entity.localId)
        val failure = result.exceptionOrNull()
        if (failure != null && failure !is FileNotFoundException) throw failure
        val content = result.getOrNull()?.takeIf(String::isNotBlank)
        val hash = if (content == null) sourceDatabase.syncBlobDao().listReferencesForOwner(options.space,
            "ARTICLE_STATE", "article", entity.entitySyncId, entity.generation)
            .firstOrNull { it.referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND }?.hash
        else persist(options, content)
        val fields = Json.parseToJsonElement(entity.fieldsJson).jsonObject
        return entity.copy(fieldsJson = JsonObject(fields + ("fullContentHash" to (hash?.let(::JsonPrimitive) ?: JsonNull))).toString())
    }

    /** 验证并持久化当前正文，再将真实 hash 注册到同一实体代次。 */
    private suspend fun persist(options: Options, content: String): String {
        val reference = SyncBlobPayloadCodec.articleFullContentReference(content)
        blobs.putUtf8Text(reference, content)
        state.registerManifest(reference.manifest, SyncBlobAvailabilityState.READY, options.now)
        state.replaceOwnerReference(syncSpaceId = options.space, lane = "ARTICLE_STATE", ownerEntityType = "article",
            ownerEntitySyncId = options.entity.value.entitySyncId, ownerEntityGeneration = options.entity.value.generation,
            referenceKind = reference.referenceKind, hash = reference.manifest.hash, now = options.now)
        return reference.manifest.hash
    }
}
