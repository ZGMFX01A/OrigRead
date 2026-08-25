package me.ash.reader.llm.mcp

import java.util.UUID
import me.ash.reader.llm.runtime.LlmToolDescriptor
import me.ash.reader.llm.runtime.LlmToolRisk
import me.ash.reader.llm.runtime.LlmToolSource
import org.json.JSONObject

/** Android 首期 Remote MCP 的认证类型；OAuth 在 P5-B 后续子任务接入，不伪装成已支持。 */
enum class McpAuthType {
    NONE,
    BEARER,
    CUSTOM_HEADERS,
}

/** MCP 协议时代。现代 2026-07-28 无握手，HANDSHAKE 兼容现存 2025-era Server。 */
enum class McpProtocolEra {
    MODERN,
    HANDSHAKE,
}

/** 用户保存的一条 Remote MCP Server。Token 由 SecureSecretStore 独立保存。 */
data class McpServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpoint: String,
    val enabled: Boolean = true,
    val authType: McpAuthType = McpAuthType.NONE,
)

/** MCP Tool 的稳定本地描述；保留原始 JSON Schema，供 Function Calling 与参数 UI 共同使用。 */
data class McpToolDefinition(
    val name: String,
    val title: String? = null,
    val description: String = "",
    val inputSchemaJson: String,
    val outputSchemaJson: String? = null,
    val risk: LlmToolRisk,
) {
    fun descriptor(serverId: String): LlmToolDescriptor =
        LlmToolDescriptor(
            id = stableToolId(serverId, name),
            name = title?.takeIf(String::isNotBlank) ?: name,
            description = description,
            source = LlmToolSource.MCP,
            sourceId = serverId,
            risk = risk,
            inputSchemaJson = inputSchemaJson,
            outputSchemaJson = outputSchemaJson,
        )
}

/** 一次 Tool discovery 的缓存快照；现代协议遵守服务端 ttlMs，旧协议默认立即过期。 */
data class McpToolCatalog(
    val serverId: String,
    val protocolVersion: String,
    val era: McpProtocolEra,
    val serverName: String? = null,
    val tools: List<McpToolDefinition>,
    val fetchedAtEpochMs: Long = System.currentTimeMillis(),
    val ttlMs: Long = 0L,
    val cacheScope: String = "private",
) {
    fun isFresh(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        ttlMs > 0 && nowEpochMs - fetchedAtEpochMs < ttlMs
}

/** MCP tools/call 归一化结果。MRTR/Task 暂不在这里伪装成普通成功文本。 */
data class McpCallToolResult(
    val content: String,
    val isError: Boolean,
    val structuredContentJson: String? = null,
    val resultType: String? = null,
)

class McpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** MCP Tool 在本地 Runtime 使用的稳定 ID，避免不同 Server 的同名 Tool 冲突。 */
fun stableToolId(serverId: String, toolName: String): String = "mcp:$serverId:$toolName"

/** MCP annotations 缺省不能假定安全；只有明确 readOnlyHint=true 才免确认。 */
internal fun inferMcpToolRisk(annotations: JSONObject?): LlmToolRisk =
    when {
        annotations?.optBoolean("destructiveHint", false) == true -> LlmToolRisk.WRITE
        annotations?.has("readOnlyHint") == true && annotations.optBoolean("readOnlyHint") ->
            LlmToolRisk.READ_ONLY
        else -> LlmToolRisk.SENSITIVE
    }

