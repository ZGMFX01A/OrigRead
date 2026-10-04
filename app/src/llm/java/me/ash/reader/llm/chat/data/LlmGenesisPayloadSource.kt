package me.ash.reader.llm.chat.data

import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import me.ash.reader.infrastructure.sync.core.SyncSnapshotSqlRows
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

/** 在既有跨库 Genesis barrier 内逐行输出稳定历史，关联过滤直接由 SQL 完成。 */
class LlmGenesisPayloadSource @Inject constructor(
    database: LlmChatDatabase,
    private val rows: SyncSnapshotSqlRows,
    private val converters: LlmGenesisPayloadRows,
) {
    private val liveDatabase = database
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase, "chat")
    private data class Source(val table: String, val joins: String, val type: SyncEntityType)

    /** 拓扑顺序只影响实例化依赖；字段版本及引用身份保持正式 payload 的语义。 */
    internal suspend fun forEach(consume: suspend (LlmGenesisPayloadSeed) -> Unit) {
        val sql = database.openHelper.writableDatabase
        for (source in sources()) {
            rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql,
                "SELECT s.rowid FROM ${source.table} s ${source.joins} ORDER BY s.rowid")) { rowId ->
                val value = rows.readRow(SyncSnapshotSqlRows.Row(sql, source.table, rowId))
                consume(converters.convert(source.type, value))
            }
        }
    }

    /** 在数据库判断稳定父记录，避免构造全库 message/context/evidence ID 集合。 */
    private fun sources(): List<Source> = listOf(
        Source("llm_conversations", "", SyncEntityType.CONVERSATION),
        Source("llm_messages", "WHERE s.status<>'STREAMING'", SyncEntityType.MESSAGE),
        Source("llm_tool_calls", "$STABLE_ASSISTANT WHERE m.role='ASSISTANT' AND m.status<>'STREAMING' " +
            "AND s.status IN ('COMPLETE','DENIED','ERROR')", SyncEntityType.TOOL_CALL),
        Source("llm_context_refs", "$STABLE_ASSISTANT WHERE m.role='ASSISTANT' AND m.status<>'STREAMING'", SyncEntityType.CONTEXT_REF),
        Source("llm_evidence_blocks", "JOIN llm_context_refs c ON c.id=s.context_ref_id " +
            "JOIN llm_messages m ON m.id=c.assistant_message_id WHERE m.role='ASSISTANT' AND m.status<>'STREAMING'", SyncEntityType.EVIDENCE_BLOCK),
        Source("llm_citation_refs", "$STABLE_CITATION WHERE m.role='ASSISTANT' AND m.status<>'STREAMING' $CITATION_EVIDENCE", SyncEntityType.CITATION_REF),
        Source("llm_citation_annotations", "$STABLE_ASSISTANT WHERE m.role='ASSISTANT' AND m.status<>'STREAMING'", SyncEntityType.CITATION_ANNOTATION),
        Source("llm_conversation_articles", "", SyncEntityType.CONVERSATION_ARTICLE),
        Source("llm_citation_annotation_refs", "JOIN llm_citation_annotations a ON a.id=s.annotation_id " +
            "JOIN llm_messages am ON am.id=a.assistant_message_id JOIN llm_citation_refs cr ON cr.id=s.citation_ref_id " +
            "JOIN llm_context_refs c ON c.id=cr.context_ref_id JOIN llm_messages m ON m.id=cr.assistant_message_id " +
            "WHERE am.role='ASSISTANT' AND am.status<>'STREAMING' AND m.role='ASSISTANT' AND m.status<>'STREAMING' " +
            "AND (cr.evidence_block_id IS NULL OR EXISTS(SELECT 1 FROM llm_evidence_blocks e " +
            "WHERE e.id=cr.evidence_block_id AND e.context_ref_id=c.id))", SyncEntityType.CITATION_ANNOTATION_REF),
    )

    companion object {
        /** 子记录只关联已经结束的 assistant 请求，流式中的请求不进入冻结快照。 */
        private const val STABLE_ASSISTANT = "JOIN llm_messages m ON m.id=s.assistant_message_id"
        /** Citation 的 Context 与请求归属同时匹配，禁止通过不相干的稳定父记录进入快照。 */
        private const val STABLE_CITATION = "JOIN llm_messages m ON m.id=s.assistant_message_id " +
            "JOIN llm_context_refs c ON c.id=s.context_ref_id AND c.assistant_message_id=m.id"
        /** 非空 Evidence 引用必须对应同一 Context 内真实存在的稳定 Evidence。 */
        private const val CITATION_EVIDENCE = "AND (s.evidence_block_id IS NULL OR EXISTS(" +
            "SELECT 1 FROM llm_evidence_blocks e WHERE e.id=s.evidence_block_id AND e.context_ref_id=c.id))"
    }
}
