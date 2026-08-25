package me.ash.reader.llm.chat.data

import java.security.MessageDigest
import java.util.UUID
import me.ash.reader.llm.runtime.ComposedLlmContext
import me.ash.reader.llm.runtime.LlmContextItem

/**
 * 将一次 Runtime prepare 的候选 Context 与最终预算结果冻结为请求级 ContextRef。
 *
 * 完整来源快照与真正进入 Prompt 的片段分开保存：前者用于历史来源恢复，后者用于解释当次模型实际看到了什么。
 */
internal fun buildContextRefEntities(
    conversationId: String,
    assistantMessageId: String,
    candidates: List<LlmContextItem>,
    composed: ComposedLlmContext,
    createdAt: Long = System.currentTimeMillis(),
): List<LlmContextRefEntity> {
    val renderedById = composed.renderedItems.associateBy { it.id }
    val includedIds = composed.includedIds.toSet()
    return candidates.map { item ->
        val rendered = renderedById[item.id]
        val snapshot = item.content
        LlmContextRefEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            assistantMessageId = assistantMessageId,
            contextId = item.id,
            type = item.type,
            title = item.title?.trim()?.takeIf(String::isNotBlank),
            sourceId = item.sourceId?.trim()?.takeIf(String::isNotBlank),
            sourceUrl = item.sourceId?.asHttpSourceUrlOrNull(),
            contentSnapshot = snapshot,
            promptContentSnapshot = rendered?.content,
            contentSha256 = snapshot.sha256(),
            priority = item.priority,
            includedInPrompt = item.id in includedIds,
            truncatedInPrompt = rendered?.truncated == true,
            createdAt = createdAt,
        )
    }
}

/**
 * 标准 Tool Calling 的 Tool Result 位于 Provider conversation history，而不是 ContextComposer wrapper 中；
 * 但它仍是本次 Assistant 请求实际看到的外部资料，因此必须作为 TOOL_RESULT ContextRef 一并冻结。
 */
internal fun buildToolResultContextRefEntities(
    conversationId: String,
    assistantMessageId: String,
    toolCalls: List<LlmToolCallEntity>,
    createdAt: Long = System.currentTimeMillis(),
): List<LlmContextRefEntity> =
    toolCalls.mapNotNull { call ->
        val content =
            when (call.status) {
                LlmToolCallStatus.COMPLETE -> call.resultContent.orEmpty()
                LlmToolCallStatus.DENIED ->
                    call.resultContent ?: "Tool execution was denied by the user."
                LlmToolCallStatus.ERROR ->
                    call.resultContent ?: "Tool execution failed: ${call.errorMessage.orEmpty()}"
                LlmToolCallStatus.PENDING_APPROVAL,
                LlmToolCallStatus.RUNNING -> return@mapNotNull null
            }
        if (content.isBlank()) return@mapNotNull null
        LlmContextRefEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            assistantMessageId = assistantMessageId,
            contextId = "tool-result:${call.id}",
            type = me.ash.reader.llm.runtime.LlmContextType.TOOL_RESULT,
            title = call.apiName,
            sourceId = call.toolId,
            sourceUrl = null,
            contentSnapshot = content,
            promptContentSnapshot = content,
            contentSha256 = content.sha256(),
            priority = TOOL_RESULT_HISTORY_PRIORITY,
            includedInPrompt = true,
            truncatedInPrompt = false,
            createdAt = createdAt,
        )
    }

/** ContextRef 只把真正可打开的 HTTP(S) 来源暴露为 URL；MCP sourceId 等内部标识仍只保存在 sourceId。 */
private fun String.asHttpSourceUrlOrNull(): String? {
    val normalized = trim()
    return normalized.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
    }
}

/** 内容哈希用于识别后续来源变化；不参与权限或执行判断。 */
private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

/** Tool Result 通过 Provider history 进入模型，不参与 ContextComposer 排序；该值仅用于来源 UI 的稳定排序。 */
private const val TOOL_RESULT_HISTORY_PRIORITY = 115
