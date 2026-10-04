package me.ash.reader.infrastructure.sync.core

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository

@Serializable
data class SyncAliasEdgePayloadV1(
    val targetEntityType: String,
    val leftSyncId: String,
    val leftGeneration: Long,
    val rightSyncId: String,
    val rightGeneration: Long,
)

@Entity(
    tableName = "sync_alias_edge",
    primaryKeys = [
        "syncSpaceId", "entityType", "leftSyncId", "leftGeneration",
        "rightSyncId", "rightGeneration",
    ],
)
data class SyncAliasEdgeEntity(
    val syncSpaceId: String,
    val entityType: String,
    val leftSyncId: String,
    val leftGeneration: Long,
    val rightSyncId: String,
    val rightGeneration: Long,
    val sourceOperationId: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "sync_entity_alias",
    primaryKeys = ["syncSpaceId", "entityType", "aliasSyncId", "generation"],
)
data class SyncEntityAliasEntity(
    val syncSpaceId: String,
    val entityType: String,
    val aliasSyncId: String,
    val canonicalSyncId: String,
    val generation: Long,
    val updatedAt: Long,
)

/** Local-only cache/blob eviction state. It is deliberately not a SyncOperation. */
@Entity(
    tableName = "sync_local_eviction",
    primaryKeys = ["syncSpaceId", "entityType", "entitySyncId", "entityGeneration", "resourceKind"],
)
data class SyncLocalEvictionEntity(
    val syncSpaceId: String,
    val entityType: String,
    val entitySyncId: String,
    val entityGeneration: Long,
    val resourceKind: String,
    val evictedAt: Long,
)

@Dao
interface SyncAliasDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEdgeIgnore(edge: SyncAliasEdgeEntity): Long

    @Query(
        """
        SELECT * FROM sync_alias_edge
        WHERE syncSpaceId = :syncSpaceId AND entityType = :entityType
        ORDER BY leftGeneration, leftSyncId, rightSyncId
        """,
    )
    suspend fun listEdges(syncSpaceId: String, entityType: String): List<SyncAliasEdgeEntity>

    @Query("SELECT * FROM sync_alias_edge WHERE syncSpaceId = :syncSpaceId ORDER BY entityType, leftGeneration, leftSyncId, rightSyncId")
    suspend fun listEdges(syncSpaceId: String): List<SyncAliasEdgeEntity>

    @Query("UPDATE sync_alias_edge SET sourceOperationId=NULL WHERE syncSpaceId=:syncSpaceId AND sourceOperationId=:operationId")
    suspend fun clearSourceOperation(syncSpaceId: String, operationId: String): Int

    @Query("DELETE FROM sync_entity_alias WHERE syncSpaceId = :syncSpaceId AND entityType = :entityType")
    suspend fun clearProjection(syncSpaceId: String, entityType: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjection(values: List<SyncEntityAliasEntity>)

    @Query(
        """
        SELECT * FROM sync_entity_alias
        WHERE syncSpaceId = :syncSpaceId AND entityType = :entityType
          AND aliasSyncId = :syncId AND generation = :generation
        LIMIT 1
        """,
    )
    suspend fun findProjection(
        syncSpaceId: String,
        entityType: String,
        syncId: String,
        generation: Long,
    ): SyncEntityAliasEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalEviction(value: SyncLocalEvictionEntity)

    @Query(
        """
        DELETE FROM sync_local_eviction
        WHERE syncSpaceId=:syncSpaceId AND entityType=:entityType AND entitySyncId=:entitySyncId
          AND entityGeneration=:generation AND resourceKind=:resourceKind
        """,
    )
    suspend fun clearLocalEviction(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
        resourceKind: String,
    ): Int

    @Query(
        """
        SELECT EXISTS(
          SELECT 1 FROM sync_local_eviction
          WHERE syncSpaceId=:syncSpaceId AND entityType=:entityType AND entitySyncId=:entitySyncId
            AND entityGeneration=:generation AND resourceKind=:resourceKind
        )
        """,
    )
    suspend fun isLocallyEvicted(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
        resourceKind: String,
    ): Boolean
}

@Singleton
class AndroidSyncAliasResolver @Inject constructor(
    database: AndroidDatabase,
) {
    private val liveDatabase = database
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val blobState = SyncBlobStateService(database)
    private var onFeedDeleted: suspend (String) -> Unit = {}

    fun withFeedDeleteCleanup(cleanup: suspend (String) -> Unit): AndroidSyncAliasResolver =
        apply { onFeedDeleted = cleanup }

    suspend fun applyEdge(
        syncSpaceId: String,
        payload: SyncAliasEdgePayloadV1,
        sourceOperationId: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        recordSnapshotEdge(SnapshotEdge(syncSpaceId, payload, sourceOperationId, now))
        reconcileDeleteWins(syncSpaceId, payload.targetEntityType, payload.leftSyncId, payload.leftGeneration, now)
    }

    data class SnapshotEdge(val space: String, val payload: SyncAliasEdgePayloadV1, val sourceOperationId: String?, val now: Long, val deferProjection: Boolean = false)

    /** 快照先恢复身份图，再逆序删除业务行；建图阶段不能提前触发父实体删除。 */
    suspend fun recordSnapshotEdge(options: SnapshotEdge) {
        val (syncSpaceId, payload, sourceOperationId, now) = options
        validate(payload)
        val (left, right) = normalizedEndpoints(payload)
        database.syncAliasDao().insertEdgeIgnore(
            SyncAliasEdgeEntity(
                syncSpaceId = syncSpaceId,
                entityType = payload.targetEntityType,
                leftSyncId = left.first,
                leftGeneration = left.second,
                rightSyncId = right.first,
                rightGeneration = right.second,
                sourceOperationId = sourceOperationId,
                createdAt = now,
            )
        )
        if (!options.deferProjection) rebuild(syncSpaceId, payload.targetEntityType, now)
    }

    suspend fun rebuild(syncSpaceId: String, entityType: String, now: Long = System.currentTimeMillis()) {
        val edges = database.syncAliasDao().listEdges(syncSpaceId, entityType)
        val projection = mutableListOf<SyncEntityAliasEntity>()
        edges.groupBy { it.leftGeneration }.forEach { (generation, generationEdges) ->
            val graph = mutableMapOf<String, MutableSet<String>>()
            generationEdges.forEach { edge ->
                graph.getOrPut(edge.leftSyncId) { linkedSetOf() }.add(edge.rightSyncId)
                graph.getOrPut(edge.rightSyncId) { linkedSetOf() }.add(edge.leftSyncId)
            }
            val visited = mutableSetOf<String>()
            graph.keys.sorted().forEach { seed ->
                if (!visited.add(seed)) return@forEach
                val component = mutableListOf(seed)
                val queue = ArrayDeque<String>().apply { add(seed) }
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    graph[current].orEmpty().sorted().forEach { next ->
                        if (visited.add(next)) {
                            component += next
                            queue.add(next)
                        }
                    }
                }
                val representative = component.minOrNull()!!
                component.forEach { member ->
                    projection += SyncEntityAliasEntity(
                        syncSpaceId,
                        entityType,
                        member,
                        representative,
                        generation,
                        now,
                    )
                }
            }
        }
        database.syncAliasDao().clearProjection(syncSpaceId, entityType)
        if (projection.isNotEmpty()) database.syncAliasDao().upsertProjection(projection)
    }

    suspend fun componentMembers(
        syncSpaceId: String,
        entityType: String,
        syncId: String,
        generation: Long,
    ): Set<String> {
        val sql = database.openHelper.writableDatabase
        return sql.query("""SELECT aliasSyncId FROM sync_entity_alias
            WHERE syncSpaceId=? AND entityType=? AND generation=? AND canonicalSyncId=(
              SELECT canonicalSyncId FROM sync_entity_alias WHERE syncSpaceId=? AND entityType=? AND generation=? AND aliasSyncId=?)""",
            arrayOf(syncSpaceId, entityType, generation, syncSpaceId, entityType, generation, syncId)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }.ifEmpty { setOf(syncId) }
        }
    }

    suspend fun resolveMapping(
        syncSpaceId: String,
        entityType: String,
        syncId: String,
        generation: Long,
    ): SyncIdentityMappingEntity? {
        database.syncIdentityMappingDao().findBySyncId(syncSpaceId, entityType, syncId)
            ?.takeIf { it.generation == generation }
            ?.let { return it }
        for (member in componentMembers(syncSpaceId, entityType, syncId, generation).sorted()) {
            val mapping = database.syncIdentityMappingDao().findBySyncId(syncSpaceId, entityType, member)
            if (mapping?.generation == generation) return mapping
        }
        return null
    }

    suspend fun applyGlobalDelete(
        operation: SyncOperationEntity,
        now: Long = System.currentTimeMillis(),
    ) {
        val token = SyncVersionToken.operation(
            operation.actorIncarnationId,
            operation.replicationLaneId,
            operation.sequence,
        )
        val members = componentMembers(
            operation.syncSpaceId,
            operation.entityType,
            operation.entitySyncId,
            operation.entityGeneration,
        )
        val binding = database.syncRuntimeDao().findBindingBySpace(operation.syncSpaceId)
        for (member in members) {
            database.syncInboxDao().upsertTombstone(
                SyncTombstoneEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = operation.entityType,
                    entitySyncId = member,
                    entityGeneration = operation.entityGeneration,
                    versionToken = token,
                    sourceOperationId = operation.operationId,
                    updatedAt = now,
                )
            )
            if (operation.entityType == SyncEntityType.ARTICLE.wireName) {
                blobState.removeOwnerReferences(
                    syncSpaceId = operation.syncSpaceId,
                    lane = SyncReplicationLane.ARTICLE_STATE.wireName,
                    ownerEntityType = SyncEntityType.ARTICLE.wireName,
                    ownerEntitySyncId = member,
                    ownerEntityGeneration = operation.entityGeneration,
                )
            }
            val mapping = database.syncIdentityMappingDao().findBySyncId(
                operation.syncSpaceId,
                operation.entityType,
                member,
            ) ?: continue
            if (mapping.generation != operation.entityGeneration || binding == null) continue
            ensureDeleteDependenciesCleared(
                binding.localAccountId,
                operation.entityType,
                mapping.localId,
            )
            when (operation.entityType) {
                SyncEntityType.ARTICLE.wireName -> deleteArticleIdentity(mapping.localId, binding.localAccountId)
                SyncEntityType.FEED.wireName -> {
                    val feed = database.feedDao().queryById(mapping.localId)
                    if (feed == null) {
                        onFeedDeleted(mapping.localId)
                    } else if (feed.accountId == binding.localAccountId) {
                        database.feedDao().delete(feed)
                        onFeedDeleted(mapping.localId)
                    }
                }
                SyncEntityType.GROUP.wireName -> database.groupDao().queryById(mapping.localId)
                    ?.takeIf { it.accountId == binding.localAccountId }?.let { database.groupDao().delete(it) }
            }
        }
    }

    fun payloadJson(payload: SyncAliasEdgePayloadV1): String =
        SyncOperationCanonicalizer.canonicalJson(json.encodeToString(payload))

    fun edgeSyncId(payload: SyncAliasEdgePayloadV1): String {
        validate(payload)
        val (left, right) = normalizedEndpoints(payload)
        val material = listOf(
            payload.targetEntityType,
            left.first,
            left.second.toString(),
            right.first,
            right.second.toString(),
        ).joinToString("\u0000")
        return "alias:v1:" + SyncOperationCanonicalizer.sha256Hex(material)
    }

    suspend fun reconcileDeleteWins(
        syncSpaceId: String,
        entityType: String,
        syncId: String,
        generation: Long,
        now: Long,
    ) {
        val members = componentMembers(syncSpaceId, entityType, syncId, generation)
        val witness = members.mapNotNull {
            database.syncInboxDao().findTombstone(syncSpaceId, entityType, it)
                ?.takeIf { tombstone -> tombstone.entityGeneration == generation }
        }.maxByOrNull { it.versionToken } ?: return
        val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
        for (member in members) {
            database.syncInboxDao().upsertTombstone(
                witness.copy(entitySyncId = member, updatedAt = now)
            )
            if (entityType == SyncEntityType.ARTICLE.wireName) {
                blobState.removeOwnerReferences(
                    syncSpaceId = syncSpaceId,
                    lane = SyncReplicationLane.ARTICLE_STATE.wireName,
                    ownerEntityType = SyncEntityType.ARTICLE.wireName,
                    ownerEntitySyncId = member,
                    ownerEntityGeneration = generation,
                )
            }
            val mapping = database.syncIdentityMappingDao().findBySyncId(syncSpaceId, entityType, member) ?: continue
            if (mapping.generation != generation || binding == null) continue
            ensureDeleteDependenciesCleared(
                binding.localAccountId,
                entityType,
                mapping.localId,
            )
            when (entityType) {
                SyncEntityType.ARTICLE.wireName -> deleteArticleIdentity(mapping.localId, binding.localAccountId)
                SyncEntityType.FEED.wireName -> {
                    val feed = database.feedDao().queryById(mapping.localId)
                    if (feed == null) {
                        onFeedDeleted(mapping.localId)
                    } else if (feed.accountId == binding.localAccountId) {
                        database.feedDao().delete(feed)
                        onFeedDeleted(mapping.localId)
                    }
                }
                SyncEntityType.GROUP.wireName -> database.groupDao().queryById(mapping.localId)
                    ?.takeIf { it.accountId == binding.localAccountId }?.let { database.groupDao().delete(it) }
            }
        }
    }

    /** 删除仅依赖本地身份和账户，不读取可能超过 CursorWindow 的文章正文。 */
    private fun deleteArticleIdentity(localId: String, accountId: Int) {
        database.openHelper.writableDatabase.execSQL("DELETE FROM article WHERE id=? AND accountId=?", arrayOf(localId, accountId))
    }

    private suspend fun ensureDeleteDependenciesCleared(
        localAccountId: Int,
        entityType: String,
        localId: String,
    ) {
        when (entityType) {
            SyncEntityType.FEED.wireName -> {
                if (database.articleDao().countByFeedId(localAccountId, localId) > 0) {
                    throw SyncApplyDeferredException(
                        "Alias Feed delete is waiting for dependent Article deletes"
                    )
                }
            }
            SyncEntityType.GROUP.wireName -> {
                if (database.feedDao().queryByGroupId(localAccountId, localId).isNotEmpty()) {
                    throw SyncApplyDeferredException(
                        "Alias Group delete is waiting for dependent Feed deletes"
                    )
                }
            }
        }
    }

    private fun validate(payload: SyncAliasEdgePayloadV1) {
        require(payload.targetEntityType != SyncEntityType.ALIAS_EDGE.wireName) { "Alias Edge cannot alias Alias Edge" }
        require(payload.leftSyncId.isNotBlank() && payload.rightSyncId.isNotBlank()) { "Alias endpoints must not be blank" }
        require(payload.leftSyncId != payload.rightSyncId) { "Alias endpoints must be distinct" }
        require(payload.leftGeneration >= 0 && payload.leftGeneration == payload.rightGeneration) {
            "Alias Edge must stay within one entity generation"
        }
    }

    private fun normalizedEndpoints(payload: SyncAliasEdgePayloadV1): Pair<Pair<String, Long>, Pair<String, Long>> {
        val left = payload.leftSyncId to payload.leftGeneration
        val right = payload.rightSyncId to payload.rightGeneration
        return if (left.first < right.first) left to right else right to left
    }
}

@Singleton
class SyncLocalEvictionService @Inject constructor(
    private val database: AndroidDatabase,
) {
    suspend fun markEvicted(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
        resourceKind: String,
        now: Long = System.currentTimeMillis(),
    ) {
        require(resourceKind.isNotBlank())
        database.syncAliasDao().upsertLocalEviction(
            SyncLocalEvictionEntity(syncSpaceId, entityType, entitySyncId, generation, resourceKind, now)
        )
    }

    suspend fun clearEvicted(syncSpaceId: String, entityType: String, entitySyncId: String, generation: Long, resourceKind: String) =
        database.syncAliasDao().clearLocalEviction(syncSpaceId, entityType, entitySyncId, generation, resourceKind)

    suspend fun isEvicted(syncSpaceId: String, entityType: String, entitySyncId: String, generation: Long, resourceKind: String): Boolean =
        database.syncAliasDao().isLocallyEvicted(syncSpaceId, entityType, entitySyncId, generation, resourceKind)
}

@Singleton
class SyncAliasMutationCapture @Inject constructor(
    private val database: AndroidDatabase,
    private val coordinator: SyncRuntimeCoordinator,
    private val allocator: SyncOutboxAllocator,
    private val articleFilterRepository: ArticleFilterRepository,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    private val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
) {
    private val resolver =
        AndroidSyncAliasResolver(database).withFeedDeleteCleanup { localFeedId ->
            articleFilterRepository.deleteByFeed(localFeedId)
            websiteParsePreferenceRepository.delete(localFeedId)
            rssHubSubscriptionRepository.replaceSyncSource(localFeedId, null)
        }

    suspend fun capture(
        accountId: Int,
        payload: SyncAliasEdgePayloadV1,
        now: Long = System.currentTimeMillis(),
    ): SyncOutboxEntity? {
        val edgeSyncId = resolver.edgeSyncId(payload)

        suspend fun attempt(): SyncOutboxEntity? =
            coordinator.withLocalMutation(accountId) { context ->
                if (context == null) return@withLocalMutation null
                database.withTransaction {
                    resolver.applyEdge(context.syncSpaceId, payload, sourceOperationId = null, now = now)
                    allocator.allocate(
                        dao = database.syncOutboxDao(),
                        context = context,
                        lane = SyncReplicationLane.CORE_META,
                        draft = SyncOutboxDraft(
                            entityType = SyncEntityType.ALIAS_EDGE.wireName,
                            entitySyncId = edgeSyncId,
                            entityGeneration = payload.leftGeneration,
                            mutationType = SyncMutationType.UPSERT,
                            payloadJson = resolver.payloadJson(payload),
                        ),
                        now = now,
                    )
                }
            }

        return try {
            attempt()
        } catch (_: SyncActorRollbackDetectedException) {
            coordinator.rotateActor(accountId, "alias-edge-outbox-witness-mismatch", now)
            attempt()
        }
    }
}
