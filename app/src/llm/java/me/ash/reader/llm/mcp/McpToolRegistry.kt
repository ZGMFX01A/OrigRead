package me.ash.reader.llm.mcp

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.llm.runtime.LlmTool
import me.ash.reader.llm.runtime.LlmToolResult
import me.ash.reader.llm.runtime.LlmToolRuntime

/** 将 Remote MCP discovery 结果桥接到 P2 统一 LlmToolRuntime。 */
@Singleton
class McpToolRegistry @Inject constructor(
    private val repository: McpServerRepository,
    private val client: McpRemoteClient,
    private val oauthManager: McpOAuthManager,
    private val toolRuntime: LlmToolRuntime,
) {
    suspend fun refreshServer(serverId: String, forceRefresh: Boolean = true): McpToolCatalog {
        val profile = repository.server(serverId) ?: throw McpException("MCP Server 不存在")
        if (!repository.isConfigured(profile)) throw McpException("MCP Server 尚未完成配置：${profile.name}")
        val catalog =
            if (!forceRefresh) {
                repository.cachedCatalog(serverId)?.takeIf(McpToolCatalog::isFresh)
            } else {
                null
            } ?: discoverToolsWithAuth(profile, forceRefresh)
        repository.saveCatalog(catalog)
        registerCatalog(profile, catalog)
        return catalog
    }

    /** 应用重启后可先恢复上次 discovery 的 Tool 描述；真正执行时仍重新建立远端连接。 */
    fun restoreCachedTools() {
        repository.currentServers()
            .filter(McpServerProfile::enabled)
            .forEach { profile ->
                repository.cachedCatalog(profile.id)?.let { registerCatalog(profile, it) }
            }
    }

    fun unloadServer(serverId: String) {
        unregisterTools(serverId)
        client.invalidate(serverId)
    }

    /** OAuth 401 只允许 refresh + retry 一次；403 scope challenge 留给显式重新授权。 */
    private suspend fun discoverToolsWithAuth(
        profile: McpServerProfile,
        forceRefresh: Boolean,
    ): McpToolCatalog {
        val initialToken = authToken(profile)
        // OAuth 刷新后的重试必须复用完全相同的用户 Header；否则网关/租户前置鉴权会在刷新成功后反而失败。
        val customHeaders = repository.customHeaders(profile.id)
        return try {
            client.discoverTools(
                profile = profile,
                bearerToken = initialToken,
                customHeaders = customHeaders,
                forceRefresh = forceRefresh,
            )
        } catch (error: McpAuthorizationException) {
            if (profile.authType != McpAuthType.OAUTH) throw error
            if (error.statusCode == 403) {
                oauthManager.recordScopeChallenge(profile.id, error.wwwAuthenticate)
                throw McpException("MCP OAuth 权限范围不足，请重新授权此 Server")
            }
            val refreshed = oauthManager.accessToken(profile, forceRefresh = true)
            client.invalidate(profile.id)
            client.discoverTools(
                profile = profile,
                bearerToken = refreshed,
                customHeaders = customHeaders,
                forceRefresh = true,
            )
        }
    }

    private suspend fun authToken(profile: McpServerProfile): String =
        when (profile.authType) {
            McpAuthType.BEARER -> repository.bearerToken(profile.id)
            McpAuthType.OAUTH -> oauthManager.accessToken(profile)
            McpAuthType.NONE,
            McpAuthType.CUSTOM_HEADERS -> ""
        }

    private fun unregisterTools(serverId: String) {
        toolRuntime.descriptors()
            .filter { it.sourceId == serverId }
            .forEach { toolRuntime.unregister(it.id) }
    }

    private fun registerCatalog(profile: McpServerProfile, catalog: McpToolCatalog) {
        // refresh 时只替换本地 Tool 描述，不清理刚完成协商的 legacy session。
        unregisterTools(profile.id)
        catalog.tools.forEach { definition ->
            toolRuntime.register(
                RemoteMcpTool(
                    profile = profile,
                    definition = definition,
                    repository = repository,
                    client = client,
                    oauthManager = oauthManager,
                )
            )
        }
    }
}

private class RemoteMcpTool(
    private val profile: McpServerProfile,
    private val definition: McpToolDefinition,
    private val repository: McpServerRepository,
    private val client: McpRemoteClient,
    private val oauthManager: McpOAuthManager,
) : LlmTool {
    override val descriptor = definition.descriptor(profile.id)

    override suspend fun execute(argumentsJson: String): LlmToolResult {
        // 一次 Tool 调用冻结本次自定义 Header，OAuth 401 刷新后只替换 Token，不改变其余请求身份信息。
        val customHeaders = repository.customHeaders(profile.id)
        val initialToken =
            when (profile.authType) {
                McpAuthType.BEARER -> repository.bearerToken(profile.id)
                McpAuthType.OAUTH -> oauthManager.accessToken(profile)
                McpAuthType.NONE,
                McpAuthType.CUSTOM_HEADERS -> ""
            }
        val result =
            try {
                client.callTool(
                    profile = profile,
                    bearerToken = initialToken,
                    customHeaders = customHeaders,
                    toolName = definition.name,
                    argumentsJson = argumentsJson,
                )
            } catch (error: McpAuthorizationException) {
                if (profile.authType != McpAuthType.OAUTH) throw error
                if (error.statusCode == 403) {
                    oauthManager.recordScopeChallenge(profile.id, error.wwwAuthenticate)
                    throw McpException("MCP OAuth 权限范围不足，请在 MCP 设置中重新授权")
                }
                val refreshed = oauthManager.accessToken(profile, forceRefresh = true)
                client.invalidate(profile.id)
                client.callTool(
                    profile = profile,
                    bearerToken = refreshed,
                    customHeaders = customHeaders,
                    toolName = definition.name,
                    argumentsJson = argumentsJson,
                )
            }
        return if (result.isError) {
            LlmToolResult.Failure(result.content.ifBlank { "MCP Tool 返回错误" })
        } else {
            LlmToolResult.Success(result.content)
        }
    }
}
