package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** 仅改变本地派生表示；线上页和原签名对象保持完整、不可变。 */
internal class SyncSnapshotSourcePool(private val database: SQLiteDatabase) {
    data class Entry(val bundle: String, val lane: String, val record: SyncSnapshotRecord)
    data class Prepared(val input: Entry, val compact: SyncSnapshotRecord, val key: String?, val encoded: String?)
    data class Canonical(val key: String, val encoded: String)
    private data class Recent(val raw: String, val encoded: String, val key: String)
    /** 只保留紧邻的一个完整来源，减少同一 UPSERT 多字段的重复规范化。 */
    @Volatile private var recent: Recent? = null
    private data class OutputSource(val key: String, val revision: Long, val encoded: String)
    private var outputSource: OutputSource? = null

    /** 按完整规范签名对象去重，字段链接保留各自承诺，不能仅按 operationId 命中。 */
    fun pack(input: Entry): SyncSnapshotRecord {
        val prepared = prepare(input)
        persist(prepared)
        return prepared.compact
    }

    /** 完整对象的规范化与摘要在写事务外准备，SQL 提交只接收已冻结字节。 */
    fun prepare(input: Entry): Prepared {
        val source = input.record.value["sourceOperation"]?.takeUnless { it == JsonNull }
        if (source == null) return Prepared(input, input.record, null, null)
        val raw = source.toString()
        val saved = recent?.takeIf { it.raw == raw } ?: SyncOperationCanonicalizer.canonicalValue(source).let {
            Recent(raw, it, SyncOperationCanonicalizer.sha256Hex(it)).also { current -> recent = current }
        }
        return Prepared(input, input.record.copy(value = JsonObject(input.record.value - "sourceOperation")),
            saved.key, saved.encoded)
    }

    /** 分组来源已规范编码，字段直接复用完整材料，不再逐字段展开操作正文。 */
    fun prepareCanonical(input: Entry, source: Canonical?): Prepared = Prepared(input,
        if (source == null) input.record else input.record.copy(value = JsonObject(input.record.value - "sourceOperation")),
        source?.key, source?.encoded)

    /** 旧私有来源重入必须校验真实完整字节，不能静默移除已有签名。 */
    fun readCanonical(key: String): Canonical {
        val id = database.rawQuery("SELECT rowid FROM sync_snapshot_source WHERE source_key=?", arrayOf(key)).use {
            check(it.moveToFirst()) { "SNAPSHOT_CORRUPTED: field source is missing" }; it.getLong(0)
        }
        val encoded = SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.SOURCE, id)
        check(SyncOperationCanonicalizer.sha256Hex(encoded) == key) { "SNAPSHOT_CORRUPTED: signed source digest differs" }
        return Canonical(key, encoded)
    }

    /** 来源池和字段链接必须与调用方记录同事务提交，比较不解码或搬运已存在的大载荷。 */
    fun persist(prepared: Prepared) {
        if (prepared.key == null) {
            link(prepared)
            return
        }
        val encoded = checkNotNull(prepared.encoded)
        val conflict = database.rawQuery("SELECT 1 FROM sync_snapshot_source WHERE source_key=? AND envelope_json<>?",
            arrayOf(prepared.key, encoded)).use { it.moveToFirst() }
        check(!conflict) { "SNAPSHOT_CORRUPTED: source digest names another signed envelope" }
        database.execSQL("INSERT OR IGNORE INTO sync_snapshot_source VALUES(?,?)", arrayOf(prepared.key, encoded))
        link(prepared)
    }

    /** 原来源已经提交的后续字段只写轻链接，仍由调用方的记录事务拥有。 */
    fun link(prepared: Prepared) {
        val input = prepared.input
        if (prepared.key == null) database.delete("sync_snapshot_source_link", "snapshot_bundle_id=? AND replication_lane_id=? AND record_key=?",
            arrayOf(input.bundle, input.lane, input.record.key))
        else database.execSQL("INSERT OR REPLACE INTO sync_snapshot_source_link VALUES(?,?,?,?)",
            arrayOf(input.bundle, input.lane, input.record.key, prepared.key))
    }

    /** 旧全文记录照常读取；新索引只在证据验证或输出 wire 时展开精确来源。 */
    fun unpack(rowId: Long, raw: String): String {
        val sourceRow = database.rawQuery("""SELECT s.rowid,l.source_key FROM sync_paged_snapshot_record r
            JOIN sync_snapshot_source_link l ON l.snapshot_bundle_id=r.snapshot_bundle_id
              AND l.replication_lane_id=r.replication_lane_id AND l.record_key=r.record_key
            LEFT JOIN sync_snapshot_source s ON s.source_key=l.source_key
            WHERE r.rowid=? AND r.kind='FIELD_VERSION'""", arrayOf(rowId.toString())).use {
            if (!it.moveToFirst()) null else {
                check(!it.isNull(0)) { "SNAPSHOT_CORRUPTED: field source is missing" }
                it.getLong(0)
            }
        } ?: return raw
        val record = SyncSnapshotRecordCodec.decode(raw)
        val source = Json.parseToJsonElement(SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.SOURCE, sourceRow))
        return Json.encodeToString(record.copy(value = JsonObject(record.value + ("sourceOperation" to source))))
    }

    /** 页面直接消费规范片段，不拼接包含大字段及完整来源的第二份字符串。 */
    fun fragments(rowId: Long, raw: String): Sequence<String> = SyncSnapshotRecordFragments.fragments(
        SyncSnapshotRecordFragments.Input(SyncSnapshotRecordCodec.decode(raw), outputMaterial(rowId)))

    /** 规范化只编码一次紧凑成员，完整来源进入摘要而不分配完整 wire 字符串。 */
    fun encoding(rowId: Long, record: SyncSnapshotRecord): SyncSnapshotRecordFragments.Prepared =
        SyncSnapshotRecordFragments.prepare(SyncSnapshotRecordFragments.Input(record, outputMaterial(rowId)))

    /** 当前派生修订变化后重新检查真实来源摘要，只复用当前一个不可变片段。 */
    private fun outputMaterial(rowId: Long): String? {
        val link = database.rawQuery("""SELECT s.rowid,l.source_key,COALESCE(v.revision,0) FROM sync_paged_snapshot_record r
            JOIN sync_snapshot_source_link l ON l.snapshot_bundle_id=r.snapshot_bundle_id AND l.replication_lane_id=r.replication_lane_id AND l.record_key=r.record_key
            LEFT JOIN sync_snapshot_source s ON s.source_key=l.source_key
            LEFT JOIN sync_snapshot_revision v ON v.bundle_id=r.snapshot_bundle_id WHERE r.rowid=? AND r.kind='FIELD_VERSION'""", arrayOf(rowId.toString())).use {
            if (!it.moveToFirst()) null else {
                check(!it.isNull(0)) { "SNAPSHOT_CORRUPTED: field source is missing" }
                Triple(it.getLong(0), it.getString(1), it.getLong(2))
            }
        }
        if (link == null) return null
        if (outputSource?.key != link.second || outputSource?.revision != link.third) {
            val encoded = SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.SOURCE, link.first)
            check(SyncOperationCanonicalizer.sha256Hex(encoded) == link.second) { "SNAPSHOT_CORRUPTED: signed source digest differs" }
            outputSource = OutputSource(link.second, link.third, encoded)
        }
        return checkNotNull(outputSource).encoded
    }

    /** 字段只查询轻量来源键，验证器可按完整对象身份复用一次解码。 */
    fun key(input: Entry): String? = database.rawQuery("SELECT source_key FROM sync_snapshot_source_link WHERE snapshot_bundle_id=? AND replication_lane_id=? AND record_key=?",
        arrayOf(input.bundle, input.lane, input.record.key)).use { if (it.moveToFirst()) it.getString(0) else null }

    /** 真实来源丢失或摘要不符直接失败，不降级为“无来源”。 */
    fun read(key: String): SyncOperationEnvelope {
        val id = database.rawQuery("SELECT rowid FROM sync_snapshot_source WHERE source_key=?", arrayOf(key)).use {
            check(it.moveToFirst()) { "SNAPSHOT_CORRUPTED: field source is missing" }; it.getLong(0)
        }
        val raw = SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.SOURCE, id)
        check(SyncOperationCanonicalizer.sha256Hex(raw) == key) { "SNAPSHOT_CORRUPTED: signed source digest differs" }
        return SyncOperationWireCodec.decode(raw)
    }
}

/** 来源池及链接是可重建派生数据；旧页、业务库和签名历史不变。 */
internal const val PAGED_SNAPSHOT_SOURCE_SCHEMA = """
CREATE TABLE sync_snapshot_source(source_key TEXT PRIMARY KEY,envelope_json TEXT NOT NULL);
CREATE TABLE sync_snapshot_source_link(snapshot_bundle_id TEXT NOT NULL,replication_lane_id TEXT NOT NULL,
 record_key TEXT NOT NULL,source_key TEXT NOT NULL,PRIMARY KEY(snapshot_bundle_id,replication_lane_id,record_key));
CREATE INDEX sync_snapshot_source_link_source ON sync_snapshot_source_link(source_key);
CREATE INDEX sync_snapshot_source_link_field_order ON sync_snapshot_source_link(snapshot_bundle_id,replication_lane_id,source_key,record_key);
"""
