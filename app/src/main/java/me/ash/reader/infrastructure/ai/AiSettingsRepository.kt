package me.ash.reader.infrastructure.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ash.reader.infrastructure.translation.SecureSecretStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 保存 AI 阅读全局偏好与多个 OpenAI Compatible 服务配置。
 * API Key 不进入 SharedPreferences，始终按 providerId 使用 Android Keystore 加密保存。
 */
@Singleton
class AiSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secretStore: SecureSecretStore,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val initialSettings = normalizeSettings(readSettings())
    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    init {
        // 第一次升级到多供应商结构时立即落盘，并把旧单服务 Key 复制到默认供应商。
        persistSettings(initialSettings)
        migrateLegacySecretIfNeeded(initialSettings)
    }

    fun current(): AiSettings = _settings.value

    fun provider(providerId: String?): AiProviderProfile? {
        val settings = current()
        return if (providerId == null) {
            settings.defaultProvider()
        } else {
            settings.providers.firstOrNull { it.id == providerId }
        }
    }

    fun setEnabled(value: Boolean) = updateSettings { it.copy(enabled = value) }

    fun setOutputLanguage(value: String) =
        updateSettings { it.copy(outputLanguage = value) }

    fun setSummaryLength(value: AiSummaryLength) =
        updateSettings { it.copy(summaryLength = value) }

    fun setDefaultProvider(providerId: String) {
        val target = current().providers.firstOrNull { it.id == providerId } ?: return
        if (!target.enabled) return
        updateSettings { it.copy(defaultProviderId = providerId) }
    }

    /** 新增一个独立服务并返回其 ID；默认不自动替换当前默认服务。 */
    fun addProvider(): String {
        val id = UUID.randomUUID().toString()
        val index = current().providers.size + 1
        val profile =
            AiProviderProfile(
                id = id,
                name = "AI 服务 $index",
                endpoint = "https://api.openai.com/v1",
            )
        updateSettings { it.copy(providers = it.providers + profile) }
        return id
    }

    /** 至少保留一个服务配置，避免设置页进入没有可编辑项的状态。 */
    fun removeProvider(providerId: String) {
        val settings = current()
        if (settings.providers.size <= 1) return
        if (settings.providers.none { it.id == providerId }) return
        secretStore.remove(secretKey(providerId))
        updateSettings { current ->
            val remaining = current.providers.filterNot { it.id == providerId }
            val defaultId =
                if (current.defaultProviderId == providerId) {
                    remaining.firstOrNull { it.enabled }?.id ?: remaining.first().id
                } else {
                    current.defaultProviderId
                }
            current.copy(providers = remaining, defaultProviderId = defaultId)
        }
    }

    fun setProviderName(providerId: String, value: String) =
        updateProvider(providerId) { it.copy(name = value.take(MAX_PROVIDER_NAME_LENGTH)) }

    fun setProviderEnabled(providerId: String, value: Boolean) {
        updateSettings { settings ->
            val providers =
                settings.providers.map { profile ->
                    if (profile.id == providerId) profile.copy(enabled = value) else profile
                }
            val defaultProviderId =
                if (!value && settings.defaultProviderId == providerId) {
                    providers.firstOrNull { it.enabled }?.id ?: settings.defaultProviderId
                } else {
                    settings.defaultProviderId
                }
            settings.copy(providers = providers, defaultProviderId = defaultProviderId)
        }
    }

    fun setProviderEndpoint(providerId: String, value: String) =
        updateProvider(providerId) {
            // 地址改变后旧模型列表和手动能力覆盖都不再可信；保留当前手填模型作为兜底。
            it.copy(
                endpoint = value,
                models = emptyList(),
                streamingCapabilityOverride = AiCapabilityOverrideMode.AUTO,
                toolCallingCapabilityOverride = AiCapabilityOverrideMode.AUTO,
                reasoningCapabilityOverride = AiCapabilityOverrideMode.AUTO,
                outputTokenLimitStyle = AiOutputTokenLimitStyle.AUTO,
            )
        }

    fun setProviderDefaultModel(providerId: String, value: String) =
        updateProvider(providerId) { it.copy(defaultModel = normalizeAiModelName(value)) }

    fun setProviderModels(providerId: String, models: List<String>) =
        updateProvider(providerId) {
            val normalizedModels =
                models
                    .map(::normalizeAiModelName)
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted()
            it.copy(
                models = normalizedModels,
                defaultModel =
                    it.defaultModel.takeIf(String::isNotBlank)
                        ?: normalizedModels.firstOrNull()
                        ?: it.defaultModel,
            )
        }

    fun setProviderStreamingCapability(providerId: String, value: AiCapabilityOverrideMode) =
        updateProvider(providerId) { it.copy(streamingCapabilityOverride = value) }

    fun setProviderToolCallingCapability(providerId: String, value: AiCapabilityOverrideMode) =
        updateProvider(providerId) { it.copy(toolCallingCapabilityOverride = value) }

    fun setProviderReasoningCapability(providerId: String, value: AiCapabilityOverrideMode) =
        updateProvider(providerId) { it.copy(reasoningCapabilityOverride = value) }

    /** 持久化 Provider 输出 token 字段策略，供 Chat 与 Summary 共用。 */
    fun setProviderOutputTokenLimitStyle(providerId: String, value: AiOutputTokenLimitStyle) =
        updateProvider(providerId) { it.copy(outputTokenLimitStyle = value) }

    fun setProviderContextWindowTokens(providerId: String, value: Int) =
        updateProvider(providerId) { it.copy(contextWindowTokens = normalizeContextWindowTokens(value)) }

    fun setProviderStrictStreamTermination(providerId: String, value: Boolean) =
        updateProvider(providerId) { it.copy(strictStreamTermination = value) }

    fun setApiKey(providerId: String, value: String) {
        secretStore.put(secretKey(providerId), value.trim())
    }

    fun getApiKey(providerId: String): String = secretStore.get(secretKey(providerId))

    fun hasApiKey(providerId: String): Boolean = getApiKey(providerId).isNotBlank()

    /** 自建兼容服务允许无 Key，因此只强制要求启用、地址和模型。 */
    fun isConfigured(providerId: String): Boolean {
        val profile = current().providers.firstOrNull { it.id == providerId } ?: return false
        return profile.enabled && profile.endpoint.isNotBlank() && profile.defaultModel.isNotBlank()
    }

    fun configuredProviders(): List<AiProviderProfile> =
        current().providers.filter { isConfigured(it.id) }

    fun runtimeConfig(): AiRuntimeConfig {
        val profile = current().defaultProvider()
            ?: throw AiException(AiErrorCode.NOT_CONFIGURED, "没有可用的 AI 服务")
        return runtimeConfig(profile.id)
    }

    /**
     * 构建指定服务的运行时配置。
     * 模型与 Key 都允许临时覆盖，用于阅读页“换一个模型重新生成”而不修改全局默认配置。
     */
    fun runtimeConfig(
        providerId: String,
        modelOverride: String? = null,
        apiKeyOverride: String? = null,
    ): AiRuntimeConfig {
        val profile = current().providers.firstOrNull { it.id == providerId }
            ?: throw AiException(AiErrorCode.NOT_CONFIGURED, "AI 服务配置不存在")
        return AiRuntimeConfig(
            endpoint = normalizeEndpoint(profile.endpoint),
            model = normalizeAiModelName(modelOverride ?: profile.defaultModel),
            apiKey = apiKeyOverride?.trim() ?: getApiKey(providerId),
        )
    }

    fun restoreDefaults() {
        current().providers.forEach { secretStore.remove(secretKey(it.id)) }
        secretStore.remove(LEGACY_SECRET_API_KEY)
        preferences.edit().clear().apply()
        val defaults = AiSettings()
        persistSettings(defaults)
        _settings.value = defaults
    }

    /**
     * 从完整配置备份恢复 AI 服务。
     * 无凭据备份只恢复 Provider/模型等普通设置，不会清除本机已有 Key。
     */
    fun restoreBackup(
        settings: AiSettings,
        apiKeys: Map<String, String> = emptyMap(),
        replaceSecrets: Boolean = false,
    ) {
        requireUniqueProviderIds(settings.providers)
        val previousProviders = current().providers
        val previousProviderIds = previousProviders.map(AiProviderProfile::id)
        val normalized = normalizeSettings(settings)
        if (replaceSecrets) {
            (previousProviderIds + normalized.providers.map(AiProviderProfile::id))
                .distinct()
                .forEach { secretStore.remove(secretKey(it)) }
            secretStore.remove(LEGACY_SECRET_API_KEY)
            normalized.providers.forEach { provider ->
                apiKeys[provider.id]?.takeIf(String::isNotBlank)?.let { secretStore.put(secretKey(provider.id), it) }
            }
        } else {
            // Standard / LLM 两个 Edition 可能各自独立创建过同一服务，导致 providerId 不同。
            // 无凭据同步时若直接替换 Provider 列表，旧 Key 会仍留在旧 providerId 下，但新配置无法再读取，
            // 用户看到的表现就是“同步后 Key 丢失”。这里只对 Endpoint 一一对应且无歧义的服务迁移本机 Key；
            // 同 Endpoint 存在多个候选时保持原状，避免把不同账号的凭据错误复制给另一服务。
            findProviderSecretMigrations(
                previousProviders = previousProviders,
                incomingProviders = normalized.providers,
                hasSecret = { providerId -> hasApiKey(providerId) },
            ).forEach { migration ->
                val value = getApiKey(migration.fromProviderId)
                if (value.isNotBlank()) {
                    secretStore.put(secretKey(migration.toProviderId), value)
                }
            }
        }
        preferences.edit().clear().apply()
        persistSettings(normalized)
        _settings.value = normalized
    }

    private fun updateProvider(
        providerId: String,
        transform: (AiProviderProfile) -> AiProviderProfile,
    ) {
        updateSettings { settings ->
            settings.copy(
                providers =
                    settings.providers.map { profile ->
                        if (profile.id == providerId) transform(profile) else profile
                    }
            )
        }
    }

    private fun updateSettings(transform: (AiSettings) -> AiSettings) {
        val next = normalizeSettings(transform(_settings.value))
        persistSettings(next)
        _settings.value = next
    }

    private fun normalizeSettings(settings: AiSettings): AiSettings {
        val providers =
            settings.providers
                .map { profile ->
                    profile.copy(
                        name = profile.name.take(MAX_PROVIDER_NAME_LENGTH),
                        defaultModel = normalizeAiModelName(profile.defaultModel),
                        models =
                            profile.models
                                .map(::normalizeAiModelName)
                                .filter(String::isNotBlank)
                                .distinct()
                                .sorted(),
                        contextWindowTokens = normalizeContextWindowTokens(profile.contextWindowTokens),
                    )
                }
                // 只用于迁移本机历史异常数据；外部备份在 restoreBackup 入口会明确拒绝重复 ID。
                .distinctBy(AiProviderProfile::id)
                .ifEmpty { listOf(AiProviderProfile()) }
        val defaultProviderId =
            settings.defaultProviderId.takeIf { id -> providers.any { it.id == id } }
                ?: providers.firstOrNull { it.enabled }?.id
                ?: providers.first().id
        return settings.copy(providers = providers, defaultProviderId = defaultProviderId)
    }

    private fun requireUniqueProviderIds(providers: List<AiProviderProfile>) {
        require(providers.all { it.id.isNotBlank() }) { "AI 服务 Provider ID 不能为空" }
        require(providers.map(AiProviderProfile::id).distinct().size == providers.size) {
            "AI 服务配置包含重复 Provider ID"
        }
    }

    private fun persistSettings(settings: AiSettings) {
        val providersJson =
            JSONArray().apply {
                settings.providers.forEach { profile ->
                    put(
                        JSONObject()
                            .put("id", profile.id)
                            .put("name", profile.name)
                            .put("enabled", profile.enabled)
                            .put("endpoint", profile.endpoint)
                            .put("defaultModel", profile.defaultModel)
                            .put("models", JSONArray(profile.models))
                            .put("streamingCapabilityOverride", profile.streamingCapabilityOverride.name)
                            .put("toolCallingCapabilityOverride", profile.toolCallingCapabilityOverride.name)
                            .put("reasoningCapabilityOverride", profile.reasoningCapabilityOverride.name)
                            .put("outputTokenLimitStyle", profile.outputTokenLimitStyle.name)
                            .put("contextWindowTokens", profile.contextWindowTokens)
                            .put("strictStreamTermination", profile.strictStreamTermination)
                    )
                }
            }
        preferences
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_PROVIDERS, providersJson.toString())
            .putString(KEY_DEFAULT_PROVIDER_ID, settings.defaultProviderId)
            .putString(KEY_OUTPUT_LANGUAGE, settings.outputLanguage)
            .putString(KEY_SUMMARY_LENGTH, settings.summaryLength.name)
            .apply()
    }

    private fun readSettings(): AiSettings {
        val providers = readProviders()
        val defaultProviderId =
            preferences.getString(KEY_DEFAULT_PROVIDER_ID, null)
                ?: providers.firstOrNull()?.id
                ?: DEFAULT_AI_PROVIDER_ID
        return AiSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            providers = providers,
            defaultProviderId = defaultProviderId,
            outputLanguage =
                preferences.getString(KEY_OUTPUT_LANGUAGE, AiSettings.defaultOutputLanguage())
                    ?: AiSettings.defaultOutputLanguage(),
            summaryLength =
                runCatching {
                        AiSummaryLength.valueOf(
                            preferences.getString(KEY_SUMMARY_LENGTH, null).orEmpty()
                        )
                    }
                    .getOrDefault(AiSummaryLength.STANDARD),
        )
    }

    /** 读取新结构失败或尚未迁移时，从旧 endpoint/model 字段构造默认供应商。 */
    private fun readProviders(): List<AiProviderProfile> {
        preferences.getString(KEY_PROVIDERS, null)?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching {
                    val array = JSONArray(raw)
                    buildList {
                        for (index in 0 until array.length()) {
                            val json = array.optJSONObject(index) ?: continue
                            val id = json.optString("id").ifBlank { UUID.randomUUID().toString() }
                            // 兼容上一版 model / availableModels 字段，读取后统一落盘为新结构。
                            val defaultModel =
                                json.optString("defaultModel")
                                    .ifBlank { json.optString("model") }
                            val models =
                                (json.optJSONArray("models") ?: json.optJSONArray("availableModels"))
                                    ?.let { values ->
                                    buildList {
                                        for (modelIndex in 0 until values.length()) {
                                            values.optString(modelIndex)
                                                .takeIf(String::isNotBlank)
                                                ?.let(::add)
                                        }
                                    }
                                } ?: emptyList()
                            add(
                                AiProviderProfile(
                                    id = id,
                                    name = json.optString("name").ifBlank { "AI 服务" },
                                    enabled = json.optBoolean("enabled", true),
                                    endpoint = json.optString("endpoint"),
                                    defaultModel = defaultModel,
                                    models =
                                        (models + defaultModel)
                                            .filter(String::isNotBlank)
                                            .distinct(),
                                    streamingCapabilityOverride =
                                        json.optCapabilityOverride("streamingCapabilityOverride"),
                                    toolCallingCapabilityOverride =
                                        json.optCapabilityOverride("toolCallingCapabilityOverride"),
                                    reasoningCapabilityOverride =
                                        json.optCapabilityOverride("reasoningCapabilityOverride"),
                                    outputTokenLimitStyle = json.optOutputTokenLimitStyle("outputTokenLimitStyle"),
                                    contextWindowTokens =
                                        normalizeContextWindowTokens(
                                            json.optInt("contextWindowTokens", DEFAULT_PROVIDER_CONTEXT_WINDOW_TOKENS)
                                        ),
                                    strictStreamTermination = json.optBoolean("strictStreamTermination", true),
                                )
                            )
                        }
                    }
                }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        val endpoint =
            preferences.getString(KEY_LEGACY_ENDPOINT, AiProviderProfile().endpoint)
                ?: AiProviderProfile().endpoint
        val model = preferences.getString(KEY_LEGACY_MODEL, "").orEmpty()
        return listOf(
            AiProviderProfile(
                id = DEFAULT_AI_PROVIDER_ID,
                name = inferProviderName(endpoint),
                endpoint = endpoint,
                defaultModel = model,
                models = listOf(model).filter(String::isNotBlank),
            )
        )
    }

    private fun migrateLegacySecretIfNeeded(settings: AiSettings) {
        val default = settings.providers.firstOrNull { it.id == DEFAULT_AI_PROVIDER_ID } ?: return
        val legacy = secretStore.get(LEGACY_SECRET_API_KEY)
        if (legacy.isBlank()) return
        if (!secretStore.contains(secretKey(default.id))) {
            secretStore.put(secretKey(default.id), legacy)
        }
        // 迁移完成后必须删除旧键，否则用户清空新凭据时会被旧值重新读回。
        secretStore.remove(LEGACY_SECRET_API_KEY)
    }

    private fun JSONObject.optCapabilityOverride(key: String): AiCapabilityOverrideMode =
        runCatching { AiCapabilityOverrideMode.valueOf(optString(key)) }
            .getOrDefault(AiCapabilityOverrideMode.AUTO)

    /** 旧设置没有该字段时保持 AUTO，由真实 Endpoint/Model resolver 决定请求字段。 */
    private fun JSONObject.optOutputTokenLimitStyle(key: String): AiOutputTokenLimitStyle =
        parseAiOutputTokenLimitStyle(optString(key).takeIf(String::isNotBlank))

    private fun normalizeEndpoint(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    /** 只夹取异常值，不吸附模型档位；Provider 可按官方文档填写真实窗口。 */
    private fun normalizeContextWindowTokens(value: Int): Int =
        value.coerceIn(MIN_PROVIDER_CONTEXT_WINDOW_TOKENS, MAX_PROVIDER_CONTEXT_WINDOW_TOKENS)

    private fun secretKey(providerId: String): String = "$SECRET_API_KEY_PREFIX$providerId"

    private fun inferProviderName(endpoint: String): String {
        val lower = endpoint.lowercase()
        return when {
            "deepseek" in lower -> "DeepSeek"
            "openai" in lower -> "OpenAI"
            "gemini" in lower || "google" in lower -> "Google AI"
            else -> "默认服务"
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "ai_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDERS = "providers_v2"
        private const val KEY_DEFAULT_PROVIDER_ID = "default_provider_id"
        private const val KEY_OUTPUT_LANGUAGE = "output_language"
        private const val KEY_SUMMARY_LENGTH = "summary_length"
        private const val KEY_LEGACY_ENDPOINT = "endpoint"
        private const val KEY_LEGACY_MODEL = "model"
        private const val LEGACY_SECRET_API_KEY = "ai_openai_compatible_api_key"
        private const val SECRET_API_KEY_PREFIX = "ai_provider_api_key_"
        private const val MAX_PROVIDER_NAME_LENGTH = 40
        const val DEFAULT_PROVIDER_CONTEXT_WINDOW_TOKENS = 128_000
        /** 允许常见 4K 模型，避免配置/恢复时静默抬高为 8K。 */
        const val MIN_PROVIDER_CONTEXT_WINDOW_TOKENS = 4_096
        const val MAX_PROVIDER_CONTEXT_WINDOW_TOKENS = 4_000_000
    }
}

/** 无凭据配置恢复时需要执行的本机 AI Key providerId 迁移。 */
internal data class AiProviderSecretMigration(
    val fromProviderId: String,
    val toProviderId: String,
)

/**
 * 根据唯一 Endpoint 关系识别 providerId 变化后的 Secret 迁移。
 *
 * 只接受来源与目标都一一对应的情况；任一侧同 Endpoint 出现多个 Provider 都视为歧义，不自动迁移。
 */
internal fun findProviderSecretMigrations(
    previousProviders: List<AiProviderProfile>,
    incomingProviders: List<AiProviderProfile>,
    hasSecret: (String) -> Boolean,
): List<AiProviderSecretMigration> {
    val previousWithSecretByEndpoint =
        previousProviders
            .filter { hasSecret(it.id) }
            .groupBy { providerSecretEndpointKey(it.endpoint) }
    val incomingCountByEndpoint = incomingProviders.groupingBy { providerSecretEndpointKey(it.endpoint) }.eachCount()

    return incomingProviders.mapNotNull { incoming ->
        if (hasSecret(incoming.id)) return@mapNotNull null
        val endpointKey = providerSecretEndpointKey(incoming.endpoint)
        if (endpointKey.isBlank() || incomingCountByEndpoint[endpointKey] != 1) return@mapNotNull null
        val candidates = previousWithSecretByEndpoint[endpointKey].orEmpty()
        val previous = candidates.singleOrNull() ?: return@mapNotNull null
        if (previous.id == incoming.id) return@mapNotNull null
        AiProviderSecretMigration(
            fromProviderId = previous.id,
            toProviderId = incoming.id,
        )
    }
}

/** Endpoint 仅用于本机 Secret 迁移匹配，不参与网络请求地址规范化。 */
private fun providerSecretEndpointKey(endpoint: String): String =
    endpoint.trim().trimEnd('/').lowercase()

/**
 * 模型 ID 本质上是单个标识符，不应该包含空白符。
 * 部分输入法/粘贴场景可能把同一模型名以多行形式重复写入，单行 TextField 又只显示第一段，
 * 最终会造成界面看起来正确、实际请求中的 model 却包含多个重复值。
 */
internal fun normalizeAiModelName(value: String): String =
    value
        .trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        .orEmpty()
