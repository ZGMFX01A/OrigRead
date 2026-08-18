package me.ash.reader.infrastructure.rsshub

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RssHubInstance(
    val id: String,
    val url: String,
    val location: String,
    val maintainer: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = true,
)

data class RssHubSettings(
    val enabled: Boolean = true,
    val instances: List<RssHubInstance> = RssHubSettingsRepository.defaultInstances(),
)

/** 保存 RSSHub 总开关和实例列表，供设置页与来源发现流程共享。 */
@Singleton
class RssHubSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<RssHubSettings> = _settings.asStateFlow()

    fun current(): RssHubSettings = _settings.value

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(enabled = enabled)
    }

    /** 添加自定义实例；已存在的地址会直接重新启用，避免产生重复项。 */
    fun addInstance(value: String) {
        val normalized = normalizeInstanceUrl(value)
        val currentInstances = _settings.value.instances
        val existing = currentInstances.firstOrNull { it.url == normalized }
        val updated =
            if (existing != null) {
                currentInstances.map { instance ->
                    if (instance.id == existing.id) instance.copy(enabled = true) else instance
                }
            } else {
                currentInstances +
                    RssHubInstance(
                        id = "custom-${normalized.hashCode()}",
                        url = normalized,
                        location = "",
                        maintainer = "",
                        enabled = true,
                        builtIn = false,
                    )
            }
        saveInstances(updated)
    }

    fun setInstanceEnabled(id: String, enabled: Boolean) {
        saveInstances(
            _settings.value.instances.map { instance ->
                if (instance.id == id) instance.copy(enabled = enabled) else instance
            }
        )
    }

    fun deleteInstance(id: String) {
        saveInstances(_settings.value.instances.filterNot { it.id == id })
    }

    /** 记录最近成功提供有效 Feed 的实例，后续探测时优先复用。 */
    fun recordSuccess(instanceBaseUrl: String) {
        val normalized = normalizeInstanceUrl(instanceBaseUrl)
        preferences.edit()
            .putString(KEY_LAST_SUCCESS_INSTANCE, normalized)
            .remove(cooldownKey(normalized))
            .apply()
    }

    /** 实例网络失败后进入短暂冷却，避免连续添加来源时反复等待同一不可用实例。 */
    fun recordFailure(instanceBaseUrl: String, nowMillis: Long = System.currentTimeMillis()) {
        val normalized = normalizeInstanceUrl(instanceBaseUrl)
        preferences.edit()
            .putLong(cooldownKey(normalized), nowMillis + INSTANCE_COOLDOWN_MILLIS)
            .apply()
    }

    /**
     * 最近成功实例优先，其余启用实例保持设置页顺序。
     * 冷却实例只降到列表末尾，不再彻底排除；手动来源探测仍有机会从瞬时失败中恢复。
     */
    fun candidateInstances(nowMillis: Long = System.currentTimeMillis()): List<String> {
        val enabledInstances = current().instances.filter { it.enabled }.map { it.url }
        val lastSuccess = preferences.getString(KEY_LAST_SUCCESS_INSTANCE, null)
        val ordered = orderInstances(lastSuccess, *enabledInstances.toTypedArray())
        val (ready, cooling) =
            ordered.partition { instance -> preferences.getLong(cooldownKey(instance), 0L) <= nowMillis }
        return ready + cooling
    }

    fun restoreDefault() {
        preferences.edit()
            .putBoolean(KEY_ENABLED, true)
            .remove(KEY_INSTANCES)
            .remove(KEY_LEGACY_INSTANCE_URL)
            .remove(KEY_LAST_SUCCESS_INSTANCE)
            .apply()
        _settings.value = RssHubSettings()
    }

    /** 完整配置恢复入口；网络成功记录和失败冷却属于临时状态，不随备份迁移。 */
    fun restoreBackup(settings: RssHubSettings) {
        val normalizedInstances =
            settings.instances
                .map { instance -> instance.copy(url = normalizeInstanceUrl(instance.url)) }
                .distinctBy(RssHubInstance::url)
                .ifEmpty { defaultInstances() }
        preferences.edit()
            .clear()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_INSTANCES, encodeInstances(normalizedInstances))
            .apply()
        _settings.value = settings.copy(instances = normalizedInstances)
    }

    private fun saveInstances(instances: List<RssHubInstance>) {
        preferences.edit().putString(KEY_INSTANCES, encodeInstances(instances)).apply()
        _settings.value = _settings.value.copy(instances = instances)
    }

    private fun readSettings(): RssHubSettings {
        val stored = preferences.getString(KEY_INSTANCES, null)
        val instances =
            if (stored.isNullOrBlank()) {
                migrateLegacyInstances()
            } else {
                decodeInstances(stored).ifEmpty { defaultInstances() }
            }
        return RssHubSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, true),
            instances = instances,
        )
    }

    /** 兼容此前只保存一个实例地址的版本，并补齐新的公共实例列表。 */
    private fun migrateLegacyInstances(): List<RssHubInstance> {
        val legacy = preferences.getString(KEY_LEGACY_INSTANCE_URL, null)
        if (legacy.isNullOrBlank()) return defaultInstances()
        val normalized = normalizeInstanceUrl(legacy)
        return defaultInstances().map { instance ->
            if (instance.url == normalized) instance.copy(enabled = true) else instance
        }.let { defaults ->
            if (defaults.any { it.url == normalized }) defaults
            else listOf(
                RssHubInstance(
                    id = "custom-${normalized.hashCode()}",
                    url = normalized,
                    location = "",
                    maintainer = "",
                    builtIn = false,
                )
            ) + defaults
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "rsshub_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INSTANCES = "instances"
        private const val KEY_LEGACY_INSTANCE_URL = "instance_url"
        private const val KEY_LAST_SUCCESS_INSTANCE = "last_success_instance"
        private const val KEY_COOLDOWN_PREFIX = "cooldown_until_"
        private const val INSTANCE_COOLDOWN_MILLIS = 5 * 60 * 1000L

        fun normalizeInstanceUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            return when {
                trimmed.isBlank() -> RssHubResolver.DEFAULT_INSTANCE
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> "https://$trimmed"
            }
        }

        internal fun orderInstances(vararg values: String?): List<String> =
            values.asSequence()
                .filterNotNull()
                .map(::normalizeInstanceUrl)
                .distinct()
                .toList()

        /** 内置实例只是初始配置，用户仍可逐个禁用或删除。 */
        fun defaultInstances(): List<RssHubInstance> =
            listOf(
                instance("official", "https://rsshub.app", "🇺🇸 美国", "DIYgod"),
                instance("rssforever", "https://rsshub.rssforever.com", "🇦🇪 阿联酋", "Stille"),
                instance("slarker", "https://hub.slarker.me", "🇺🇸 美国", "Slarker"),
                instance("pseudoyu", "https://rsshub.pseudoyu.com", "🇫🇷 法国", "pseudoyu"),
                instance("rsstips", "https://rsshub.rss.tips", "🇺🇸 美国", "AboutRSS"),
                instance("ktachibana", "https://rsshub.ktachibana.party", "🇺🇸 美国", "KTachibanaM"),
                instance("owonz", "https://rss.owo.nz", "🇩🇪 德国", "Vincent Yang"),
                instance("wudifeixue", "https://rss.wudifeixue.com", "🇨🇦 加拿大", "wudifeixue"),
                instance("henry", "https://rsshub.henry.wang", "🇬🇧 英国", "HenryQW"),
                instance("umzzz", "https://rsshub.umzzz.com", "🇭🇰 香港", "nesay"),
                instance("isrss", "https://rsshub.isrss.com", "🇺🇸 美国", "isRSS"),
                instance("emailonce", "https://rsshub.email-once.com", "🇭🇰 香港", "EmailOnce"),
                instance("datuan", "https://rss.datuan.dev", "🇻🇳 越南", "Tuấn Dev"),
                instance("cups", "https://rsshub.cups.moe", "🇺🇸 美国", "FunnyCups"),
                instance("spriple", "https://rss.spriple.org", "🇨🇳 中国", "Spriple"),
                instance("virworks", "https://rsshub-balancer.virworks.moe", "🇺🇳 多地负载均衡", "chesha1"),
            )

        private fun instance(id: String, url: String, location: String, maintainer: String) =
            RssHubInstance(
                id = id,
                url = url,
                location = location,
                maintainer = maintainer,
            )

        private fun encodeInstances(instances: List<RssHubInstance>): String {
            val array = JSONArray()
            instances.forEach { instance ->
                array.put(
                    JSONObject()
                        .put("id", instance.id)
                        .put("url", instance.url)
                        .put("location", instance.location)
                        .put("maintainer", instance.maintainer)
                        .put("enabled", instance.enabled)
                        .put("builtIn", instance.builtIn)
                )
            }
            return array.toString()
        }

        private fun decodeInstances(value: String): List<RssHubInstance> =
            runCatching {
                val array = JSONArray(value)
                buildList {
                    repeat(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        val url = normalizeInstanceUrl(item.getString("url"))
                        add(
                            RssHubInstance(
                                id = item.optString("id").ifBlank { "custom-${url.hashCode()}" },
                                url = url,
                                location = item.optString("location"),
                                maintainer = item.optString("maintainer"),
                                enabled = item.optBoolean("enabled", true),
                                builtIn = item.optBoolean("builtIn", false),
                            )
                        )
                    }
                }.distinctBy { it.url }
            }.getOrDefault(emptyList())

        private fun cooldownKey(instanceBaseUrl: String): String =
            KEY_COOLDOWN_PREFIX + instanceBaseUrl.hashCode()
    }
}
