package me.ash.reader.llm.mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.awaitResponseAndUse
import me.ash.reader.BuildConfig
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * P5-B Remote MCP Streamable HTTP 客户端。
 *
 * 优先使用 2026-07-28 无状态协议；仅在服务端明确不理解现代 discovery 时回退到
 * 2025-era initialize/initialized 握手，兼容现有部署而不把新实现锁死在旧协议。
 */
@Singleton
class McpRemoteClient @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val requestIds = AtomicLong(1L)
    private val connections = ConcurrentHashMap<String, McpConnection>()

    /**
     * OAuth 首次授权前探测 Resource Server challenge。
     * 只返回 HTTP 状态与 WWW-Authenticate，不把未授权响应误当成协议错误。
     */
    suspend fun authorizationChallenge(profile: McpServerProfile): McpAuthorizationChallenge =
        withContext(ioDispatcher) {
            val id = nextId()
            val response =
                send(
                    profile = profile.copy(authType = McpAuthType.NONE),
                    bearerToken = "",
                    customHeaders = emptyMap(),
                    body = rpcRequest(id, "server/discover", JSONObject().put("_meta", modernMeta())),
                    protocolVersion = MODERN_PROTOCOL_VERSION,
                    method = "server/discover",
                    name = null,
                    expectedId = id,
                )
            McpAuthorizationChallenge(response.statusCode, response.wwwAuthenticate)
        }

    suspend fun discoverTools(
        profile: McpServerProfile,
        bearerToken: String,
        customHeaders: Map<String, String> = emptyMap(),
        forceRefresh: Boolean = false,
    ): McpToolCatalog =
        withContext(ioDispatcher) {
            val connection =
                if (forceRefresh) connect(profile, bearerToken, customHeaders, forceReconnect = true)
                else connect(profile, bearerToken, customHeaders)
            listAllTools(profile, bearerToken, customHeaders, connection)
        }

    suspend fun callTool(
        profile: McpServerProfile,
        bearerToken: String,
        toolName: String,
        argumentsJson: String,
        customHeaders: Map<String, String> = emptyMap(),
    ): McpCallToolResult =
        withContext(ioDispatcher) {
            val connection = connect(profile, bearerToken, customHeaders)
            val arguments =
                runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }
                    .getOrElse { throw McpException("Tool 参数不是有效 JSON object", it) }
            val params = JSONObject().put("name", toolName).put("arguments", arguments)
            val response =
                request(
                    profile = profile,
                    bearerToken = bearerToken,
                    customHeaders = customHeaders,
                    connection = connection,
                    method = "tools/call",
                    params = params,
                    name = toolName,
                )
            val result = requireResult(response, "tools/call")
            normalizeCallToolResult(result)
        }

    fun invalidate(serverId: String) {
        connections.remove(serverId)
    }

    private suspend fun connect(
        profile: McpServerProfile,
        bearerToken: String,
        customHeaders: Map<String, String>,
        forceReconnect: Boolean = false,
    ): McpConnection {
        if (!forceReconnect) connections[profile.id]?.let { return it }

        val modernProbe = modernDiscover(profile, bearerToken, customHeaders)
        if (modernProbe != null) {
            val connection =
                McpConnection(
                    era = McpProtocolEra.MODERN,
                    protocolVersion = MODERN_PROTOCOL_VERSION,
                    serverName = modernProbe.serverName,
                )
            connections[profile.id] = connection
            return connection
        }

        val legacy = initializeLegacy(profile, bearerToken, customHeaders)
        connections[profile.id] = legacy
        return legacy
    }

    /** 返回 null 仅表示“服务端不支持现代 era”，认证/网络/服务端 5xx 不允许伪装成协议降级。 */
    private suspend fun modernDiscover(
        profile: McpServerProfile,
        bearerToken: String,
        customHeaders: Map<String, String>,
    ): ModernDiscovery? {
        val id = nextId()
        val params = JSONObject().put("_meta", modernMeta())
        val response =
            send(
                profile = profile,
                bearerToken = bearerToken,
                customHeaders = customHeaders,
                body = rpcRequest(id, "server/discover", params),
                protocolVersion = MODERN_PROTOCOL_VERSION,
                method = "server/discover",
                name = null,
                expectedId = id,
            )
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw McpAuthorizationException(response.statusCode, response.wwwAuthenticate)
        }
        val errorCode = response.payload?.optJSONObject("error")?.optInt("code")
        if (errorCode == MCP_HEADER_MISMATCH) {
            throw McpException("MCP 2026-07-28 请求头与请求体不一致，拒绝降级到旧协议")
        }
        val unsupported =
            response.statusCode in setOf(404, 405) ||
                errorCode == JSON_RPC_METHOD_NOT_FOUND ||
                errorCode == MCP_UNSUPPORTED_VERSION ||
                (response.statusCode == 400 && errorCode != MCP_HEADER_MISMATCH)
        if (unsupported) return null
        if (response.statusCode >= 500) throw McpException("MCP Server 暂不可用：HTTP ${response.statusCode}")
        val result = requireResult(response, "server/discover")
        val versions = result.optJSONArray("supportedVersions")
        if (versions != null && (0 until versions.length()).none { versions.optString(it) == MODERN_PROTOCOL_VERSION }) {
            return null
        }
        val serverInfo = result.optJSONObject("_meta")?.optJSONObject(SERVER_INFO_META_KEY)
        return ModernDiscovery(serverName = serverInfo?.optString("name")?.takeIf(String::isNotBlank))
    }

    private suspend fun initializeLegacy(
        profile: McpServerProfile,
        bearerToken: String,
        customHeaders: Map<String, String>,
    ): McpConnection {
        val id = nextId()
        val params =
            JSONObject()
                .put("protocolVersion", LEGACY_PREFERRED_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", clientInfo())
        val response =
            send(
                profile = profile,
                bearerToken = bearerToken,
                customHeaders = customHeaders,
                body = rpcRequest(id, "initialize", params),
                protocolVersion = null,
                method = null,
                name = null,
                expectedId = id,
            )
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw McpAuthorizationException(response.statusCode, response.wwwAuthenticate)
        }
        val result = requireResult(response, "initialize")
        val protocolVersion = result.optString("protocolVersion").ifBlank { LEGACY_PREFERRED_VERSION }
        val sessionId = response.sessionId
        val serverName = result.optJSONObject("serverInfo")?.optString("name")?.takeIf(String::isNotBlank)
        val connection =
            McpConnection(
                era = McpProtocolEra.HANDSHAKE,
                protocolVersion = protocolVersion,
                sessionId = sessionId,
                serverName = serverName,
            )
        sendNotification(
            profile = profile,
            bearerToken = bearerToken,
            customHeaders = customHeaders,
            connection = connection,
            method = "notifications/initialized",
            params = JSONObject(),
        )
        return connection
    }

    private suspend fun listAllTools(
        profile: McpServerProfile,
        bearerToken: String,
        customHeaders: Map<String, String>,
        connection: McpConnection,
    ): McpToolCatalog {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        var ttlMs = 0L
        var cacheScope = "private"
        repeat(MAX_LIST_PAGES) {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val response =
                request(
                    profile = profile,
                    bearerToken = bearerToken,
                    customHeaders = customHeaders,
                    connection = connection,
                    method = "tools/list",
                    params = params,
                    name = null,
                )
            val result = requireResult(response, "tools/list")
            ttlMs = result.optLong("ttlMs", ttlMs)
            cacheScope = result.optString("cacheScope", cacheScope)
            parseTools(result.optJSONArray("tools"))?.let(tools::addAll)
            cursor = result.optString("nextCursor").takeIf(String::isNotBlank)
            if (cursor == null) {
                return McpToolCatalog(
                    serverId = profile.id,
                    protocolVersion = connection.protocolVersion,
                    era = connection.era,
                    serverName = connection.serverName,
                    tools = tools.distinctBy(McpToolDefinition::name),
                    ttlMs = ttlMs.coerceAtLeast(0L),
                    cacheScope = cacheScope.ifBlank { "private" },
                )
            }
        }
        throw McpException("MCP tools/list 分页超过安全上限 $MAX_LIST_PAGES")
    }

    private suspend fun request(
        profile: McpServerProfile,
        bearerToken: String,
        customHeaders: Map<String, String>,
        connection: McpConnection,
        method: String,
        params: JSONObject,
        name: String?,
    ): McpHttpResponse {
        val id = nextId()
        if (connection.era == McpProtocolEra.MODERN) {
            params.put("_meta", modernMeta())
        }
        return send(
            profile = profile,
            bearerToken = bearerToken,
            customHeaders = customHeaders,
            body = rpcRequest(id, method, params),
            protocolVersion = connection.protocolVersion,
            method = if (connection.era == McpProtocolEra.MODERN) method else null,
            name = if (connection.era == McpProtocolEra.MODERN) name else null,
            sessionId = connection.sessionId,
            expectedId = id,
        )
    }

    private suspend fun sendNotification(
        profile: McpServerProfile,
        bearerToken: String,
        customHeaders: Map<String, String>,
        connection: McpConnection,
        method: String,
        params: JSONObject,
    ) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        val response =
            send(
                profile = profile,
                bearerToken = bearerToken,
                customHeaders = customHeaders,
                body = body,
                protocolVersion = connection.protocolVersion,
                method = null,
                name = null,
                sessionId = connection.sessionId,
            )
        if (response.statusCode !in 200..299) {
            throw McpException("MCP $method 失败：HTTP ${response.statusCode}")
        }
    }

    private suspend fun send(
        profile: McpServerProfile,
        bearerToken: String,
        customHeaders: Map<String, String>,
        body: JSONObject,
        protocolVersion: String?,
        method: String?,
        name: String?,
        sessionId: String? = null,
        expectedId: Any? = null,
    ): McpHttpResponse {
        val request =
            Request.Builder()
                .url(profile.endpoint)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .apply {
                    if (
                        profile.authType in setOf(McpAuthType.BEARER, McpAuthType.OAUTH) &&
                            bearerToken.isNotBlank()
                    ) {
                        header("Authorization", "Bearer $bearerToken")
                    }
                    if (profile.authType == McpAuthType.CUSTOM_HEADERS) {
                        customHeaders
                            .filterKeys(::isAllowedCustomHeader)
                            .forEach { (headerName, headerValue) -> header(headerName, headerValue) }
                    }
                    protocolVersion?.let { header("MCP-Protocol-Version", it) }
                    method?.let { header("Mcp-Method", it) }
                    name?.let { header("Mcp-Name", it) }
                    sessionId?.let { header("Mcp-Session-Id", it) }
                }
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        // MCP 写操作取消只承诺停止等待并取消 Call；远端已执行的副作用无法由客户端撤销。
        return httpClient.client.newCall(request).awaitResponseAndUse { response ->
            parseHttpResponse(response, expectedId)
        }
    }

    private fun parseHttpResponse(response: Response, expectedId: Any?): McpHttpResponse {
        val raw = response.body?.string().orEmpty()
        val payload =
            when {
                raw.isBlank() -> null
                response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true) ->
                    parseSseJsonRpc(raw, expectedId)
                else ->
                    runCatching { JSONObject(raw) }.getOrNull()
                        ?.takeIf { payload -> expectedId == null || jsonRpcIdMatches(payload.opt("id"), expectedId) }
            }
        return McpHttpResponse(
            statusCode = response.code,
            payload = payload,
            sessionId = response.header("Mcp-Session-Id"),
            wwwAuthenticate = response.header("WWW-Authenticate"),
        )
    }

    private fun requireResult(response: McpHttpResponse, operation: String): JSONObject {
        if (response.statusCode !in 200..299) {
            if (response.statusCode == 401 || response.statusCode == 403) {
                throw McpAuthorizationException(response.statusCode, response.wwwAuthenticate)
            }
            throw McpException("MCP $operation 失败：HTTP ${response.statusCode}")
        }
        val payload = response.payload ?: throw McpException("MCP $operation 返回空响应")
        payload.optJSONObject("error")?.let { error ->
            throw McpException(
                "MCP $operation 失败：${error.optString("message").ifBlank { "JSON-RPC error ${error.optInt("code")}" }}"
            )
        }
        return payload.optJSONObject("result") ?: throw McpException("MCP $operation 缺少 result")
    }

    private fun modernMeta(): JSONObject =
        JSONObject()
            .put(PROTOCOL_VERSION_META_KEY, MODERN_PROTOCOL_VERSION)
            .put(CLIENT_INFO_META_KEY, clientInfo())
            .put(CLIENT_CAPABILITIES_META_KEY, JSONObject())

    private fun clientInfo(): JSONObject =
        JSONObject().put("name", "OrigRead Android").put("version", BuildConfig.VERSION_NAME)

    private fun nextId(): Long = requestIds.getAndIncrement()
}

private data class McpConnection(
    val era: McpProtocolEra,
    val protocolVersion: String,
    val sessionId: String? = null,
    val serverName: String? = null,
)

private data class ModernDiscovery(val serverName: String?)

private data class McpHttpResponse(
    val statusCode: Int,
    val payload: JSONObject?,
    val sessionId: String?,
    val wwwAuthenticate: String?,
)

/** 保留 Bearer challenge，供 OAuth token refresh / scope step-up 判断。 */
class McpAuthorizationException(
    val statusCode: Int,
    val wwwAuthenticate: String?,
) : McpException("MCP 认证失败：HTTP $statusCode")

private fun rpcRequest(id: Long, method: String, params: JSONObject): JSONObject =
    JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

private fun parseTools(array: JSONArray?): List<McpToolDefinition>? {
    if (array == null) return null
    return buildList {
        repeat(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@repeat
            val name = item.optString("name").trim()
            if (name.isBlank()) return@repeat
            val inputSchema = item.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
            val outputSchema = item.optJSONObject("outputSchema")
            add(
                McpToolDefinition(
                    name = name,
                    title = item.optString("title").takeIf(String::isNotBlank),
                    description = item.optString("description"),
                    inputSchemaJson = inputSchema.toString(),
                    outputSchemaJson = outputSchema?.toString(),
                    risk = inferMcpToolRisk(item.optJSONObject("annotations")),
                )
            )
        }
    }
}

private fun normalizeCallToolResult(result: JSONObject): McpCallToolResult {
    val resultType = result.optString("resultType").takeIf(String::isNotBlank)
    if (resultType == "input_required" || resultType == "task") {
        return McpCallToolResult(
            content = "MCP 返回 $resultType；当前 Android 首期尚未完成该多轮交互扩展。",
            isError = true,
            resultType = resultType,
        )
    }
    val parts = mutableListOf<String>()
    val content = result.optJSONArray("content")
    if (content != null) {
        repeat(content.length()) { index ->
            val item = content.optJSONObject(index) ?: return@repeat
            when (item.optString("type")) {
                "text" -> item.optString("text").takeIf(String::isNotBlank)?.let(parts::add)
                "resource_link" -> {
                    val uri = item.optString("uri")
                    val name = item.optString("name").ifBlank { "Resource" }
                    if (uri.isNotBlank()) parts += "$name: $uri"
                }
                "image" -> parts += "[MCP image: ${item.optString("mimeType").ifBlank { "unknown" }}]"
                "audio" -> parts += "[MCP audio: ${item.optString("mimeType").ifBlank { "unknown" }}]"
            }
        }
    }
    val structured = result.optJSONObject("structuredContent")?.toString()
    if (parts.isEmpty() && structured != null) parts += structured
    return McpCallToolResult(
        content = parts.joinToString("\n\n"),
        isError = result.optBoolean("isError", false),
        structuredContentJson = structured,
        resultType = resultType,
    )
}

/** Streamable HTTP 的 POST 可能返回 SSE；只抽取最后一个 JSON-RPC data 事件作为当前响应。 */
internal fun parseSseJsonRpc(raw: String, expectedId: Any? = null): JSONObject? =
    raw.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("data:") }
        .map { it.removePrefix("data:").trim() }
        .filter(String::isNotBlank)
        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        .filter { payload ->
            // notification/progress 没有 id；其他请求的响应也不能抢占当前 request。
            expectedId == null || jsonRpcIdMatches(payload.opt("id"), expectedId)
        }
        .lastOrNull()

/** JSON-RPC id 允许 number/string，但不把不同类型的同形文本视为相同 id。 */
private fun jsonRpcIdMatches(actual: Any?, expected: Any): Boolean =
    when {
        actual is Number && expected is Number -> actual.toLong() == expected.toLong()
        actual is String && expected is String -> actual == expected
        else -> false
    }

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
internal const val MODERN_PROTOCOL_VERSION = "2026-07-28"
internal const val LEGACY_PREFERRED_VERSION = "2025-11-25"
private const val MAX_LIST_PAGES = 20
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val MCP_UNSUPPORTED_VERSION = -32022
private const val MCP_HEADER_MISMATCH = -32020
private const val PROTOCOL_VERSION_META_KEY = "io.modelcontextprotocol/protocolVersion"
private const val CLIENT_INFO_META_KEY = "io.modelcontextprotocol/clientInfo"
private const val CLIENT_CAPABILITIES_META_KEY = "io.modelcontextprotocol/clientCapabilities"
private const val SERVER_INFO_META_KEY = "io.modelcontextprotocol/serverInfo"

/** 用户自定义认证头不能覆盖 MCP 路由、会话或基础 HTTP 头。 */
private fun isAllowedCustomHeader(name: String): Boolean =
    name.trim().lowercase() !in
        setOf(
            "accept",
            "content-type",
            "host",
            "mcp-protocol-version",
            "mcp-method",
            "mcp-name",
            "mcp-session-id",
        )
