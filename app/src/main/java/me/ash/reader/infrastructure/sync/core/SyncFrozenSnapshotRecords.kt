package me.ash.reader.infrastructure.sync.core

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 私有 cut 的原始记录可恢复落盘；规范化和摘要不占 Genesis 写屏障。 */
internal object SyncFrozenSnapshotRecords {
    data class Input(val database: SQLiteDatabase, val bundle: String, val lane: String,
        val record: SyncSnapshotRecord, val readRecord: (Long) -> String, val sources: SyncSnapshotSourcePool, val derived: SyncSnapshotDerivedIndex)
    data class Target(val database: SQLiteDatabase, val bundle: String,
        val sources: SyncSnapshotSourcePool, val derived: SyncSnapshotDerivedIndex)
    private data class CapturePrepared(val columns: ContentValues, val source: SyncSnapshotSourcePool.Prepared, val facts: SyncSnapshotDerivedFacts.Prepared)
    /** 私有规范化按行数和字节双预算提交，单条大对象独立提交且保留完整数据。 */
    private const val NORMALIZE_BATCH_ROWS = 256
    /** 对应方案初始解码预算，不是截断正文的上限。 */
    private const val NORMALIZE_BATCH_BYTES = 2 * 1024 * 1024
    /** 断点绑定 P3 的字段和关系派生格式，不接管旧私有游标。 */
    private const val NORMALIZATION_PHASE = "normalize:v2"
    private val json = Json { encodeDefaults = true }

    /** 复制当前固定事实，只有重复键才比较规范值，绝不把冲突记录静默去重。 */
    fun capture(input: Input): Boolean {
        val state = input.database.rawQuery("SELECT state FROM sync_paged_snapshot WHERE snapshot_bundle_id=?", arrayOf(input.bundle)).use {
            check(it.moveToFirst()) { "SNAPSHOT_CONFLICT: capture state is missing" }; it.getString(0)
        }
        check(state == "CAPTURING") { "SNAPSHOT_CONFLICT: raw capture requires private staging" }
        SyncSnapshotRecordValidation.validate(input.record)
        SyncSnapshotRecordLane.validate(input.lane, input.record)
        val raw = json.encodeToString(input.record)
        val existing = input.database.rawQuery("SELECT rowid FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND kind=? AND record_key=?",
            arrayOf(input.bundle, input.lane, input.record.kind, input.record.key)).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existing != null) {
            require(SyncOperationCanonicalizer.canonicalJson(input.readRecord(existing)) == SyncOperationCanonicalizer.canonicalJson(raw)) {
                "SNAPSHOT_CORRUPTED: conflicting Snapshot capture record: ${input.lane}/${input.record.kind}/${input.record.key}"
            }
            return false
        }
        val source = input.sources.prepare(SyncSnapshotSourcePool.Entry(input.bundle, input.lane, input.record))
        val prepared = CapturePrepared(columns(input, json.encodeToString(source.compact)), source,
            SyncSnapshotDerivedFacts.prepare(SyncSnapshotDerivedFacts.Input(input.bundle, input.lane, input.record)))
        commitCapture(input, prepared)
        return true
    }

    /** 原始私有记录、来源与轻索引同事务；已有批次仍由其拥有者提交游标。 */
    private fun commitCapture(input: Input, prepared: CapturePrepared) {
        val ownsTransaction = !input.database.inTransaction()
        if (ownsTransaction) input.database.beginTransaction()
        try {
            input.sources.persist(prepared.source)
            input.database.insertOrThrow("sync_paged_snapshot_record", null, prepared.columns)
            input.derived.write(prepared.facts)
            if (ownsTransaction) input.database.setTransactionSuccessful()
        } finally {
            // 失败保持调用方的回滚责任；单条调用自行结束其真实事务。
            if (ownsTransaction) input.database.endTransaction()
        }
    }

    /** 索引列只携带寻址元数据，原始值完整保存在私有 record_json。 */
    private fun columns(input: Input, raw: String): ContentValues {
        val value = input.record.value
        return ContentValues().apply {
            put("snapshot_bundle_id", input.bundle); put("replication_lane_id", input.lane)
            put("kind", input.record.kind); put("record_key", input.record.key); put("content_hash", "")
            put("entity_type", (value["entityType"] ?: value["ownerEntityType"])?.jsonPrimitive?.content.orEmpty())
            put("entity_sync_id", (value["entitySyncId"] ?: value["ownerEntitySyncId"])?.jsonPrimitive?.content.orEmpty())
            put("generation", (value["entityGeneration"] ?: value["generation"] ?: value["ownerEntityGeneration"])?.jsonPrimitive?.longOrNull ?: 0L)
            put("field_id", value["fieldId"]?.jsonPrimitive?.content.orEmpty()); put("blob_hash", value["hash"]?.jsonPrimitive?.content.orEmpty())
            put("record_json", raw)
        }
    }

    /** 规范记录与摘要整批提交，避免每条 UPDATE 独立刷盘；失败保留本批开始前的冻结索引。 */
    fun normalize(input: Target) {
        val progress = SyncSnapshotBatchProgress(input.database)
        var after = progress.cursor(input.bundle, NORMALIZATION_PHASE)?.let { Json.decodeFromString<List<String>>(it) }
            ?: listOf("", "", "")
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            val batch = prepareBatch(input, after)
            if (batch.isEmpty()) return
            val cursor = json.encodeToString(batch.last().key.cursor)
            progress.commit(SyncSnapshotBatchProgress.Batch(input.bundle, NORMALIZATION_PHASE, cursor,
                rows = batch.size.toLong(), bytes = batch.sumOf { it.key.bytes })) {
                for (row in batch) {
                    row.source?.let { input.sources.persist(it) }
                    input.database.execSQL("UPDATE sync_paged_snapshot_record SET record_json=?,content_hash=? WHERE rowid=?",
                        arrayOf(row.encoded, row.hash, row.id))
                    input.derived.write(row.facts)
                }
            }
            after = batch.last().key.cursor
        }
    }

    /** 每次只读取一个待处理 rowid，大字段沿用分段读取，不占一个巨大 CursorWindow 单元格。 */
    private data class NormalizedKey(val id: Long, val lane: String, val cursor: List<String>, val bytes: Long)
    private data class Normalized(val id: Long, val key: NormalizedKey, val source: SyncSnapshotSourcePool.Prepared?, val encoded: String, val hash: String,
        val facts: SyncSnapshotDerivedFacts.Prepared)

    /** 大对象解码、规范化及摘要在写事务外完成；rowid 只在本次固定表内临时寻址。 */
    private fun prepareBatch(input: Target, after: List<String>): List<Normalized> {
        val ids = normalizationKeys(input, after)
        var bytes = 0L
        return buildList {
            for (next in ids) {
                SyncSnapshotCancellation.checkpoint()
                if (isNotEmpty() && bytes + next.bytes > NORMALIZE_BATCH_BYTES) break
                val raw = SyncPagedSnapshotText.read(input.database, SyncPagedSnapshotText.Source.RECORD, next.id)
                val record = SyncSnapshotRecordCodec.decode(raw)
                SyncSnapshotRecordLane.validate(next.lane, record)
                val source = record.value["sourceOperation"]?.let {
                    input.sources.prepare(SyncSnapshotSourcePool.Entry(bundle = input.bundle, lane = next.lane, record = record))
                }
                val compact = source?.compact ?: record
                val encoded = if (source != null) SyncSnapshotRecordFragments.prepare(SyncSnapshotRecordFragments.Input(compact, source.encoded))
                    else input.sources.encoding(next.id, compact)
                add(Normalized(id = next.id, key = next, source = source, encoded = encoded.stored, hash = encoded.hash,
                    facts = SyncSnapshotDerivedFacts.prepare(SyncSnapshotDerivedFacts.Input(bundle = input.bundle, lane = next.lane, record = compact))))
                bytes += next.bytes
            }
        }
    }

    /** 身份、长度和稳定键先批读并关闭 Cursor，下一条在解码之前判断预算。 */
    private fun normalizationKeys(input: Target, after: List<String>): List<NormalizedKey> =
        input.database.rawQuery("""SELECT rowid,replication_lane_id,kind,record_key,length(CAST(record_json AS BLOB))+
            COALESCE((SELECT length(CAST(s.envelope_json AS BLOB)) FROM sync_snapshot_source_link l JOIN sync_snapshot_source s ON s.source_key=l.source_key
            WHERE l.snapshot_bundle_id=r.snapshot_bundle_id AND l.replication_lane_id=r.replication_lane_id AND l.record_key=r.record_key),0)
            FROM sync_paged_snapshot_record r WHERE snapshot_bundle_id=? AND (replication_lane_id,kind,record_key)>(?,?,?) AND
            (content_hash='' OR (kind='FIELD_VERSION' AND NOT EXISTS(SELECT 1 FROM sync_snapshot_field_index f
             WHERE f.snapshot_bundle_id=r.snapshot_bundle_id AND f.replication_lane_id=r.replication_lane_id AND f.record_key=r.record_key)) OR
             (kind='ENTITY' AND NOT EXISTS(SELECT 1 FROM sync_snapshot_entity_index e
             WHERE e.snapshot_bundle_id=r.snapshot_bundle_id AND e.replication_lane_id=r.replication_lane_id AND e.record_key=r.record_key)))
             ORDER BY replication_lane_id,kind,record_key LIMIT $NORMALIZE_BATCH_ROWS""",
            (listOf(input.bundle) + after).toTypedArray()).use { cursor -> buildList {
            while (cursor.moveToNext()) add(NormalizedKey(id = cursor.getLong(0), lane = cursor.getString(1),
                cursor = listOf(cursor.getString(1), cursor.getString(2), cursor.getString(3)), bytes = cursor.getLong(4)))
        } }
}
