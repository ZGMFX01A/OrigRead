package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import androidx.room.withTransaction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 全部候选保留后才计算字段 winner，业务安装事务由调用者持有。 */
class SyncPagedFieldRestore @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val resolver: SyncPagedFieldResolver,
) {
    @Inject lateinit var index: SyncSnapshotFieldIndex
    @Inject lateinit var operationEvidence: SyncPagedOperationEvidence
    @Inject lateinit var batches: SyncSnapshotReaderBatches
    data class Options(val manifest: SyncPagedSnapshotManifest, val lane: String, val now: Long) {
        val bundleId get() = manifest.snapshotBundleId
        val space get() = manifest.syncSpaceId
    }

    /** 每次恢复一个候选/字段，因果元数据不能只跟随当前传输页中的 winner。 */
    suspend fun restore(options: Options) {
        val filter = SyncPagedSnapshotStore.RecordFilter(options.bundleId, options.lane, "FIELD_VERSION")
        batches.apply(SyncSnapshotReaderBatches.Phase(options.manifest, "fields:${options.lane}"), store.compactRecords(filter)) { record ->
            database.syncInboxDao().upsertFieldCandidate(SyncFieldCandidateEntity(restoredVersion(options, record)))
        }
        index.forEach(SyncSnapshotFieldIndex.Options(options.bundleId, options.lane) { field ->
            val records = filter.copy(entityType = field.type, entitySyncId = field.id, generation = field.generation, fieldId = field.field)
            val winner = resolver.resolve(SyncPagedFieldResolver.Options(records, field.field, includeSource = false))
            database.withTransaction { database.syncInboxDao().upsertFieldVersion(restoredVersion(options, winner)) }
        })
    }

    /** 原操作和候选同事务恢复；sourceOperationId 不能在快照安装时清空。 */
    private suspend fun restoredVersion(options: Options, record: SyncSnapshotRecord): SyncFieldVersionEntity {
        val dot = SyncVersionToken.parseOperationDot(record.value.getValue("versionToken").jsonPrimitive.content)
        val source = dot?.let {
            database.openHelper.readableDatabase.query("SELECT operationId FROM sync_operation_log WHERE syncSpaceId=? AND actorIncarnationId=? AND replicationLaneId=? AND sequence=?",
                arrayOf(options.space, it.actorIncarnationId, it.replicationLaneId, it.sequence)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
        return version(options, record).copy(sourceOperationId = source)
    }

    /** 来源 operation ID 不由 Snapshot 猜测，候选本身保留真实 token、时钟和 causal context。 */
    private fun version(options: Options, record: SyncSnapshotRecord): SyncFieldVersionEntity {
        val value = record.value
        return SyncFieldVersionEntity(syncSpaceId = options.space, entityType = value.getValue("entityType").jsonPrimitive.content,
            entitySyncId = value.getValue("entitySyncId").jsonPrimitive.content,
            entityGeneration = value.getValue("entityGeneration").jsonPrimitive.long, fieldId = value.getValue("fieldId").jsonPrimitive.content,
            versionToken = value.getValue("versionToken").jsonPrimitive.content, valueJson = value.getValue("valueJson").jsonPrimitive.content,
            causalContextJson = value["causalContextJson"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            logicalClock = value["logicalClock"]?.jsonPrimitive?.longOrNull, updatedAt = options.now)
    }
}
