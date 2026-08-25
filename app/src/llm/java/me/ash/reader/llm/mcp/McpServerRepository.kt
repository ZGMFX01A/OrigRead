package me.ash.reader.llm.mcp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ash.reader.infrastructure.translation.SecureSecretStore
import me.ash.reader.llm.runtime.LlmToolRisk
import org.json.JSONArray
import org.json.JSONObject

/** Remote MCP Server 与 Tool Catalog 的 LLM-edition 私有仓储。 */
@Singleton
class McpServerRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secretStore: SecureSecretStore,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _servers = MutableStateFlow(readServers())
    val servers: StateFlow<List<McpServerProfile>> = _servers.asStateFlow()

    fun currentServers(): List<McpServerProfile> = _servers.value

    fun addServer(
        name: String,
        endpoint: String,
        authType: McpAuthType = McpAuthType.NONE,
    ): String {
        val profile =
            McpServerProfile(
                name = normalizeName(name),
                endpoint = normalizeEndpoint(endpoint),
                authType = authType,
            )
        updateServers { it + profile }
        return profile.id
    }

    fun updateServer(profile: McpServerProfile) {
        val normalized =
            profile.copy(
                name = normalizeName(profile.name),
                endpoint = normalizeEndpoint(profile.endpoint),
            )
        updateServers { servers ->
            servers.map { if (it.id == profile.id) normalized else it }
        }
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        updateServers { servers ->
            servers.map { if (it.id == serverId) it.copy(enabled = enabled) else it }
        }
    }

    fun removeServer(serverId: String) {
        secretStore.remove(secretKey(serverId))
        secretStore.remove(customHeadersKey(serverId))
        secretStore.remove(oauthClientConfigKey(serverId))
        secretStore.remove(oauthRegistrationKey(serverId))
        secretStore.remove(oauthTokenKey(serverId))
        preferences.edit().remove(oauthPendingScopesKey(serverId)).apply()
        preferences.edit().remove(catalogKey(serverId)).apply()
        updateServers { servers -> servers.filterNot { it.id == serverId } }
    }

    fun setBearerToken(serverId: String, token: String) {
        secretStore.put(secretKey(serverId), token.trim())
    }

    fun bearerToken(serverId: String): String = secretStore.get(secretKey(serverId))

    fun hasBearerToken(serverId: String): Boolean = bearerToken(serverId).isNotBlank()

    /** 自定义 Header 可能包含 API Key 等敏感值，因此整体进入 Keystore 加密存储。 */
    fun setCustomHeaders(serverId: String, headers: Map<String, String>) {
        val payload = JSONObject()
        headers.forEach { (name, value) -> payload.put(name, value) }
        secretStore.put(customHeadersKey(serverId), payload.toString())
    }

    fun customHeaders(serverId: String): Map<String, String> =
        secretStore.get(customHeadersKey(serverId))
            .takeIf(String::isNotBlank)
            ?.let { raw ->
                runCatching {
                        val root = JSONObject(raw)
                        buildMap {
                            root.keys().forEach { name ->
                                val value = root.optString(name)
                                if (name.isNotBlank() && value.isNotBlank()) put(name, value)
                            }
                        }
                    }
                    .getOrDefault(emptyMap())
            }
            ?: emptyMap()

    fun hasCustomHeaders(serverId: String): Boolean = customHeaders(serverId).isNotEmpty()

    /** 预注册 OAuth Client 可能包含 client_secret，因此整份配置使用 Keystore 加密。 */
    fun setOAuthClientConfig(serverId: String, config: McpOAuthClientConfig) {
        if (config.clientId.isBlank() && config.clientSecret.isBlank()) {
            secretStore.remove(oauthClientConfigKey(serverId))
            return
        }
        secretStore.put(
            oauthClientConfigKey(serverId),
            JSONObject()
                .put("clientId", config.clientId.trim())
                .put("clientSecret", config.clientSecret)
                .put("authMethod", config.authMethod.name)
                .toString(),
        )
    }

    fun oauthClientConfig(serverId: String): McpOAuthClientConfig =
        secretStore.get(oauthClientConfigKey(serverId))
            .takeIf(String::isNotBlank)
            ?.let { raw ->
                runCatching {
                        val root = JSONObject(raw)
                        McpOAuthClientConfig(
                            clientId = root.optString("clientId"),
                            clientSecret = root.optString("clientSecret"),
                            authMethod =
                                runCatching {
                                        McpOAuthClientAuthMethod.valueOf(root.optString("authMethod"))
                                    }
                                    .getOrDefault(McpOAuthClientAuthMethod.NONE),
                        )
                    }
                    .getOrDefault(McpOAuthClientConfig())
            }
            ?: McpOAuthClientConfig()

    /** DCR / 预注册最终使用的 Client 必须与发现出的 issuer 绑定，禁止跨 AS 复用。 */
    fun setOAuthRegistration(serverId: String, registration: McpOAuthClientRegistration) {
        secretStore.put(
            oauthRegistrationKey(serverId),
            JSONObject()
                .put("issuer", registration.issuer)
                .put("clientId", registration.clientId)
                .put("clientSecret", registration.clientSecret)
                .put("authMethod", registration.authMethod.name)
                .toString(),
        )
    }

    fun oauthRegistration(serverId: String): McpOAuthClientRegistration? =
        secretStore.get(oauthRegistrationKey(serverId))
            .takeIf(String::isNotBlank)
            ?.let { raw ->
                runCatching {
                        val root = JSONObject(raw)
                        McpOAuthClientRegistration(
                            issuer = root.getString("issuer"),
                            clientId = root.getString("clientId"),
                            clientSecret = root.optString("clientSecret"),
                            authMethod =
                                runCatching {
                                        McpOAuthClientAuthMethod.valueOf(root.optString("authMethod"))
                                    }
                                    .getOrDefault(McpOAuthClientAuthMethod.NONE),
                        )
                    }
                    .getOrNull()
            }

    fun setOAuthTokenSet(serverId: String, tokenSet: McpOAuthTokenSet) {
        secretStore.put(
            oauthTokenKey(serverId),
            JSONObject()
                .put("issuer", tokenSet.issuer)
                .put("accessToken", tokenSet.accessToken)
                .put("refreshToken", tokenSet.refreshToken)
                .put("tokenType", tokenSet.tokenType)
                .put("scope", tokenSet.scope)
                .put("expiresAt", tokenSet.expiresAtEpochMs)
                .toString(),
        )
    }

    fun oauthTokenSet(serverId: String): McpOAuthTokenSet? =
        secretStore.get(oauthTokenKey(serverId))
            .takeIf(String::isNotBlank)
            ?.let { raw ->
                runCatching {
                        val root = JSONObject(raw)
                        McpOAuthTokenSet(
                            issuer = root.getString("issuer"),
                            accessToken = root.getString("accessToken"),
                            refreshToken = root.optString("refreshToken"),
                            tokenType = root.optString("tokenType", "Bearer"),
                            scope = root.optString("scope"),
                            expiresAtEpochMs = root.optLong("expiresAt", Long.MAX_VALUE),
                        )
                    }
                    .getOrNull()
            }

    fun hasOAuthAuthorization(serverId: String): Boolean = oauthTokenSet(serverId)?.accessToken?.isNotBlank() == true

    fun clearOAuthAuthorization(serverId: String, keepClientConfig: Boolean = true) {
        if (!keepClientConfig) secretStore.remove(oauthClientConfigKey(serverId))
        secretStore.remove(oauthRegistrationKey(serverId))
        secretStore.remove(oauthTokenKey(serverId))
        preferences.edit().remove(oauthPendingScopesKey(serverId)).apply()
    }

    /** 403 insufficient_scope 后记录下一次人工重新授权需要并集申请的 scope。 */
    fun setOAuthPendingScopes(serverId: String, scopes: Set<String>) {
        val normalized = scopes.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        preferences.edit()
            .putString(oauthPendingScopesKey(serverId), normalized.joinToString(" "))
            .apply()
    }

    fun oauthPendingScopes(serverId: String): Set<String> =
        preferences.getString(oauthPendingScopesKey(serverId), "").orEmpty()
            .split(' ')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

    fun server(serverId: String): McpServerProfile? = _servers.value.firstOrNull { it.id == serverId }

    fun isConfigured(profile: McpServerProfile): Boolean =
        profile.enabled &&
            profile.endpoint.isNotBlank() &&
            when (profile.authType) {
                McpAuthType.NONE -> true
                McpAuthType.BEARER -> hasBearerToken(profile.id)
                McpAuthType.CUSTOM_HEADERS -> hasCustomHeaders(profile.id)
                McpAuthType.OAUTH -> hasOAuthAuthorization(profile.id)
            }

    /** Tool discovery 结果写入应用私有缓存；协议 TTL 决定是否可跳过下一次刷新。 */
    fun saveCatalog(catalog: McpToolCatalog) {
        preferences.edit().putString(catalogKey(catalog.serverId), catalog.toJson().toString()).apply()
    }

    fun cachedCatalog(serverId: String): McpToolCatalog? =
        preferences.getString(catalogKey(serverId), null)
            ?.let { raw -> runCatching { catalogFromJson(JSONObject(raw)) }.getOrNull() }

    fun clearCatalog(serverId: String) {
        preferences.edit().remove(catalogKey(serverId)).apply()
    }

    private fun updateServers(transform: (List<McpServerProfile>) -> List<McpServerProfile>) {
        val next = transform(_servers.value).distinctBy(McpServerProfile::id)
        persistServers(next)
        _servers.value = next
    }

    private fun readServers(): List<McpServerProfile> =
        runCatching {
                val raw = preferences.getString(KEY_SERVERS, null).orEmpty()
                if (raw.isBlank()) return@runCatching emptyList()
                val array = JSONArray(raw)
                buildList {
                    repeat(array.length()) { index ->
                        val item = array.optJSONObject(index) ?: return@repeat
                        val id = item.optString("id").trim()
                        val endpoint = item.optString("endpoint").trim()
                        if (id.isBlank() || endpoint.isBlank()) return@repeat
                        add(
                            McpServerProfile(
                                id = id,
                                name = item.optString("name").ifBlank { "MCP Server" },
                                endpoint = endpoint,
                                enabled = item.optBoolean("enabled", true),
                                authType =
                                    runCatching { McpAuthType.valueOf(item.optString("authType")) }
                                        .getOrDefault(McpAuthType.NONE),
                            )
                        )
                    }
                }
            }
            .getOrDefault(emptyList())

    private fun persistServers(servers: List<McpServerProfile>) {
        val array = JSONArray()
        servers.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("endpoint", profile.endpoint)
                    .put("enabled", profile.enabled)
                    .put("authType", profile.authType.name)
            )
        }
        preferences.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun normalizeName(value: String): String = value.trim().take(MAX_NAME_LENGTH).ifBlank { "MCP Server" }

    private fun normalizeEndpoint(value: String): String = value.trim()

    private fun secretKey(serverId: String): String = "llm_mcp_bearer:$serverId"

    private fun customHeadersKey(serverId: String): String = "llm_mcp_headers:$serverId"

    private fun oauthClientConfigKey(serverId: String): String = "llm_mcp_oauth_client_config:$serverId"

    private fun oauthRegistrationKey(serverId: String): String = "llm_mcp_oauth_registration:$serverId"

    private fun oauthTokenKey(serverId: String): String = "llm_mcp_oauth_tokens:$serverId"

    private fun oauthPendingScopesKey(serverId: String): String = "oauth_pending_scopes:$serverId"

    private fun catalogKey(serverId: String): String = "catalog:$serverId"

    companion object {
        private const val PREFERENCES_NAME = "origread_llm_mcp"
        private const val KEY_SERVERS = "servers"
        private const val MAX_NAME_LENGTH = 80
    }
}

private fun McpToolCatalog.toJson(): JSONObject =
    JSONObject()
        .put("serverId", serverId)
        .put("protocolVersion", protocolVersion)
        .put("era", era.name)
        .put("serverName", serverName)
        .put("fetchedAt", fetchedAtEpochMs)
        .put("ttlMs", ttlMs)
        .put("cacheScope", cacheScope)
        .put(
            "tools",
            JSONArray().apply {
                tools.forEach { tool ->
                    put(
                        JSONObject()
                            .put("name", tool.name)
                            .put("title", tool.title)
                            .put("description", tool.description)
                            .put("inputSchema", tool.inputSchemaJson)
                            .put("outputSchema", tool.outputSchemaJson)
                            .put("risk", tool.risk.name)
                    )
                }
            },
        )

private fun catalogFromJson(root: JSONObject): McpToolCatalog {
    val tools = root.optJSONArray("tools")
    return McpToolCatalog(
        serverId = root.getString("serverId"),
        protocolVersion = root.getString("protocolVersion"),
        era = McpProtocolEra.valueOf(root.getString("era")),
        serverName = root.optString("serverName").takeIf(String::isNotBlank),
        fetchedAtEpochMs = root.optLong("fetchedAt"),
        ttlMs = root.optLong("ttlMs"),
        cacheScope = root.optString("cacheScope", "private"),
        tools =
            buildList {
                if (tools != null) {
                    repeat(tools.length()) { index ->
                        val item = tools.optJSONObject(index) ?: return@repeat
                        add(
                            McpToolDefinition(
                                name = item.getString("name"),
                                title = item.optString("title").takeIf(String::isNotBlank),
                                description = item.optString("description"),
                                inputSchemaJson = item.optString("inputSchema", "{\"type\":\"object\"}"),
                                outputSchemaJson = item.optString("outputSchema").takeIf(String::isNotBlank),
                                risk =
                                    runCatching { LlmToolRisk.valueOf(item.optString("risk")) }
                                        .getOrDefault(LlmToolRisk.SENSITIVE),
                            )
                        )
                    }
                }
            },
    )
}
