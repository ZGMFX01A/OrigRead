package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase

/** 专用存储的大 JSON 单元格分段读取，页面索引增长也不受 CursorWindow 单格大小影响。 */
internal object SyncPagedSnapshotText {
    /** 允许读取的本地表/列固定，外部请求不能参与 SQL 标识符拼接。 */
    enum class Source(val table: String, val column: String) {
        RECORD("sync_paged_snapshot_record", "record_json"),
        MANIFEST("sync_paged_snapshot", "manifest_json"),
        JOURNAL("sync_paged_snapshot_receive_journal", "manifest_json"),
        TAIL("sync_paged_snapshot_tail", "operation_json"),
        /** 完整来源载荷按字节分块，不能要求 CursorWindow 容纳大 Operation。 */
        SOURCE("sync_snapshot_source", "envelope_json"),
        /** 字段裁决只分块读取因果上下文，不带入该字段的实际大文本。 */
        FIELD_CAUSAL("sync_snapshot_field_index", "causal_context_json"),
        /** 别名删除传播使用不可变临时输入，避免自身重写破坏预取身份。 */
        ALIAS_SEED("sync_recovery_alias_seed", "record_json"),
    }
    /** 分块原始字节后持续解码，正文中的 NUL 与跨块 UTF-8 字符均保持完整。 */
    fun read(database: SQLiteDatabase, source: Source, rowId: Long): String {
        var totalBytes: Long? = null
        return SyncSnapshotTextChunks.read load@ { start, length ->
            // 首块已经读取真实字节长度，末尾无需再执行 SQL；跨块仍由原解码器完整恢复。
            if (totalBytes?.let { start > it } == true) return@load byteArrayOf()
            database.rawQuery("SELECT substr(CAST(${source.column} AS BLOB),?,?),length(CAST(${source.column} AS BLOB)) FROM ${source.table} WHERE rowid=?",
                arrayOf(start.toString(), length.toString(), rowId.toString())).use {
                check(it.moveToFirst()) { "SNAPSHOT_CORRUPTED: Snapshot stored text is missing" }
                check(!it.isNull(1)) { "SNAPSHOT_CORRUPTED: Snapshot stored text is null" }
                totalBytes = it.getLong(1)
                // Android 的空 BLOB 可能由 Cursor 返回 null；以原始字节长度明确判断 EOF。
                if (start > it.getLong(1)) byteArrayOf() else requireNotNull(it.getBlob(0)) {
                    "SNAPSHOT_CORRUPTED: Snapshot text chunk is null before EOF"
                }
            }
        }
    }
}
