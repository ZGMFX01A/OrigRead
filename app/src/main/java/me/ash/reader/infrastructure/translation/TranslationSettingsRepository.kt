package me.ash.reader.infrastructure.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 保存翻译 Provider、目标语言和显示方式；API Key 委托给 Keystore 加密存储。 */
@Singleton
class TranslationSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secretStore: SecureSecretStore,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<TranslationSettings> = _settings.asStateFlow()

    fun current(): TranslationSettings = _settings.value

    fun setDefaultProvider(type: TranslationProviderType) {
        if (!current().provider(type).enabled) return
        preferences.edit().putString(KEY_DEFAULT_PROVIDER, type.name).apply()
        setDefaultTarget(TranslationTarget.Traditional(type))
    }

    /** 将完整设置模型写入 SharedPreferences，供恢复流程复用。 */
    private fun persistSettings(settings: TranslationSettings) {
        val editor =
            preferences.edit()
                .putString(KEY_DEFAULT_PROVIDER, settings.defaultProvider.name)
                .putString(KEY_DEFAULT_TARGET, encodeTarget(settings.defaultTarget))
                .putString(KEY_TARGET_LANGUAGE, settings.targetLanguage)
                .putString(KEY_DISPLAY_MODE, settings.displayMode.name)
        TranslationProviderType.entries.forEach { type ->
            val provider = settings.provider(type)
            editor
                .putBoolean(enabledKey(type), provider.enabled)
                .putString(endpointKey(type), provider.endpoint)
            if (type == TranslationProviderType.MICROSOFT) {
                editor.putString(KEY_MICROSOFT_REGION, provider.region)
            }
        }
        editor.apply()
    }

    /** 默认翻译方式可以是传统 Provider，也可以是某个 AI Provider + Model。 */
    fun setDefaultTarget(target: TranslationTarget) {
        val current = _settings.value
        val nextProvider =
            (target as? TranslationTarget.Traditional)?.provider ?: current.defaultProvider
        preferences.edit()
            .putString(KEY_DEFAULT_TARGET, encodeTarget(target))
            .putString(KEY_DEFAULT_PROVIDER, nextProvider.name)
            .apply()
        _settings.value = current.copy(defaultProvider = nextProvider, defaultTarget = target)
    }

    fun setTargetLanguage(value: String) {
        preferences.edit().putString(KEY_TARGET_LANGUAGE, value).apply()
        _settings.value = _settings.value.copy(targetLanguage = value)
    }

    fun setDisplayMode(mode: TranslationDisplayMode) {
        preferences.edit().putString(KEY_DISPLAY_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(displayMode = mode)
    }

    fun setProviderEnabled(type: TranslationProviderType, enabled: Boolean) {
        val current = _settings.value
        if (current.provider(type).enabled == enabled) return

        val enabledOthers =
            TranslationProviderType.entries.filter { candidate ->
                candidate != type && current.provider(candidate).enabled
            }
        // 始终保留至少一个可用 Provider，避免默认翻译入口进入无服务状态。
        if (!enabled && enabledOthers.isEmpty()) return

        preferences.edit().putBoolean(enabledKey(type), enabled).apply()
        val providers =
            current.providers +
                (type to current.provider(type).copy(enabled = enabled))
        val nextDefault =
            when {
                enabled && !current.provider(current.defaultProvider).enabled -> type
                !enabled && current.defaultProvider == type -> enabledOthers.first()
                else -> current.defaultProvider
            }
        if (nextDefault != current.defaultProvider) {
            preferences.edit().putString(KEY_DEFAULT_PROVIDER, nextDefault.name).apply()
        }
        val nextTarget =
            if (!enabled &&
                (current.defaultTarget as? TranslationTarget.Traditional)?.provider == type
            ) {
                TranslationTarget.Traditional(nextDefault)
            } else {
                current.defaultTarget
            }
        if (nextTarget != current.defaultTarget) {
            preferences.edit().putString(KEY_DEFAULT_TARGET, encodeTarget(nextTarget)).apply()
        }
        _settings.value =
            current.copy(
                defaultProvider = nextDefault,
                defaultTarget = nextTarget,
                providers = providers,
            )
    }

    fun setProviderEndpoint(type: TranslationProviderType, endpoint: String) {
        preferences.edit().putString(endpointKey(type), endpoint).apply()
        updateProvider(type) { it.copy(endpoint = endpoint) }
    }

    fun setMicrosoftRegion(region: String) {
        val value = region.trim()
        preferences.edit().putString(KEY_MICROSOFT_REGION, value).apply()
        updateProvider(TranslationProviderType.MICROSOFT) { it.copy(region = value) }
    }

    fun setApiKey(type: TranslationProviderType, value: String) {
        secretStore.put(secretKey(type), value.trim())
    }

    fun getApiKey(type: TranslationProviderType): String = secretStore.get(secretKey(type))

    fun hasApiKey(type: TranslationProviderType): Boolean = secretStore.contains(secretKey(type))

    fun isConfigured(type: TranslationProviderType): Boolean {
        val provider = current().provider(type)
        return when (type) {
            TranslationProviderType.ML_KIT -> true
            TranslationProviderType.DLX -> provider.endpoint.isNotBlank()
            else -> provider.endpoint.isNotBlank() && hasApiKey(type)
        }
    }

    fun runtimeConfig(type: TranslationProviderType): TranslationRuntimeConfig {
        val provider = current().provider(type)
        return TranslationRuntimeConfig(
            endpoint = normalizeEndpoint(provider.endpoint),
            region = provider.region,
            apiKey = getApiKey(type),
        )
    }

    fun restoreDefaults() {
        preferences.edit().clear().apply()
        TranslationProviderType.entries.forEach { secretStore.remove(secretKey(it)) }
        _settings.value = TranslationSettings()
    }

    /**
     * 从完整配置备份恢复翻译设置。
     * 仅当备份确实携带并成功解密了凭据时才替换现有 API Key，避免无密钥备份清空当前设备凭据。
     */
    fun restoreBackup(
        settings: TranslationSettings,
        apiKeys: Map<TranslationProviderType, String> = emptyMap(),
        replaceSecrets: Boolean = false,
    ) {
        var providers = settings.providers
        if (providers.values.none(TranslationProviderSettings::enabled)) {
            providers =
                providers +
                    (TranslationProviderType.ML_KIT to
                        settings.provider(TranslationProviderType.ML_KIT).copy(enabled = true))
        }
        val defaultProvider =
            settings.defaultProvider.takeIf { providers[it]?.enabled == true }
                ?: TranslationProviderType.entries.first { providers[it]?.enabled == true }
        val defaultTarget =
            when (val target = settings.defaultTarget) {
                is TranslationTarget.Traditional ->
                    target.takeIf { providers[it.provider]?.enabled == true }
                        ?: TranslationTarget.Traditional(defaultProvider)
                is TranslationTarget.Ai -> target
            }
        val normalized =
            settings.copy(
                defaultProvider = defaultProvider,
                defaultTarget = defaultTarget,
                providers =
                    TranslationProviderType.entries.associateWith { type ->
                        providers[type] ?: TranslationSettings.defaultProviderSettings().getValue(type)
                    },
            )

        preferences.edit().clear().apply()
        persistSettings(normalized)
        if (replaceSecrets) {
            TranslationProviderType.entries.forEach { type ->
                val value = apiKeys[type].orEmpty()
                if (value.isBlank()) secretStore.remove(secretKey(type)) else secretStore.put(secretKey(type), value)
            }
        }
        _settings.value = normalized
    }

    private fun updateProvider(
        type: TranslationProviderType,
        transform: (TranslationProviderSettings) -> TranslationProviderSettings,
    ) {
        val current = _settings.value
        _settings.value =
            current.copy(providers = current.providers + (type to transform(current.provider(type))))
    }

    private fun readSettings(): TranslationSettings {
        val defaults = TranslationSettings.defaultProviderSettings()
        var providers =
            TranslationProviderType.entries.associateWith { type ->
                val fallback = defaults.getValue(type)
                TranslationProviderSettings(
                    enabled = preferences.getBoolean(enabledKey(type), fallback.enabled),
                    endpoint =
                        preferences.getString(endpointKey(type), fallback.endpoint)
                            ?: fallback.endpoint,
                    region =
                        if (type == TranslationProviderType.MICROSOFT) {
                            preferences.getString(KEY_MICROSOFT_REGION, "").orEmpty()
                        } else {
                            fallback.region
                        },
                )
            }
        if (providers.values.none(TranslationProviderSettings::enabled)) {
            val mlKit = providers.getValue(TranslationProviderType.ML_KIT)
            providers =
                providers +
                    (TranslationProviderType.ML_KIT to mlKit.copy(enabled = true))
            preferences.edit().putBoolean(enabledKey(TranslationProviderType.ML_KIT), true).apply()
        }
        val requestedDefault =
            enumValueOrDefault(
                preferences.getString(KEY_DEFAULT_PROVIDER, null),
                TranslationProviderType.ML_KIT,
            )
        val resolvedDefault =
            requestedDefault.takeIf { providers.getValue(it).enabled }
                ?: TranslationProviderType.entries.first { providers.getValue(it).enabled }
        if (resolvedDefault != requestedDefault) {
            preferences.edit().putString(KEY_DEFAULT_PROVIDER, resolvedDefault.name).apply()
        }
        val requestedTarget =
            decodeTarget(preferences.getString(KEY_DEFAULT_TARGET, null))
                ?: TranslationTarget.Traditional(resolvedDefault)
        val resolvedTarget =
            when (requestedTarget) {
                is TranslationTarget.Traditional ->
                    requestedTarget.takeIf { providers.getValue(it.provider).enabled }
                        ?: TranslationTarget.Traditional(resolvedDefault)
                is TranslationTarget.Ai -> requestedTarget
            }
        if (resolvedTarget != requestedTarget || !preferences.contains(KEY_DEFAULT_TARGET)) {
            preferences.edit().putString(KEY_DEFAULT_TARGET, encodeTarget(resolvedTarget)).apply()
        }
        return TranslationSettings(
            defaultProvider = resolvedDefault,
            defaultTarget = resolvedTarget,
            targetLanguage =
                preferences.getString(
                    KEY_TARGET_LANGUAGE,
                    TranslationSettings.defaultTargetLanguage(),
                ) ?: TranslationSettings.defaultTargetLanguage(),
            displayMode =
                enumValueOrDefault(
                    preferences.getString(KEY_DISPLAY_MODE, null),
                    TranslationDisplayMode.TRANSLATED,
                ),
            providers = providers,
        )
    }

    private fun normalizeEndpoint(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

    private fun encodeTarget(target: TranslationTarget): String =
        when (target) {
            is TranslationTarget.Traditional ->
                JSONObject()
                    .put("type", "traditional")
                    .put("provider", target.provider.name)
                    .toString()
            is TranslationTarget.Ai ->
                JSONObject()
                    .put("type", "ai")
                    .put("providerId", target.providerId)
                    .put("providerName", target.providerName)
                    .put("model", target.model)
                    .toString()
        }

    private fun decodeTarget(value: String?): TranslationTarget? =
        value?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching {
                    val json = JSONObject(raw)
                    when (json.getString("type")) {
                        "traditional" ->
                            TranslationTarget.Traditional(
                                TranslationProviderType.valueOf(json.getString("provider"))
                            )
                        "ai" ->
                            TranslationTarget.Ai(
                                providerId = json.getString("providerId"),
                                providerName = json.optString("providerName").ifBlank { "AI" },
                                model = json.getString("model"),
                            )
                        else -> null
                    }
                }
                .getOrNull()
        }

    companion object {
        private const val PREFERENCES_NAME = "translation_settings"
        private const val KEY_DEFAULT_PROVIDER = "default_provider"
        private const val KEY_DEFAULT_TARGET = "default_target"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_MICROSOFT_REGION = "microsoft_region"

        private fun enabledKey(type: TranslationProviderType) = "${type.name.lowercase()}_enabled"

        private fun endpointKey(type: TranslationProviderType) = "${type.name.lowercase()}_endpoint"

        private fun secretKey(type: TranslationProviderType) = "${type.name.lowercase()}_api_key"
    }
}
