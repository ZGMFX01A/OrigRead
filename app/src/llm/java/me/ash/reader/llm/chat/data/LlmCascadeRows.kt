package me.ash.reader.llm.chat.data

import javax.inject.Inject
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

/** 级联删除只遍历 rowid 和外键，不把消息、证据或正文装入 CursorWindow。 */
internal class LlmCascadeRows @Inject constructor(private val database: LlmChatDatabase) {
    data class Row(val type: String, val rowId: Long)
    private data class Edge(val parent: String, val child: String, val parentFields: List<String>, val childFields: List<String>)
    private val sql get() = database.openHelper.writableDatabase

    /** 关系实体使用正式组合身份，其余实体按真实本地 id 定位。 */
    suspend fun roots(type: String, localId: String, consume: suspend (Row) -> Unit) {
        val table = table(type)
        if (type !in RELATION_TYPES) {
            sql.query("SELECT rowid FROM $table WHERE id=?", arrayOf(localId)).use { cursor ->
                while (cursor.moveToNext()) consume(Row(type, cursor.getLong(0)))
            }
            return
        }
        val columns = if (type == "conversation_article") listOf("conversation_id", "article_id") else listOf("annotation_id", "citation_ref_id")
        sql.query("SELECT rowid,${columns.joinToString(",")} FROM $table").use { cursor ->
            while (cursor.moveToNext()) {
                val id = SyncCanonicalIdentity.relationLocalId(checkNotNull(SyncEntityType.fromWireName(type)), cursor.getString(1), cursor.getString(2))
                if (id == localId) consume(Row(type, cursor.getLong(0)))
            }
        }
    }

    /** 使用 Chat schema 声明的 CASCADE 外键，父实体和全部实际后代都进入持久清理计划。 */
    suspend fun visit(row: Row, consume: suspend (Row) -> Unit) {
        consume(row)
        for (edge in edges(row.type)) {
            val values = columns(row, edge.parentFields)
            val predicate = edge.childFields.joinToString(" AND ") { "$it=?" }
            sql.query("SELECT rowid FROM ${table(edge.child)} WHERE $predicate", values.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) visit(Row(edge.child, cursor.getLong(0)), consume)
            }
        }
    }

    /** 业务身份读取始终只包含主键，关系身份沿用正式规范 Hash。 */
    fun localId(row: Row): String = if (row.type in RELATION_TYPES) {
        val columns = if (row.type == "conversation_article") listOf("conversation_id", "article_id") else listOf("annotation_id", "citation_ref_id")
        val values = columns(row, columns)
        SyncCanonicalIdentity.relationLocalId(checkNotNull(SyncEntityType.fromWireName(row.type)), values[0], values[1])
    } else columns(row, listOf("id")).single()

    /** 删除只绑定真实 rowid；SQLite 按已声明的外键执行实际级联。 */
    fun delete(row: Row) { sql.execSQL("DELETE FROM ${table(row.type)} WHERE rowid=?", arrayOf(row.rowId)) }

    /** 只查询本地 schema 已声明的外键列，外部输入不参与 SQL 标识符。 */
    private fun columns(row: Row, names: List<String>): List<String> {
        check(names.all { it.matches(Regex("[a-z_]+")) }) { "Invalid local cascade column" }
        return sql.query("SELECT ${names.joinToString(",")} FROM ${table(row.type)} WHERE rowid=?", arrayOf(row.rowId)).use { cursor ->
            check(cursor.moveToFirst()) { "Chat cascade row disappeared" }
            names.indices.map { cursor.getString(it) }
        }
    }

    /** PRAGMA 只含轻量 schema，复合外键按 id/seq 还原，保持真实级联语义。 */
    private fun edges(parent: String): List<Edge> = TABLES.flatMap { (child, childTable) ->
        sql.query("PRAGMA foreign_key_list($childTable)").use { cursor ->
            val groups = linkedMapOf<Int, MutableList<Pair<String, String>>>()
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == table(parent) && cursor.getString(6) == "CASCADE") {
                    groups.getOrPut(cursor.getInt(0)) { mutableListOf() }.add(cursor.getString(4) to cursor.getString(3))
                }
            }
            groups.values.map { fields -> Edge(parent, child, fields.map { it.first }, fields.map { it.second }) }
        }
    }

    private fun table(type: String): String = checkNotNull(TABLES[type]) { "Unsupported Chat cascade entity" }
    companion object {
        /** 两类关系使用正式组合主键，不能假定都有 id 列。 */
        private val RELATION_TYPES = setOf("conversation_article", "citation_annotation_ref")
        /** 产品实体与真实 Chat 表的唯一映射，所有 SQL 标识符来自该白名单。 */
        private val TABLES = mapOf("conversation" to "llm_conversations", "message" to "llm_messages",
            "tool_call" to "llm_tool_calls", "context_ref" to "llm_context_refs", "evidence_block" to "llm_evidence_blocks",
            "citation_ref" to "llm_citation_refs", "citation_annotation" to "llm_citation_annotations",
            "conversation_article" to "llm_conversation_articles", "citation_annotation_ref" to "llm_citation_annotation_refs")
    }
}
