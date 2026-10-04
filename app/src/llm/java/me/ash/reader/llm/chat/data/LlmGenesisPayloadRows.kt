package me.ash.reader.llm.chat.data

import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

/** 逐行转换按职责注入，调度层无需硬编码创建 Chat 或 Evidence 转换器。 */
class LlmGenesisPayloadRows @Inject constructor(
    private val chat: LlmGenesisChatRows,
    private val evidence: LlmGenesisEvidenceRows,
) {
    /** 所有 AI_HISTORY 类型共用正式 payload 映射，未知类型必须显式失败。 */
    internal fun convert(type: SyncEntityType, row: JsonObject): LlmGenesisPayloadSeed = when (type) {
        SyncEntityType.CONVERSATION -> chat.conversation(row)
        SyncEntityType.MESSAGE -> chat.message(row)
        SyncEntityType.TOOL_CALL -> chat.toolCall(row)
        SyncEntityType.CONVERSATION_ARTICLE -> chat.conversationArticle(row)
        SyncEntityType.CONTEXT_REF -> evidence.context(row)
        SyncEntityType.EVIDENCE_BLOCK -> evidence.evidence(row)
        SyncEntityType.CITATION_REF -> evidence.citation(row)
        SyncEntityType.CITATION_ANNOTATION -> evidence.annotation(row)
        SyncEntityType.CITATION_ANNOTATION_REF -> evidence.annotationRef(row)
        else -> error("Unsupported Genesis AI_HISTORY type: ${type.wireName}")
    }
}
