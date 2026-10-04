package me.ash.reader.infrastructure.sync.core

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** 来源分组批读完整轻量承诺，长因果文本在关闭批游标后单独分块读取。 */
internal class SyncSnapshotFieldProjection(private val database: SQLiteDatabase) {
    data class Prepared(val source: String, val field: SyncSnapshotFieldMetadata, val causalRowId: Long?)

    /** 缺失轻事实不能被 JOIN 悄悄丢弃，普通项每列最多读取既定小块。 */
    fun read(cursor: Cursor, bundle: String, lane: String): List<Prepared> {
        val columns = Columns(cursor)
        return buildList {
            while (cursor.moveToNext()) {
                check(!cursor.isNull(columns.rowId)) { "SNAPSHOT_CORRUPTED: field causal index is missing" }
                val length = if (cursor.isNull(columns.causalBytes)) null else cursor.getLong(columns.causalBytes)
                val large = length != null && length > INLINE_CAUSAL_BYTES
                val causal = when {
                    length == null || large -> null
                    length == 0L -> ""
                    else -> checkNotNull(cursor.getBlob(columns.causal)) { "SNAPSHOT_CORRUPTED: causal bytes are missing" }.toString(Charsets.UTF_8)
                }
                val field = SyncSnapshotFieldMetadata(bundle = bundle, lane = lane, key = cursor.getString(columns.key),
                    fieldId = cursor.getString(columns.field), token = cursor.getString(columns.token), clock = cursor.getLong(columns.clock),
                    causalContext = causal, valueDigest = cursor.getString(columns.digest), preferenceValue = cursor.getString(columns.preference),
                    identity = SyncSnapshotFieldIdentity(type = cursor.getString(columns.type), id = cursor.getString(columns.id), generation = cursor.getLong(columns.generation)))
                add(Prepared(cursor.getString(columns.source), field, if (large) cursor.getLong(columns.rowId) else null))
            }
        }
    }

    /** 合法长单条保持完整因果语义，不将空值占位当作实际证据。 */
    fun complete(prepared: Prepared): SyncSnapshotFieldMetadata = prepared.causalRowId?.let {
        prepared.field.copy(causalContext = SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.FIELD_CAUSAL, it))
    } ?: prepared.field

    /** 列定位随批游标创建一次，不为每个字段重复解析投影列名。 */
    private class Columns(cursor: Cursor) {
        val key = cursor.getColumnIndexOrThrow("record_key")
        val type = cursor.getColumnIndexOrThrow("entity_type")
        val id = cursor.getColumnIndexOrThrow("entity_sync_id")
        val generation = cursor.getColumnIndexOrThrow("generation")
        val field = cursor.getColumnIndexOrThrow("field_id")
        val rowId = cursor.getColumnIndexOrThrow("field_rowid")
        val token = cursor.getColumnIndexOrThrow("version_token")
        val clock = cursor.getColumnIndexOrThrow("logical_clock")
        val digest = cursor.getColumnIndexOrThrow("value_digest")
        val preference = cursor.getColumnIndexOrThrow("preference_value_json")
        val causalBytes = cursor.getColumnIndexOrThrow("causal_bytes")
        val causal = cursor.getColumnIndexOrThrow("causal_blob")
        val source = cursor.getColumnIndexOrThrow("source_key")
    }

    companion object {
        /** 每项最多内联 2 KiB 因果文本，长单条仍保留原完整分块读取路径。 */
        private const val INLINE_CAUSAL_BYTES = 2048L
        /** 固定派生表 r/f 的轻列投影，字段值和来源正文不进入批 CursorWindow。 */
        val columns = """r.record_key,r.entity_type,r.entity_sync_id,r.generation,r.field_id,
            f.rowid AS field_rowid,f.version_token,f.logical_clock,f.value_digest,f.preference_value_json,
            length(CAST(f.causal_context_json AS BLOB)) AS causal_bytes,
            CASE WHEN length(CAST(f.causal_context_json AS BLOB))<=$INLINE_CAUSAL_BYTES THEN CAST(f.causal_context_json AS BLOB) END AS causal_blob"""
    }
}
