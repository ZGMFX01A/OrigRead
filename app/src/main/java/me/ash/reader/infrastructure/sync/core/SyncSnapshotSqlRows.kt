package me.ash.reader.infrastructure.sync.core

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** 单行字段按小块读取，巨大的消息/正文不会作为一个 CursorWindow 单元格返回。 */
class SyncSnapshotSqlRows @Inject constructor() {
    data class Row(val database: SupportSQLiteDatabase, val table: String, val rowId: Long)
    data class Selection(val database: SupportSQLiteDatabase, val sql: String, val arguments: Array<out Any?> = emptyArray())
    private data class Column(val row: Row, val name: String)
    private data class Schema(val version: Long, val tables: MutableMap<String, List<String>>)
    private val schemas = WeakHashMap<SupportSQLiteDatabase, Schema>()

    /** 业务源只查询 rowid 游标，实际字段在需要输出当前记录时读取。 */
    suspend fun forEachRowId(selection: Selection, consume: suspend (Long) -> Unit) {
        refreshSchema(selection.database)
        val database = selection.database
        val name = "snapshot_row_ids_${selectionSequence.incrementAndGet()}"
        // 原查询可能按字段或 Dot 排序；一次固化顺序后用 ordinal 分批，不能改为 rowid 顺序改变 wire。
        database.execSQL("CREATE TEMP TABLE $name(ordinal INTEGER PRIMARY KEY,source_id INTEGER NOT NULL)")
        try {
            database.execSQL("INSERT INTO $name(source_id) ${selection.sql}", selection.arguments)
            var after = 0L
            while (true) {
                SyncSnapshotCancellation.checkpoint()
                val batch = database.query("SELECT ordinal,source_id FROM $name WHERE ordinal>? ORDER BY ordinal LIMIT $ROW_ID_BATCH", arrayOf(after)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1)) }
                }
                if (batch.isEmpty()) return
                for ((ordinal, sourceId) in batch) { consume(sourceId); after = ordinal }
            }
        } finally {
            // 消费会进入其他数据库，所有源 Cursor 在回调前关闭，临时轻索引退出时删除。
            database.execSQL("DROP TABLE $name")
        }
    }

    /** 列名来自本地 schema；外部输入不参与 SQL 标识符拼接。 */
    fun readRow(row: Row, selectedColumns: List<String>? = null): JsonObject {
        val table = quotedIdentifier(row.table)
        val available = columns(row)
        val names = selectedColumns ?: available
        check(names.isNotEmpty()) { "Snapshot source table is missing: ${row.table}" }
        check(names.all { it in available }) { "Snapshot source projection has unknown columns: ${row.table}" }
        val projection = names.joinToString(",") { name ->
            val identifier = quotedIdentifier(name)
            "typeof($identifier),CASE WHEN typeof($identifier)='text' THEN substr(CAST($identifier AS BLOB),1,$SCALAR_TEXT_BYTES) ELSE $identifier END,length(CAST($identifier AS BLOB))"
        }
        return row.database.query("SELECT $projection FROM $table WHERE rowid=?", arrayOf(row.rowId)).use { cursor ->
            check(cursor.moveToFirst()) { "Snapshot source row disappeared: ${row.table}/${row.rowId}" }
            JsonObject(names.mapIndexed { index, name -> name to scalar(cursor, index * COLUMN_PARTS, Column(row, name)) }.toMap())
        }
    }

    /** schema_version 改变时清空列缓存；固定 schema 的整批行只查询一次表结构。 */
    private fun columns(row: Row): List<String> {
        synchronized(schemas) { schemas[row.database]?.tables?.get(row.table) }?.let { return it }
        val names = row.database.query("PRAGMA table_info(${quotedIdentifier(row.table)})").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        synchronized(schemas) { schemas.getOrPut(row.database) { Schema(0L, mutableMapOf()) }.tables[row.table] = names }
        return names
    }

    /** 每个固定源阶段检查一次 schema；登记 monitor 内不执行 SQL，避免锁顺序反转。 */
    fun refreshSchema(database: SupportSQLiteDatabase) {
        val version = database.query("PRAGMA schema_version").use { it.moveToFirst(); it.getLong(0) }
        synchronized(schemas) {
            if (schemas[database]?.version != version) schemas[database] = Schema(version, mutableMapOf())
        }
    }

    /** 普通标量由同一行查询返回，只有超过 CursorWindow 小块预算的文本继续分块。 */
    private fun scalar(cursor: Cursor, index: Int, column: Column): JsonElement = when (cursor.getString(index)) {
        "null" -> JsonNull
        "integer" -> JsonPrimitive(cursor.getLong(index + 1))
        "real" -> JsonPrimitive(cursor.getDouble(index + 1))
        "text" -> JsonPrimitive(if (cursor.getLong(index + 2) > SCALAR_TEXT_BYTES) readText(column)
            else (cursor.getBlob(index + 1) ?: byteArrayOf()).toString(Charsets.UTF_8))
        else -> error("Unsupported Snapshot source column type: ${column.row.table}/${column.name}")
    }


    /** 以 BLOB 字节切块保留 NUL，再由同一个 UTF-8 decoder 处理中文/emoji 跨块边界。 */
    private fun readText(column: Column): String {
        val row = column.row
        val table = quotedIdentifier(row.table)
        val name = quotedIdentifier(column.name)
        return SyncSnapshotTextChunks.read { offset, length ->
            row.database.query("SELECT substr(CAST($name AS BLOB),?,?),length(CAST($name AS BLOB)) FROM $table WHERE rowid=?",
                arrayOf(offset, length, row.rowId)).use {
                check(it.moveToFirst()) { "Snapshot source row disappeared: ${row.table}/${row.rowId}" }
                check(!it.isNull(1)) { "Snapshot source text became null: ${row.table}/${column.name}" }
                // 空文本与最后一块之后均为正常 EOF，不能依赖 Android 对空 BLOB 的返回值。
                if (offset > it.getLong(1)) byteArrayOf() else requireNotNull(it.getBlob(0)) {
                    "Snapshot source text chunk is null before EOF: ${row.table}/${column.name}"
                }
            }
        }
    }

    /** 仅允许本地表/列名的普通标识符，值始终通过参数绑定。 */
    private fun quotedIdentifier(value: String): String {
        require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid local Snapshot SQL identifier" }
        return "\"$value\""
    }

    companion object {
        /** 临时元数据索引名称仅来自进程内序号，不含外部 SQL 标识符。 */
        private val selectionSequence = AtomicLong()
        /** 固定顺序元数据按 256 行读取，回调不持有源库 Cursor。 */
        private const val ROW_ID_BATCH = 256
        /** 每列只预读 2 KiB，超长正文继续走原有无总量截断的 UTF-8 分块读取器。 */
        private const val SCALAR_TEXT_BYTES = 2048
        /** 每列投影依次包含 SQLite 类型、标量/文本首块与字节长度。 */
        private const val COLUMN_PARTS = 3
    }
}
