package me.ash.reader.llm.chat.data

import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import me.ash.reader.infrastructure.sync.core.SyncLocalBlobStore
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.llm.runtime.LlmExecutionTask
import me.ash.reader.llm.search.WebSearchRequestStatus

internal data class LlmGenesisPayloadSeed(val type: SyncEntityType, val localId: String, val payloadJson: String)

/** 逐行恢复 Chat domain 对象，复用正式 payload 转换，避免维护第二套跨端字段语义。 */
class LlmGenesisChatRows @Inject constructor(private val blobs: SyncLocalBlobStore) {
    /** 会话的来源文章、模型和时间原样进入正式 payload。 */
    internal fun conversation(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmConversationEntity(id = f.text("id"), title = f.text("title"),
            providerId = f.nullableText("provider_id"), model = f.nullableText("model"),
            skillId = f.nullableText("skill_id"), articleId = f.nullableText("article_id"),
            articleTitle = f.nullableText("article_title"), articleLink = f.nullableText("article_link"),
            createdAt = f.number("created_at"), updatedAt = f.number("updated_at"))
        return LlmGenesisPayloadSeed(SyncEntityType.CONVERSATION, entity.id, entity.toSyncPayloadJson())
    }

    /** 单条消息允许大正文；SQL 分段读取后只转换当前消息。 */
    internal fun message(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmMessageEntity(id = f.text("id"), conversationId = f.text("conversation_id"),
            role = LlmChatRole.valueOf(f.text("role")), content = f.text("content"),
            requestTask = f.nullableText("request_task")?.let(LlmExecutionTask::valueOf),
            reasoning = f.nullableText("reasoning"), status = LlmMessageStatus.valueOf(f.text("status")),
            errorMessage = f.nullableText("error_message"), historyActive = f.flag("history_active"),
            webSearchStatus = f.nullableText("web_search_status")?.let(WebSearchRequestStatus::valueOf),
            webSearchQuery = f.nullableText("web_search_query"), webSearchProviderName = f.nullableText("web_search_provider_name"),
            webSearchErrorMessage = f.nullableText("web_search_error_message"),
            promptTokens = f.nullableNumber("prompt_tokens")?.toInt(), completionTokens = f.nullableNumber("completion_tokens")?.toInt(),
            durationMs = f.nullableNumber("duration_ms"), tokenUsageEstimated = f.flag("token_usage_estimated"),
            createdAt = f.number("created_at"), updatedAt = f.number("updated_at"))
        return LlmGenesisPayloadSeed(SyncEntityType.MESSAGE, entity.id, entity.toSyncPayloadJson())
    }

    /** 工具结果继续使用正式耐久 Blob，参数和状态保持既有映射。 */
    internal fun toolCall(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmToolCallEntity(id = f.text("id"), conversationId = f.text("conversation_id"),
            assistantMessageId = f.text("assistant_message_id"), providerCallId = f.text("provider_call_id"),
            toolId = f.text("tool_id"), toolName = f.nullableText("tool_name"), toolSourceId = f.nullableText("tool_source_id"),
            apiName = f.text("api_name"), argumentsJson = f.text("arguments_json"),
            status = LlmToolCallStatus.valueOf(f.text("status")), resultContent = f.nullableText("result_content"),
            errorMessage = f.nullableText("error_message"), createdAt = f.number("created_at"), updatedAt = f.number("updated_at"))
        return LlmGenesisPayloadSeed(SyncEntityType.TOOL_CALL, entity.id, entity.toSyncPayloadJson(blobs))
    }

    /** 会话文章关系使用现有复合身份算法，文章快照转换仍由正式 payload 负责。 */
    internal fun conversationArticle(row: JsonObject): LlmGenesisPayloadSeed {
        val f = LlmGenesisSqlFields(row)
        val entity = LlmConversationArticleEntity(conversationId = f.text("conversation_id"), articleId = f.text("article_id"),
            title = f.text("title"), link = f.nullableText("link"), originalContent = f.text("original_content"),
            summary = f.nullableText("summary"), position = f.number("position").toInt(), createdAt = f.number("created_at"))
        val type = SyncEntityType.CONVERSATION_ARTICLE
        return LlmGenesisPayloadSeed(type, SyncCanonicalIdentity.relationLocalId(type, entity.conversationId, entity.articleId),
            entity.toSyncPayloadJson(blobs))
    }
}
