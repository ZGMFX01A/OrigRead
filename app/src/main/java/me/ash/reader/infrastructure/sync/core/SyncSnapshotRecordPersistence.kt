package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 持久化完整承诺摘要和紧凑派生记录，不改变不可变页面。 */
internal class SyncSnapshotRecordPersistence(private val input: Dependencies) {
    data class Dependencies(val database: SQLiteDatabase, val sources: SyncSnapshotSourcePool,
        val readRecord: (Long) -> String, val derived: SyncSnapshotDerivedIndex)
    private val database get() = input.database
    private val sources get() = input.sources
    data class Prepared(val bundle: String, val lane: String, val kind: String, val key: String,
        val hash: String, val columns: List<Any?>, val source: SyncSnapshotSourcePool.Prepared,
        val facts: SyncSnapshotDerivedFacts.Prepared)
    data class Capture(val bundle: String, val lane: String, val record: SyncSnapshotRecord, val frozen: Boolean = false)
    /** Recovery 未发布索引可补齐原签名；网络不可变页仍走严格 writeRecord。 */
    fun mergeFieldRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord) {
        val prepared = prepareMergedField(snapshotBundleId, lane, record)
        database.delete("sync_paged_snapshot_record", "snapshot_bundle_id=? AND replication_lane_id=? AND kind='FIELD_VERSION' AND record_key=?",
            arrayOf(snapshotBundleId, lane, record.key))
        writePrepared(prepared)
    }

    /** 冲突证据和完整来源的合并在写事务前准备。 */
    fun prepareMergedField(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Prepared {
        val raw = database.rawQuery("SELECT rowid FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND kind='FIELD_VERSION' AND record_key=?",
            arrayOf(snapshotBundleId, lane, record.key)).use { if (it.moveToFirst()) input.readRecord(it.getLong(0)) else null }
        if (raw == null) return prepare(snapshotBundleId, lane, record)
        val old = SyncSnapshotRecordCodec.decode(raw)
        val merged = record.copy(value = SyncSnapshotRollbackCandidate.merge(old.value, record.value))
        return prepare(snapshotBundleId, lane, merged)
    }

    /** 完整业务键只对应一个规范值，同值重试去重，冲突值立即失败。 */
    fun writeRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Boolean {
        return writePrepared(prepare(snapshotBundleId, lane, record))
    }

    /** 字段转换、规范化、完整摘要和来源对象准备全部在事务之前完成。 */
    fun prepare(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Prepared {
        return prepareCapture(Capture(snapshotBundleId, lane, record))
    }

    /** 私有捕获暂缺 wire 摘要，完成来源分组和规范化前不能发布。 */
    fun prepareCapture(capture: Capture): Prepared {
        val (snapshotBundleId, lane, record) = capture
        SyncSnapshotRecordValidation.validate(record)
        SyncSnapshotRecordLane.validate(lane, record)
        val source = sources.prepare(SyncSnapshotSourcePool.Entry(snapshotBundleId, lane, record))
        // 规范成员只编码一次，完整来源仍参与原摘要，紧凑落盘不再重复转义正文。
        val encoded = if (capture.frozen) SyncSnapshotRecordFragments.Prepared("", Json.encodeToString(source.compact))
            else SyncSnapshotRecordFragments.prepare(SyncSnapshotRecordFragments.Input(source.compact, source.encoded))
        val hash = encoded.hash
        val value = record.value
        val columns = listOf(snapshotBundleId, lane, record.kind, record.key, hash,
            (value["entityType"] ?: value["ownerEntityType"])?.jsonPrimitive?.content.orEmpty(),
            (value["entitySyncId"] ?: value["ownerEntitySyncId"])?.jsonPrimitive?.content.orEmpty(),
            (value["entityGeneration"] ?: value["generation"] ?: value["ownerEntityGeneration"])?.jsonPrimitive?.longOrNull ?: 0L,
            value["fieldId"]?.jsonPrimitive?.content.orEmpty(), value["hash"]?.jsonPrimitive?.content.orEmpty(),
            encoded.stored)
        return Prepared(snapshotBundleId, lane, record.kind, record.key, hash, columns, source,
            SyncSnapshotDerivedFacts.prepare(SyncSnapshotDerivedFacts.Input(snapshotBundleId, lane, record)))
    }

    /** 此方法只读轻量键并写 SQL 列，完整来源链接与记录由同一批事务拥有。 */
    fun writePrepared(prepared: Prepared): Boolean {
        if (database.inTransaction()) return writeLocked(prepared)
        database.beginTransaction()
        try {
            val inserted = writeLocked(prepared)
            database.setTransactionSuccessful()
            return inserted
        } finally {
            // 单条嵌入式调用同样原子提交来源、记录和派生承诺；失败自动回滚。
            database.endTransaction()
        }
    }

    /** 继承已有批次事务，不提前提交调用方的数据或恢复断点。 */
    private fun writeLocked(prepared: Prepared): Boolean {
        if (duplicate(prepared)) return false
        sources.persist(prepared.source)
        database.execSQL("INSERT INTO sync_paged_snapshot_record VALUES(?,?,?,?,?,?,?,?,?,?,?)", prepared.columns.toTypedArray())
        input.derived.write(prepared.facts)
        return true
    }

    /** 空摘要只属于私有事实，重入必须分块读该记录并核对完整规范内容。 */
    fun duplicate(prepared: Prepared): Boolean {
        val existing = database.rawQuery("SELECT rowid,content_hash FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND kind=? AND record_key=?",
            arrayOf(prepared.bundle, prepared.lane, prepared.kind, prepared.key)).use {
            if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
        } ?: return false
        val equal = if (prepared.hash.isNotEmpty()) prepared.hash == existing.second else sameCapturedRecord(
            SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.RECORD, existing.first), prepared.columns.last().toString())
        check(equal) { "SNAPSHOT_CORRUPTED: conflicting Snapshot record key" }
        if (prepared.hash.isEmpty() && prepared.source.key != null) check(sources.key(prepared.source.input) == prepared.source.key) {
            "DOT_COLLISION: captured field sources differ"
        }
        return true
    }
}

/** 只在文本不同的重复私有键上计算规范内容，空摘要永远不能代替承诺。 */
internal fun sameCapturedRecord(first: String, second: String): Boolean = first == second ||
    SyncOperationCanonicalizer.canonicalJson(first) == SyncOperationCanonicalizer.canonicalJson(second)
