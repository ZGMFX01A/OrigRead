package me.ash.reader.infrastructure.backup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.ash.reader.llm.mcp.McpAuthType
import me.ash.reader.llm.mcp.McpOAuthClientAuthMethod
import me.ash.reader.llm.mcp.McpOAuthClientConfig
import me.ash.reader.llm.mcp.McpOAuthClientRegistration
import me.ash.reader.llm.mcp.McpOAuthTokenSet
import me.ash.reader.llm.mcp.McpServerBackupSecrets
import me.ash.reader.llm.mcp.McpServerProfile
import me.ash.reader.llm.mcp.McpServerRepository
import me.ash.reader.llm.mcp.McpToolRegistry
import me.ash.reader.llm.quickmessage.LlmQuickMessageRepository
import me.ash.reader.llm.runtime.LlmReasoningEffort
import me.ash.reader.llm.search.MAX_WEB_SEARCH_MAX_RESULTS
import me.ash.reader.llm.search.MIN_WEB_SEARCH_MAX_RESULTS
import me.ash.reader.llm.search.WebSearchMode
import me.ash.reader.llm.search.WebSearchProviderKind
import me.ash.reader.llm.search.WebSearchProviderProfile
import me.ash.reader.llm.search.WebSearchRepository
import me.ash.reader.llm.search.WebSearchSettings
import me.ash.reader.llm.settings.LlmAdvancedSettings
import me.ash.reader.llm.settings.LlmSettingsRepository
import me.ash.reader.llm.skill.LlmSkillRepository

/** OrigRead / OrigRead X 共用的完整 AI 扩展配置备份桥；公共备份服务本身不需要认识任何 llm 类型。 */
@Singleton
class EditionConfigurationBackupExtensionImpl @Inject constructor(
    private val llmSettingsRepository: LlmSettingsRepository,
    private val webSearchRepository: WebSearchRepository,
    private val mcpServerRepository: McpServerRepository,
    private val mcpToolRegistry: McpToolRegistry,
    private val skillRepository: LlmSkillRepository,
    private val quickMessageRepository: LlmQuickMessageRepository,
) : EditionConfigurationBackupExtension {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun exportConfiguration(): JsonElement =
        json.encodeToJsonElement(
            LlmEditionConfigurationBackup(
                schemaVersion = CURRENT_LLM_BACKUP_SCHEMA_VERSION,
                settings = llmSettingsRepository.current().toBackup(),
                webSearch = webSearchRepository.current().toBackup(),
                mcpServers = mcpServerRepository.currentServers().map(McpServerProfile::toBackup),
                skillState = skillRepository.exportBackupState(),
                quickMessageState = quickMessageRepository.exportBackupState(),
            )
        )

    override fun exportSecrets(): JsonElement =
        json.encodeToJsonElement(
            LlmEditionSecretsBackup(
                webSearchApiKeys =
                    webSearchRepository.current().providers
                        .mapNotNull { provider ->
                            webSearchRepository.getApiKey(provider.id)
                                .takeIf(String::isNotBlank)
                                ?.let { provider.id to it }
                        }
                        .toMap(),
                mcpServers =
                    mcpServerRepository.currentServers().associate { server ->
                        server.id to mcpServerRepository.backupSecrets(server.id).toBackup()
                    },
            )
        )

    override fun validateBackup(configuration: JsonElement?, secrets: JsonElement?) {
        val decoded = configuration?.let { json.decodeFromJsonElement<LlmEditionConfigurationBackup>(it) } ?: return
        decoded.toValidatedDomain()
        decoded.skillState?.let(skillRepository::validateBackupState)
        decoded.quickMessageState?.let(quickMessageRepository::validateBackupState)
        secrets?.let { secretElement ->
            val decodedSecrets = json.decodeFromJsonElement<LlmEditionSecretsBackup>(secretElement)
            val searchIds = decoded.webSearch.providers.mapTo(hashSetOf(), WebSearchProviderBackup::id)
            require(decodedSecrets.webSearchApiKeys.keys.all { it in searchIds }) {
                "Web Search 凭据引用了不存在的 Provider"
            }
            val mcpIds = decoded.mcpServers.mapTo(hashSetOf(), McpServerProfileBackup::id)
            require(decodedSecrets.mcpServers.keys.all { it in mcpIds }) {
                "MCP 凭据引用了不存在的 Server"
            }
            decodedSecrets.mcpServers.values.forEach(McpServerSecretsBackup::toDomain)
        }
    }

    override fun restoreBackup(
        configuration: JsonElement?,
        secrets: JsonElement?,
        replaceSecrets: Boolean,
    ) {
        val decoded = configuration?.let { json.decodeFromJsonElement<LlmEditionConfigurationBackup>(it) } ?: return
        val domain = decoded.toValidatedDomain()
        val decodedSecrets = secrets?.let { json.decodeFromJsonElement<LlmEditionSecretsBackup>(it) }
        // 老版本加密块可能存在但没有 editionSecrets；这种情况不能误清空本机 LLM 凭据。
        val replaceEditionSecrets = replaceSecrets && decodedSecrets != null

        llmSettingsRepository.restoreBackup(domain.settings)
        webSearchRepository.restoreBackup(
            settings = domain.webSearch,
            apiKeys = decodedSecrets?.webSearchApiKeys.orEmpty(),
            replaceSecrets = replaceEditionSecrets,
        )
        // RemoteMcpTool 会冻结 discovery 时的 Profile。恢复前必须先卸载旧 Tool 并失效旧连接，
        // 否则相同 serverId 更换 endpoint/凭据后，当前进程仍可能继续命中旧 Server。
        (mcpServerRepository.currentServers().map(McpServerProfile::id) +
            domain.mcpServers.map(McpServerProfile::id))
            .distinct()
            .forEach(mcpToolRegistry::unloadServer)
        mcpServerRepository.restoreBackup(
            servers = domain.mcpServers,
            secrets = decodedSecrets?.mcpServers.orEmpty().mapValues { (_, value) -> value.toDomain() },
            replaceSecrets = replaceEditionSecrets,
        )
        // v1 备份没有这两个字段；缺失时保持当前设备内容，不能误清已有用户数据。
        decoded.skillState?.let(skillRepository::restoreBackupState)
        decoded.quickMessageState?.let(quickMessageRepository::restoreBackupState)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EditionConfigurationBackupExtensionModule {
    @Binds
    abstract fun bindEditionConfigurationBackupExtension(
        implementation: EditionConfigurationBackupExtensionImpl,
    ): EditionConfigurationBackupExtension
}

@Serializable
private data class LlmEditionConfigurationBackup(
    val schemaVersion: Int = CURRENT_LLM_BACKUP_SCHEMA_VERSION,
    val settings: LlmAdvancedSettingsBackup = LlmAdvancedSettingsBackup(),
    val webSearch: WebSearchSettingsBackup = WebSearchSettingsBackup(),
    val mcpServers: List<McpServerProfileBackup> = emptyList(),
    val skillState: String? = null,
    val quickMessageState: String? = null,
)

@Serializable
private data class LlmAdvancedSettingsBackup(
    // 旧 OrigRead X 备份没有这些字段；缺失时保持旧 X 行为：助手开启、默认先摘要。
    val assistantEnabled: Boolean = true,
    val defaultGenerateSummary: Boolean = true,
    val advancedAiConfigEnabled: Boolean = false,
    val reasoningEffort: String = LlmReasoningEffort.AUTO.name,
    val streamResponses: Boolean = true,
    val showReasoning: Boolean = true,
    val contextMaxTokens: Int = LlmSettingsRepository.DEFAULT_CONTEXT_TOKENS,
    val customInstructions: String = "",
    val skillsEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val webSearchMode: String = WebSearchMode.AUTO.name,
    val mcpEnabled: Boolean = false,
)

@Serializable
private data class WebSearchSettingsBackup(
    val providers: List<WebSearchProviderBackup> = emptyList(),
    val defaultProviderId: String? = null,
    val maxResults: Int = 5,
)

@Serializable
private data class WebSearchProviderBackup(
    val id: String,
    val kind: String,
    val name: String,
    val endpoint: String,
    val enabled: Boolean,
)

@Serializable
private data class McpServerProfileBackup(
    val id: String,
    val name: String,
    val endpoint: String,
    val enabled: Boolean,
    val authType: String,
)

@Serializable
private data class LlmEditionSecretsBackup(
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val mcpServers: Map<String, McpServerSecretsBackup> = emptyMap(),
)

@Serializable
private data class McpServerSecretsBackup(
    val bearerToken: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val oauthClientConfig: OAuthClientConfigBackup? = null,
    val oauthRegistration: OAuthRegistrationBackup? = null,
    val oauthTokenSet: OAuthTokenSetBackup? = null,
    val oauthPendingScopes: Set<String> = emptySet(),
)

@Serializable
private data class OAuthClientConfigBackup(
    val clientId: String = "",
    val clientSecret: String = "",
    val authMethod: String = McpOAuthClientAuthMethod.NONE.name,
)

@Serializable
private data class OAuthRegistrationBackup(
    val issuer: String,
    val clientId: String,
    val clientSecret: String = "",
    val authMethod: String = McpOAuthClientAuthMethod.NONE.name,
)

@Serializable
private data class OAuthTokenSetBackup(
    val issuer: String,
    val accessToken: String,
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    val scope: String = "",
    val expiresAtEpochMs: Long = Long.MAX_VALUE,
)

private data class ValidatedLlmEditionConfiguration(
    val settings: LlmAdvancedSettings,
    val webSearch: WebSearchSettings,
    val mcpServers: List<McpServerProfile>,
)

private fun LlmEditionConfigurationBackup.toValidatedDomain(): ValidatedLlmEditionConfiguration {
    require(schemaVersion in 1..CURRENT_LLM_BACKUP_SCHEMA_VERSION) {
        "不支持的 LLM 配置备份版本：$schemaVersion"
    }
    require(settings.contextMaxTokens in LlmSettingsRepository.MIN_CONTEXT_TOKENS..LlmSettingsRepository.MAX_CONTEXT_TOKENS) {
        "LLM Context 长度超出支持范围"
    }
    require(settings.customInstructions.length <= LlmSettingsRepository.MAX_CUSTOM_INSTRUCTIONS_LENGTH) {
        "Custom Instructions 超出长度限制"
    }
    val reasoningEffort = LlmReasoningEffort.valueOf(settings.reasoningEffort)
    val searchMode = WebSearchMode.valueOf(settings.webSearchMode).takeUnless { it == WebSearchMode.FORCE } ?: WebSearchMode.AUTO

    require(webSearch.maxResults in MIN_WEB_SEARCH_MAX_RESULTS..MAX_WEB_SEARCH_MAX_RESULTS) {
        "Web Search maxResults 超出支持范围"
    }
    require(webSearch.providers.map(WebSearchProviderBackup::id).distinct().size == webSearch.providers.size) {
        "Web Search 备份包含重复 Provider ID"
    }
    val providers = webSearch.providers.map { item ->
        require(item.id.isNotBlank()) { "Web Search Provider ID 不能为空" }
        WebSearchProviderProfile(
            id = item.id,
            kind = WebSearchProviderKind.valueOf(item.kind),
            name = item.name,
            endpoint = item.endpoint,
            enabled = item.enabled,
        )
    }
    require(webSearch.defaultProviderId == null || providers.any { it.id == webSearch.defaultProviderId }) {
        "Web Search 默认 Provider 不存在"
    }

    require(mcpServers.map(McpServerProfileBackup::id).distinct().size == mcpServers.size) {
        "MCP 备份包含重复 Server ID"
    }
    val servers = mcpServers.map { item ->
        require(item.id.isNotBlank() && item.endpoint.isNotBlank()) { "MCP 备份包含无效 Server" }
        McpServerProfile(
            id = item.id,
            name = item.name,
            endpoint = item.endpoint,
            enabled = item.enabled,
            authType = McpAuthType.valueOf(item.authType),
        )
    }
    return ValidatedLlmEditionConfiguration(
        settings =
            LlmAdvancedSettings(
                assistantEnabled = settings.assistantEnabled,
                defaultGenerateSummary = settings.defaultGenerateSummary,
                advancedAiConfigEnabled = settings.advancedAiConfigEnabled,
                reasoningEffort = reasoningEffort,
                streamResponses = settings.streamResponses,
                showReasoning = settings.showReasoning,
                contextMaxTokens = settings.contextMaxTokens,
                customInstructions = settings.customInstructions,
                skillsEnabled = settings.skillsEnabled,
                webSearchEnabled = settings.webSearchEnabled,
                webSearchMode = searchMode,
                mcpEnabled = settings.mcpEnabled,
            ),
        webSearch =
            WebSearchSettings(
                providers = providers,
                defaultProviderId = webSearch.defaultProviderId,
                maxResults = webSearch.maxResults,
            ),
        mcpServers = servers,
    )
}

private const val CURRENT_LLM_BACKUP_SCHEMA_VERSION = 2

private fun LlmAdvancedSettings.toBackup() =
    LlmAdvancedSettingsBackup(
        assistantEnabled = assistantEnabled,
        defaultGenerateSummary = defaultGenerateSummary,
        advancedAiConfigEnabled = advancedAiConfigEnabled,
        reasoningEffort = reasoningEffort.name,
        streamResponses = streamResponses,
        showReasoning = showReasoning,
        contextMaxTokens = contextMaxTokens,
        customInstructions = customInstructions,
        skillsEnabled = skillsEnabled,
        webSearchEnabled = webSearchEnabled,
        webSearchMode = webSearchMode.name,
        mcpEnabled = mcpEnabled,
    )

private fun WebSearchSettings.toBackup() =
    WebSearchSettingsBackup(
        providers = providers.map { it.toBackup() },
        defaultProviderId = defaultProviderId,
        maxResults = maxResults,
    )

private fun WebSearchProviderProfile.toBackup() =
    WebSearchProviderBackup(id, kind.name, name, endpoint, enabled)

private fun McpServerProfile.toBackup() =
    McpServerProfileBackup(id, name, endpoint, enabled, authType.name)

private fun McpServerBackupSecrets.toBackup() =
    McpServerSecretsBackup(
        bearerToken = bearerToken,
        customHeaders = customHeaders,
        oauthClientConfig = oauthClientConfig?.let { OAuthClientConfigBackup(it.clientId, it.clientSecret, it.authMethod.name) },
        oauthRegistration = oauthRegistration?.let {
            OAuthRegistrationBackup(it.issuer, it.clientId, it.clientSecret, it.authMethod.name)
        },
        oauthTokenSet = oauthTokenSet?.let {
            OAuthTokenSetBackup(
                issuer = it.issuer,
                accessToken = it.accessToken,
                refreshToken = it.refreshToken,
                tokenType = it.tokenType,
                scope = it.scope,
                expiresAtEpochMs = it.expiresAtEpochMs,
            )
        },
        oauthPendingScopes = oauthPendingScopes,
    )

private fun McpServerSecretsBackup.toDomain(): McpServerBackupSecrets =
    McpServerBackupSecrets(
        bearerToken = bearerToken,
        customHeaders = customHeaders.filter { (name, value) -> name.isNotBlank() && value.isNotBlank() },
        oauthClientConfig = oauthClientConfig?.let {
            McpOAuthClientConfig(
                clientId = it.clientId,
                clientSecret = it.clientSecret,
                authMethod = McpOAuthClientAuthMethod.valueOf(it.authMethod),
            )
        },
        oauthRegistration = oauthRegistration?.let {
            require(it.issuer.isNotBlank() && it.clientId.isNotBlank()) { "MCP OAuth 注册信息无效" }
            McpOAuthClientRegistration(
                issuer = it.issuer,
                clientId = it.clientId,
                clientSecret = it.clientSecret,
                authMethod = McpOAuthClientAuthMethod.valueOf(it.authMethod),
            )
        },
        oauthTokenSet = oauthTokenSet?.let {
            require(it.issuer.isNotBlank() && it.accessToken.isNotBlank()) { "MCP OAuth Token 信息无效" }
            require(it.tokenType.equals("Bearer", ignoreCase = true)) { "MCP OAuth Token 类型不受支持" }
            McpOAuthTokenSet(
                issuer = it.issuer,
                accessToken = it.accessToken,
                refreshToken = it.refreshToken,
                tokenType = it.tokenType,
                scope = it.scope,
                expiresAtEpochMs = it.expiresAtEpochMs,
            )
        },
        oauthPendingScopes = oauthPendingScopes.map(String::trim).filter(String::isNotBlank).toSet(),
    )
