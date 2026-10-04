package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 完成核对只在 baseline、固定 tail 与正文义务全部结束后执行，不用候选条数推算 coverage。 */
class SyncSnapshotSourceCompletion @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
) {
    @Inject lateinit var validation: SyncPagedInstallValidation
    /** 仅编码不同的承诺字段需要精确读取原值，其余字段通过已验证的值摘要核对。 */
    private data class Effect(val field: SyncSnapshotFieldMetadata, val readValue: () -> String)
    private data class Prepared(val source: SyncOperationEnvelope, val key: String, val bytes: Long)
    /** 每个承诺字段有真实候选或高代删除证明，全部 effect 通过后才确认原操作。 */
    suspend fun complete(manifest: SyncPagedSnapshotManifest, now: Long) {
        for (lane in manifest.lanes) {
            val phase = "source-complete:v2:" + lane.replicationLaneId
            val progress = database.snapshotInstallProgressDao().find(manifest.snapshotBundleId, phase)
            check(progress == null || progress.rootHash == manifest.rootHash) { "SNAPSHOT_CONFLICT: source progress names another root" }
            val filter = SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, lane.replicationLaneId, "FIELD_VERSION")
            val sources = preparedSources(manifest, filter, progress?.cursor)
            for (batch in sourceBatches(sources)) {
                completeBatch(manifest, batch, now)
                SyncSnapshotTrace.add(SyncSnapshotTrace.Work(rows = batch.size.toLong(), bytes = batch.sumOf { it.bytes }))
            }
        }
    }

    /** 已完成来源直接从轻键续读；每字段及签名载荷中的全部承诺仍在写事务外核对。 */
    private fun preparedSources(manifest: SyncPagedSnapshotManifest, filter: SyncPagedSnapshotStore.RecordFilter,
        after: String?): Sequence<Prepared> = sequence {
        var current: Prepared? = null
        for (field in store.evidenceFields(filter, after)) {
            SyncSnapshotCancellation.checkpoint()
            val source = field.source ?: continue
            if (current != null && current.key != field.sourceKey) {
                requirePromisedEffects(manifest, current.source); yield(current); current = null
            }
            requireEffect(manifest, Effect(field.metadata) {
                val metadata = field.metadata
                val sourceFilter = SyncPagedSnapshotStore.RecordFilter(metadata.bundle, metadata.lane, "FIELD_VERSION")
                store.fieldRecord(sourceFilter, metadata.key, includeSource = false).value.getValue("valueJson").jsonPrimitive.content
            })
            if (current == null) current = Prepared(source, field.sourceKey, Json.encodeToString(source).toByteArray(Charsets.UTF_8).size.toLong())
        }
        current?.let { requirePromisedEffects(manifest, it.source); yield(it) }
    }

    /** 只保留当前有界来源 DTO，合法大单条独占写批次。 */
    private fun sourceBatches(sources: Sequence<Prepared>): Sequence<List<Prepared>> = sequence {
        var batch = mutableListOf<Prepared>()
        var bytes = 0L
        for (source in sources) {
            SyncSnapshotCancellation.checkpoint()
            if (batch.isNotEmpty() && bytes + source.bytes > BATCH_BYTES) { yield(batch.toList()); batch = mutableListOf(); bytes = 0L }
            batch.add(source); bytes += source.bytes
            if (batch.size == BATCH_ROWS || bytes >= BATCH_BYTES) { yield(batch.toList()); batch = mutableListOf(); bytes = 0L }
        }
        if (batch.isNotEmpty()) yield(batch.toList())
    }

    /** 因果覆盖仍须保留真实候选；仅更高代次映射或墓碑可明确消解旧字段义务。 */
    private fun requireEffect(manifest: SyncPagedSnapshotManifest, effect: Effect) {
        val field = effect.field
        val identity = arrayOf(manifest.syncSpaceId, field.identity.type, field.identity.id, field.identity.generation)
        val sql = database.openHelper.readableDatabase
        val resolved = sql.query("""SELECT EXISTS(SELECT 1 FROM sync_tombstone WHERE syncSpaceId=? AND entityType=? AND entitySyncId=? AND entityGeneration>=?)
            OR EXISTS(SELECT 1 FROM sync_identity_mapping WHERE syncSpaceId=? AND entityType=? AND syncId=? AND generation>?)""",
            identity + identity).use { check(it.moveToFirst()); it.getInt(0) != 0 }
        if (resolved) return
        val candidate = sql.query("""SELECT rowid,EXISTS(SELECT 1 FROM sync_field_version WHERE syncSpaceId=? AND entityType=?
            AND entitySyncId=? AND entityGeneration=? AND fieldId=?) FROM sync_field_candidate WHERE syncSpaceId=? AND entityType=?
            AND entitySyncId=? AND entityGeneration=? AND fieldId=? AND versionToken=?""",
            identity + arrayOf(field.fieldId) + identity + arrayOf(field.fieldId, field.token)).use {
            if (it.moveToFirst() && it.getInt(1) != 0) it.getLong(0) else null
        }
        check(candidate != null) { "SNAPSHOT_EFFECT_PENDING: ${field.key}" }
        val actual = SyncSnapshotTextChunks.read { position, count ->
            sql.query("SELECT substr(CAST(valueJson AS BLOB),?,?) FROM sync_field_candidate WHERE rowid=?",
                arrayOf(position, count, candidate)).use { check(it.moveToFirst()); it.getBlob(0) }
        }
        if (SyncOperationCanonicalizer.sha256Hex(actual) == field.valueDigest) return
        // 字节摘要不同不能跳过检查；精确原值保留合法的 JSON 属性顺序和空白差异。
        check(SyncOperationCanonicalizer.canonicalJson(actual) ==
            SyncOperationCanonicalizer.canonicalJson(effect.readValue())) { "SNAPSHOT_EFFECT_PENDING: ${field.key}" }
    }

    /** 状态与最后完整来源的逻辑回执同库提交，拒绝不能伪装成 Applied，修订失效整批回滚。 */
    private suspend fun completeBatch(manifest: SyncPagedSnapshotManifest, batch: List<Prepared>, now: Long) {
        val phase = "source-complete:v2:" + batch.last().source.replicationLaneId
        database.withTransaction {
            val started = System.nanoTime()
            validation.verifyWithinDatabaseTransaction(manifest)
            for (row in batch) {
                SyncSnapshotCancellation.checkpoint()
                val state = database.syncInboxDao().findState(row.source.operationId)
                check(state == "PENDING" || state == "APPLIED") { "SNAPSHOT_EFFECT_SOURCE_REJECTED: ${row.source.operationId}" }
                database.syncInboxDao().markApplied(row.source.operationId, now)
            }
            database.snapshotInstallProgressDao().save(SyncSnapshotInstallProgress(manifest.snapshotBundleId, phase,
                manifest.rootHash, batch.last().key))
            SyncSnapshotTrace.transactionHold(System.nanoTime() - started)
        }
    }

    /** 检查签名载荷承诺的全部字段，不能只凭快照恰好携带的一个 effect 宣告整条操作完成。 */
    private fun requirePromisedEffects(manifest: SyncPagedSnapshotManifest, source: SyncOperationEnvelope) {
        val payload = Json.parseToJsonElement(source.payloadJson).jsonObject
        val fields = when (source.operationType) {
            "FIELD_SET" -> mapOf(payload.getValue("field").jsonPrimitive.content to payload.getValue("value"))
            // 关系载荷与正式捕获器一致，全部签名关系字段都必须形成真实候选。
            "UPSERT", "RELATION_SET" -> ((payload["fields"] as? JsonObject) ?: payload).toMap()
            else -> error("SNAPSHOT_EFFECT_SOURCE_UNSUPPORTED: ${source.operationType}")
        }
        for ((field, value) in fields) {
            val encoded = SyncOperationCanonicalizer.canonicalValue(value)
            val metadata = SyncSnapshotFieldMetadata(bundle = manifest.snapshotBundleId, lane = source.replicationLaneId,
                key = "source-effect:${source.operationId}:$field", fieldId = field,
                token = SyncVersionToken.operation(source.actorIncarnationId, source.replicationLaneId, source.sequence),
                clock = source.logicalClock, causalContext = source.causalContextJson,
                valueDigest = SyncOperationCanonicalizer.sha256Hex(encoded), preferenceValue = "",
                identity = SyncSnapshotFieldIdentity(source.entityType, source.entitySyncId, source.entityGeneration))
            requireEffect(manifest, Effect(metadata) { encoded })
        }
    }

    companion object {
        /** 来源完成沿用标准 Reader 短写批次数量。 */
        private const val BATCH_ROWS = 256
        /** 实际解码来源载荷预算，超大单条单独提交。 */
        private const val BATCH_BYTES = 2L * 1024L * 1024L
    }
}
