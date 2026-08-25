package me.ash.reader.llm.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ash.reader.infrastructure.translation.SecureSecretStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * LLM edition 独立的 Web Search Provider 配置仓储。
 *
 * API Key 不写入普通 SharedPreferences，继续复用项目已有 Android Keystore 加密存储。
 */
@Singleton
class WebSearchRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secretStore: SecureSecretStore,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<WebSearchSettings> = _settings.asStateFlow()

    fun current(): WebSearchSettings = _settings.value

    /** 新增 Provider；同类型允许存在多份配置，便于以后接自定义网关或不同 Key。 */
    fun addProvider(kind: WebSearchProviderKind): String {
        val profile = WebSearchProviderProfile(kind = kind)
        update { state ->
            state.copy(
                providers = state.providers + profile,
                defaultProviderId = state.defaultProviderId ?: profile.id,
            )
        }
        return profile.id
    }

    fun removeProvider(providerId: String) {
        val state = current()
        if (state.providers.none { it.id == providerId }) return
        secretStore.remove(secretKey(providerId))
        update { current ->
            val remaining = current.providers.filterNot { it.id == providerId }
            current.copy(
                providers = remaining,
                defaultProviderId =
                    if (current.defaultProviderId == providerId) {
                        remaining.firstOrNull(WebSearchProviderProfile::enabled)?.id
                            ?: remaining.firstOrNull()?.id
                    } else {
                        current.defaultProviderId
                    },
            )
        }
    }

    fun setProviderName(providerId: String, value: String) =
        updateProvider(providerId) { it.copy(name = value.trim().take(MAX_NAME_LENGTH)) }

    fun setProviderEndpoint(providerId: String, value: String) =
        updateProvider(providerId) { it.copy(endpoint = value.trim()) }

    /**
     * 启停 Provider 时同步归一化默认项。
     * 禁用当前默认 Provider 后立即切到下一个启用项，避免设置页“默认”标记与 Runtime 实际 fallback 不一致。
     */
    fun setProviderEnabled(providerId: String, value: Boolean) {
        update { state ->
            val providers =
                state.providers.map { profile ->
                    if (profile.id == providerId) profile.copy(enabled = value) else profile
                }
            state.copy(
                providers = providers,
                defaultProviderId = normalizedDefaultProviderId(providers, state.defaultProviderId),
            )
        }
    }

    fun setDefaultProvider(providerId: String) {
        val profile = current().providers.firstOrNull { it.id == providerId && it.enabled } ?: return
        update { it.copy(defaultProviderId = profile.id) }
    }

    fun setApiKey(providerId: String, value: String) =
        secretStore.put(secretKey(providerId), value.trim())

    fun getApiKey(providerId: String): String = secretStore.get(secretKey(providerId))

    fun hasApiKey(providerId: String): Boolean = getApiKey(providerId).isNotBlank()

    /** Provider 完成配置条件由其鉴权能力决定；SearXNG 等自托管服务允许无 Key。 */
    fun isConfigured(providerId: String): Boolean {
        val profile = current().providers.firstOrNull { it.id == providerId } ?: return false
        return profile.enabled &&
            profile.endpoint.isNotBlank() &&
            (!profile.kind.requiresApiKey || hasApiKey(providerId))
    }

    fun configuredProviders(): List<WebSearchProviderProfile> =
        current().providers.filter { isConfigured(it.id) }

    private fun updateProvider(
        providerId: String,
        transform: (WebSearchProviderProfile) -> WebSearchProviderProfile,
    ) {
        update { state ->
            state.copy(
                providers =
                    state.providers.map { profile ->
                        if (profile.id == providerId) transform(profile) else profile
                    }
            )
        }
    }

    private fun update(transform: (WebSearchSettings) -> WebSearchSettings) {
        val next = normalize(transform(_settings.value))
        persist(next)
        _settings.value = next
    }

    private fun readSettings(): WebSearchSettings =
        runCatching {
                val raw = preferences.getString(KEY_PROVIDERS, null).orEmpty()
                val providers =
                    if (raw.isBlank()) {
                        emptyList()
                    } else {
                        val array = JSONArray(raw)
                        buildList {
                            repeat(array.length()) { index ->
                                val item = array.getJSONObject(index)
                                val kind =
                                    runCatching {
                                            WebSearchProviderKind.valueOf(item.optString("kind"))
                                        }.getOrNull() ?: return@repeat
                                add(
                                    WebSearchProviderProfile(
                                        id = item.optString("id"),
                                        kind = kind,
                                        name = item.optString("name", kind.defaultDisplayName),
                                        endpoint = item.optString("endpoint", kind.defaultEndpoint),
                                        enabled = item.optBoolean("enabled", true),
                                    )
                                )
                            }
                        }
                    }
                normalize(
                    WebSearchSettings(
                        providers = providers,
                        defaultProviderId = preferences.getString(KEY_DEFAULT_PROVIDER, null),
                    )
                )
            }
            .getOrDefault(WebSearchSettings())

    private fun normalize(settings: WebSearchSettings): WebSearchSettings {
        val providers =
            settings.providers
                .filter { it.id.isNotBlank() }
                .distinctBy(WebSearchProviderProfile::id)
        val defaultId = normalizedDefaultProviderId(providers, settings.defaultProviderId)
        return WebSearchSettings(providers = providers, defaultProviderId = defaultId)
    }

    private fun persist(settings: WebSearchSettings) {
        val providers =
            JSONArray().apply {
                settings.providers.forEach { profile ->
                    put(
                        JSONObject()
                            .put("id", profile.id)
                            .put("kind", profile.kind.name)
                            .put("name", profile.name)
                            .put("endpoint", profile.endpoint)
                            .put("enabled", profile.enabled)
                    )
                }
            }
        preferences.edit()
            .putString(KEY_PROVIDERS, providers.toString())
            .putString(KEY_DEFAULT_PROVIDER, settings.defaultProviderId)
            .apply()
    }

    private fun secretKey(providerId: String): String = "llm_web_search_api_key:$providerId"

    companion object {
        private const val PREFERENCES_NAME = "origread_llm_web_search"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_DEFAULT_PROVIDER = "default_provider"
        private const val MAX_NAME_LENGTH = 80
    }
}

/** 默认项优先指向仍启用的原 Provider；否则切换到第一个启用项，最后才回退到任意现存项。 */
internal fun normalizedDefaultProviderId(
    providers: List<WebSearchProviderProfile>,
    currentDefaultProviderId: String?,
): String? =
    currentDefaultProviderId
        ?.takeIf { id -> providers.any { it.id == id && it.enabled } }
        ?: providers.firstOrNull(WebSearchProviderProfile::enabled)?.id
        ?: providers.firstOrNull()?.id

