package me.ash.reader.llm.mcp

import java.util.UUID
import me.ash.reader.llm.runtime.LlmToolDescriptor
import me.ash.reader.llm.runtime.LlmToolRisk
import me.ash.reader.llm.runtime.LlmToolSource
import org.json.JSONObject

/** Remote MCP 的认证类型；OAuth 走独立 OAuth 2.1 授权链，不与手填 Bearer 混用。 */
enum class McpAuthType {
    NONE,
    BEARER,
    CUSTOM_HEADERS,
    OAUTH,
}

/** 预注册 OAuth Client 的 Token Endpoint 认证方式；DCR 的 native client 默认使用 NONE。 */
enum class McpOAuthClientAuthMethod {
    NONE,
    CLIENT_SECRET_POST,
    CLIENT_SECRET_BASIC,
}

/** 用户可选的预注册 OAuth Client。clientId 为空表示授权时尝试 DCR。 */
data class McpOAuthClientConfig(
    val clientId: String = "",
    val clientSecret: String = "",
    val authMethod: McpOAuthClientAuthMethod = McpOAuthClientAuthMethod.NONE,
)

/** 与某个 Authorization Server issuer 绑定的 OAuth Client 注册结果。 */
data class McpOAuthClientRegistration(
    val issuer: String,
    val clientId: String,
    val clientSecret: String = "",
    val authMethod: McpOAuthClientAuthMethod = McpOAuthClientAuthMethod.NONE,
)

/** OAuth Token 与授权范围；access/refresh token 仅进入 Keystore 加密存储。 */
data class McpOAuthTokenSet(
    val issuer: String,
    val accessToken: String,
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    val scope: String = "",
    val expiresAtEpochMs: Long = Long.MAX_VALUE,
) {
    fun isAccessTokenUsable(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() &&
            tokenType.equals("Bearer", ignoreCase = true) &&
            nowEpochMs + 30_000L < expiresAtEpochMs
}

/** Resource Server 返回的 Bearer challenge，用于发现 metadata 与 step-up scope。 */
data class McpAuthorizationChallenge(
    val statusCode: Int,
    val wwwAuthenticate: String? = null,
)

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

open class McpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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

