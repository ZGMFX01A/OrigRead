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

    private val bundledInstances = loadBundledInstances(context).ifEmpty(::defaultInstances)

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
        updateCooldown(cooldownKey(normalized), nowMillis)
    }

    /** route family 级成功记录，避免“实例 healthz 正常”被误当成所有反爬 route 都可用。 */
    fun recordRouteSuccess(instanceBaseUrl: String, routeFamily: String) {
        val normalized = normalizeInstanceUrl(instanceBaseUrl)
        preferences.edit()
            .putString(routeLastSuccessKey(routeFamily), normalized)
            .remove(routeCooldownKey(normalized, routeFamily))
            .apply()
    }

    /** route family 级失败冷却；不会把该实例对其它 RSSHub route 一并判死。 */
    fun recordRouteFailure(
        instanceBaseUrl: String,
        routeFamily: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val normalized = normalizeInstanceUrl(instanceBaseUrl)
        updateCooldown(routeCooldownKey(normalized, routeFamily), nowMillis)
    }

    /** 清理到期的临时键；串行化清理和写入，避免并发失败记录相互删除新冷却。 */
    @Synchronized
    private fun updateCooldown(key: String, nowMillis: Long) {
        val editor = preferences.edit()
        preferences.all.orEmpty().forEach { (storedKey, value) ->
            if ((storedKey.startsWith(KEY_COOLDOWN_PREFIX) || storedKey.startsWith(KEY_ROUTE_COOLDOWN_PREFIX)) &&
                value is Long && value <= nowMillis) {
                editor.remove(storedKey)
            }
        }
        editor.putLong(key, nowMillis + INSTANCE_COOLDOWN_MILLIS).apply()
    }

    /**
     * 最近成功实例优先，其余启用实例保持设置页顺序。
     * 冷却实例只降到列表末尾，不再彻底排除；手动来源探测仍有机会从瞬时失败中恢复。
     */
    fun candidateInstances(nowMillis: Long = System.currentTimeMillis()): List<String> {
        val enabledInstances = current().instances.filter { it.enabled }.map { it.url }
        val lastSuccess = preferences.getString(KEY_LAST_SUCCESS_INSTANCE, null)
        val ordered = orderInstances(lastSuccess, *enabledInstances.toTypedArray())
            .filter { it in enabledInstances }
        val (ready, cooling) =
            ordered.partition { instance -> preferences.getLong(cooldownKey(instance), 0L) <= nowMillis }
        return ready + cooling
    }

    fun candidateInstancesForRoute(
        routeFamily: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<String> {
        val enabledInstances = current().instances.filter { it.enabled }.map { it.url }
        val routeLastSuccess = preferences.getString(routeLastSuccessKey(routeFamily), null)
        val globalLastSuccess = preferences.getString(KEY_LAST_SUCCESS_INSTANCE, null)
        val ordered = orderInstances(routeLastSuccess, globalLastSuccess, *enabledInstances.toTypedArray())
            .filter { it in enabledInstances }
        val (ready, cooling) =
            ordered.partition { instance ->
                val globalCooldown = preferences.getLong(cooldownKey(instance), 0L)
                val routeCooldown = preferences.getLong(routeCooldownKey(instance, routeFamily), 0L)
                maxOf(globalCooldown, routeCooldown) <= nowMillis
            }
        return ready + cooling
    }

    fun restoreDefault() {
        val editor = preferences.edit()
            .putBoolean(KEY_ENABLED, true)
            .remove(KEY_INSTANCES)
            .remove(KEY_LEGACY_INSTANCE_URL)
            .remove(KEY_LAST_SUCCESS_INSTANCE)
        preferences.all.keys
            .filter { key ->
                key.startsWith(KEY_COOLDOWN_PREFIX) ||
                    key.startsWith(KEY_ROUTE_LAST_SUCCESS_PREFIX) ||
                    key.startsWith(KEY_ROUTE_COOLDOWN_PREFIX)
            }
            .forEach(editor::remove)
        editor.apply()
        _settings.value = RssHubSettings(instances = bundledInstances)
    }

    /** 完整配置恢复入口；网络成功记录和失败冷却属于临时状态，不随备份迁移。 */
    fun restoreBackup(settings: RssHubSettings) {
        val normalizedInstances =
            settings.instances
                .map { instance ->
                    instance.copy(
                        url = normalizeInstanceUrl(instance.url),
                        location = RssHubLocation.canonical(instance.id, instance.location),
                    )
                }
                .distinctBy(RssHubInstance::url)
                .ifEmpty { bundledInstances }
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
                decodeInstances(stored).ifEmpty { bundledInstances }
            }
        return RssHubSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, true),
            instances = instances,
        )
    }

    /** 兼容此前只保存一个实例地址的版本，并补齐新的公共实例列表。 */
    private fun migrateLegacyInstances(): List<RssHubInstance> {
        val legacy = preferences.getString(KEY_LEGACY_INSTANCE_URL, null)
        if (legacy.isNullOrBlank()) return bundledInstances
        val normalized = normalizeInstanceUrl(legacy)
        return bundledInstances.map { instance ->
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

    private fun loadBundledInstances(context: Context): List<RssHubInstance> =
        runCatching {
            val raw =
                context.assets.open(INSTANCE_CATALOG_ASSET)
                    .bufferedReader()
                    .use { it.readText() }
            val root = JSONObject(raw)
            check(root.optInt("schemaVersion") == INSTANCE_CATALOG_SCHEMA_VERSION)
            val items = root.getJSONArray("instances")
            buildList {
                repeat(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    val url = normalizeInstanceUrl(item.getString("url"))
                    add(
                        RssHubInstance(
                            id = item.getString("id"),
                            url = url,
                            location =
                                RssHubLocation.canonical(
                                    item.getString("id"),
                                    item.optString("location"),
                                ),
                            maintainer = item.optString("maintainer"),
                            enabled = item.optBoolean("enabled", true),
                            builtIn = true,
                        )
                    )
                }
            }.distinctBy(RssHubInstance::url)
        }.getOrDefault(emptyList())

    companion object {
        private const val PREFERENCES_NAME = "rsshub_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INSTANCES = "instances"
        private const val KEY_LEGACY_INSTANCE_URL = "instance_url"
        private const val KEY_LAST_SUCCESS_INSTANCE = "last_success_instance"
        private const val KEY_COOLDOWN_PREFIX = "cooldown_until_"
        private const val KEY_ROUTE_LAST_SUCCESS_PREFIX = "route_last_success_"
        private const val KEY_ROUTE_COOLDOWN_PREFIX = "route_cooldown_until_"
        private const val INSTANCE_COOLDOWN_MILLIS = 5 * 60 * 1000L
        private const val INSTANCE_CATALOG_ASSET = "rsshub_instances.json"
        private const val INSTANCE_CATALOG_SCHEMA_VERSION = 1

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
                instance("isrss", "https://rsshub.isrss.com", "US", "isRSS"),
                instance("cups", "https://rsshub.cups.moe", "US", "FunnyCups"),
                instance("slarker", "https://hub.slarker.me", "US", "Slarker"),
                instance("rssforever", "https://rsshub.rssforever.com", "AE", "Stille"),
                instance("virworks", "https://rsshub-balancer.virworks.moe", "GLOBAL", "chesha1"),
                instance("official", "https://rsshub.app", "US", "DIYgod", enabled = false),
                instance("pseudoyu", "https://rsshub.pseudoyu.com", "FR", "pseudoyu"),
                instance("rsstips", "https://rsshub.rss.tips", "US", "AboutRSS"),
                instance("ktachibana", "https://rsshub.ktachibana.party", "US", "KTachibanaM"),
                instance("owonz", "https://rss.owo.nz", "DE", "Vincent Yang"),
                instance("wudifeixue", "https://rss.wudifeixue.com", "CA", "wudifeixue"),
                instance("henry", "https://rsshub.henry.wang", "GB", "HenryQW"),
                instance("umzzz", "https://rsshub.umzzz.com", "HK", "nesay"),
                instance("emailonce", "https://rsshub.email-once.com", "HK", "EmailOnce"),
                instance("datuan", "https://rss.datuan.dev", "VN", "Tuấn Dev"),
                instance("spriple", "https://rss.spriple.org", "CN", "Spriple"),
            )

        private fun instance(
            id: String,
            url: String,
            location: String,
            maintainer: String,
            enabled: Boolean = true,
        ) =
            RssHubInstance(
                id = id,
                url = url,
                location = location,
                maintainer = maintainer,
                enabled = enabled,
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
                                location =
                                    RssHubLocation.canonical(
                                        item.optString("id"),
                                        item.optString("location"),
                                    ),
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

        private fun routeLastSuccessKey(routeFamily: String): String =
            KEY_ROUTE_LAST_SUCCESS_PREFIX + routeFamily.hashCode()

        private fun routeCooldownKey(instanceBaseUrl: String, routeFamily: String): String =
            KEY_ROUTE_COOLDOWN_PREFIX + "$instanceBaseUrl|$routeFamily".hashCode()
    }
}
