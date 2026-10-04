package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository

/** 元数据逐条恢复，清空引用只能在 lane 开始发生一次。 */
class SyncPagedMetadataRestore @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val blobs: SyncBlobStateService,
) {
    @Inject lateinit var aliases: AndroidSyncAliasResolver
    @Inject lateinit var filters: ArticleFilterRepository
    @Inject lateinit var preferences: WebsiteParsePreferenceRepository
    @Inject lateinit var subscriptions: RssHubSubscriptionRepository
    @Inject lateinit var extensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension>
    data class Options(val manifest: SyncPagedSnapshotManifest, val lane: String, val now: Long)
    private val json = Json { ignoreUnknownKeys = true }

    /** 实际 bytes 的 READY 状态不由 Snapshot 清单决定。 */
    suspend fun restoreBlobs(options: Options) {
        val space = options.manifest.syncSpaceId
        val filter = SyncPagedSnapshotStore.RecordFilter(options.manifest.snapshotBundleId, options.lane)
        projectionWrite { blobs.clearMaterializedLaneReferences(space, options.lane) }
        for (record in store.records(filter.copy(kind = "BLOB_MANIFEST"))) {
            val manifest = json.decodeFromString<SyncBlobManifestWire>(record.value.toString())
            projectionWrite { blobs.registerManifest(manifest, SyncBlobAvailabilityState.BLOB_MISSING, options.now) }
        }
        for (record in store.records(filter.copy(kind = "BLOB_REFERENCE"))) {
            val ref = json.decodeFromString<SyncBlobReferenceWire>(record.value.toString())
            projectionWrite { blobs.addReference(syncSpaceId = space, lane = ref.replicationLaneId, ownerEntityType = ref.ownerEntityType,
                ownerEntitySyncId = ref.ownerEntitySyncId, ownerEntityGeneration = ref.ownerEntityGeneration,
                referenceKind = ref.referenceKind, hash = ref.hash, now = options.now) }
        }
    }

    /** 引用变更与正文到达共用短投影屏障，不把 JSON 或文件读取带进 Reader 事务。 */
    private suspend fun projectionWrite(action: suspend () -> Unit) = database.syncProjectionMutex.withLock {
        database.withTransaction { action() }
    }

    /** 实体安装前仅恢复别名图，删除在子实体清理之后执行。 */
    suspend fun restoreAliasGraph(options: Options) {
        val types = linkedSetOf<String>()
        for (record in store.records(SyncPagedSnapshotStore.RecordFilter(options.manifest.snapshotBundleId, options.lane, "ALIAS_EDGE"))) {
            val edge = json.decodeFromString<SyncAliasEdgePayloadV1>(record.value.toString())
            database.syncProjectionMutex.withLock {
                aliases.recordSnapshotEdge(AndroidSyncAliasResolver.SnapshotEdge(options.manifest.syncSpaceId,
                    edge, null, options.now, deferProjection = true))
            }
            types.add(record.value.getValue("targetEntityType").jsonPrimitive.content)
        }
        for (type in types) database.syncProjectionMutex.withLock { aliases.rebuild(options.manifest.syncSpaceId, type, options.now) }
    }

    /** 别名图沿用正式 delete-wins 语义，Feed 删除同步清理对应外部配置。 */
    suspend fun restoreAliases(options: Options) {
        aliases.withFeedDeleteCleanup { id -> cleanupFeed(id) }
        for (record in store.records(SyncPagedSnapshotStore.RecordFilter(options.manifest.snapshotBundleId, options.lane, "ALIAS_EDGE"))) {
            val edge = json.decodeFromString<SyncAliasEdgePayloadV1>(record.value.toString())
            database.syncProjectionMutex.withLock {
                aliases.reconcileDeleteWins(options.manifest.syncSpaceId, edge.targetEntityType, edge.leftSyncId, edge.leftGeneration, options.now)
            }
        }
    }

    /** 外层按反向实体依赖执行，单条删除摘要保留原始代次和 token。 */
    suspend fun restoreTombstones(options: Options, type: String) {
        val space = options.manifest.syncSpaceId
        for (record in store.records(SyncPagedSnapshotStore.RecordFilter(options.manifest.snapshotBundleId, options.lane, "TOMBSTONE", type))) {
            val value = record.value
            val id = value.getValue("entitySyncId").jsonPrimitive.content
            val generation = value.getValue("generation").jsonPrimitive.long
            val token = value.getValue("versionToken").jsonPrimitive.content
            val deletedAt = value.getValue("deletedAt").jsonPrimitive.long
            val extension = extensions.firstOrNull { it.owns(type) }
            database.syncProjectionMutex.withLock {
                database.withTransaction {
                    database.syncInboxDao().upsertTombstone(SyncTombstoneEntity(space, type, id, generation, token, null, deletedAt))
                    if (extension == null) deleteReader(Delete(space, type, id, generation))
                }
                if (extension != null) extension.materializeSnapshotTombstone(syncSpaceId = space, entityType = type,
                    entitySyncId = id, generation = generation, versionToken = token, deletedAt = deletedAt)
                aliases.reconcileDeleteWins(syncSpaceId = space, entityType = type, syncId = id, generation = generation, now = options.now)
            }
        }
    }

    private data class Delete(val space: String, val type: String, val id: String, val generation: Long)

    /** 删除前验证子实体已经清理，不能依赖 FK cascade 隐式丢掉未声明的当前代次。 */
    private suspend fun deleteReader(options: Delete) {
        if (options.type !in setOf("article", "feed", "group")) return
        val binding = checkNotNull(database.syncRuntimeDao().findBindingBySpace(options.space))
        val mapping = database.syncIdentityMappingDao().findBySyncId(options.space, options.type, options.id) ?: return
        if (mapping.generation > options.generation) return
        val sql = database.openHelper.writableDatabase
        val dependency = when (options.type) {
            "feed" -> "SELECT 1 FROM article WHERE accountId=? AND feedId=? LIMIT 1"
            "group" -> "SELECT 1 FROM feed WHERE accountId=? AND groupId=? LIMIT 1"
            else -> null
        }
        if (dependency != null) sql.query(dependency, arrayOf(binding.localAccountId, mapping.localId)).use {
            check(!it.moveToFirst()) { "REBASE_UNSAFE: Snapshot deletion still has live child entities" }
        }
        sql.execSQL("DELETE FROM \"${options.type}\" WHERE id=? AND accountId=?", arrayOf(mapping.localId, binding.localAccountId))
        if (options.type == "feed") cleanupFeed(mapping.localId)
        if (options.type == "article") blobs.removeOwnerReferences(syncSpaceId = options.space, lane = "ARTICLE_STATE",
            ownerEntityType = options.type, ownerEntitySyncId = options.id, ownerEntityGeneration = options.generation)
        if (mapping.generation < options.generation) database.syncIdentityMappingDao().update(mapping.copy(generation = options.generation))
    }

    /** Feed 的用户配置与 Feed 物化删除保持一致。 */
    private fun cleanupFeed(id: String) {
        filters.deleteByFeed(id)
        preferences.delete(id)
        subscriptions.replaceSyncSource(id, null)
    }
}
