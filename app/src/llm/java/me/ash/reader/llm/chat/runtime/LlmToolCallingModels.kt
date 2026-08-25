package me.ash.reader.llm.chat.runtime

import java.security.MessageDigest
import me.ash.reader.llm.runtime.LlmToolDescriptor

/** Assistant 历史中回传给 Provider 的完整 Tool Call。 */
data class LlmChatRequestToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** Streaming Chat Completions 的增量 Tool Call；arguments 可能跨多个 chunk 拼接。 */
data class LlmChatToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsDelta: String = "",
)

/**
 * 将内部 Tool ID 映射成 Provider 可接受且稳定的 function name。
 *
 * MCP Server 可能存在同名 Tool，因此函数名包含内部 ID 的短 hash；可读部分只保留常见安全字符，
 * 最终限制在 64 字符内，避免把 `mcp:<serverId>:<tool>` 这类内部命名直接发送给兼容服务。
 */
internal fun LlmToolDescriptor.toApiFunctionName(): String {
    val readable =
        name.map { char -> if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_' }
            .joinToString("")
            .trim('_')
            .ifBlank { "tool" }
            .take(42)
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .take(5)
            .joinToString("") { byte -> "%02x".format(byte) }
    return "or_${digest}_$readable".take(64)
}

/** 根据 Provider 返回的 function name 安全反解到本轮 ExecutionPlan 中真实允许的 Tool。 */
internal fun resolveToolByApiName(
    tools: List<LlmToolDescriptor>,
    apiName: String,
): LlmToolDescriptor? = tools.firstOrNull { it.toApiFunctionName() == apiName }

