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
            } ?: client.discoverTools(
                profile = profile,
                bearerToken = repository.bearerToken(serverId),
                customHeaders = repository.customHeaders(serverId),
                forceRefresh = forceRefresh,
            )
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
) : LlmTool {
    override val descriptor = definition.descriptor(profile.id)

    override suspend fun execute(argumentsJson: String): LlmToolResult {
        val result =
            client.callTool(
                profile = profile,
                bearerToken = repository.bearerToken(profile.id),
                customHeaders = repository.customHeaders(profile.id),
                toolName = definition.name,
                argumentsJson = argumentsJson,
            )
        return if (result.isError) {
            LlmToolResult.Failure(result.content.ifBlank { "MCP Tool 返回错误" })
        } else {
            LlmToolResult.Success(result.content)
        }
    }
}
