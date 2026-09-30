package me.ash.reader.infrastructure.rsshub

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

data class RssHubSubscriptionDescriptor(
    val originalInput: String,
    val routePath: String? = null,
    val preferredInstance: String? = null,
    val lastResolvedInstance: String? = null,
    val lastResolvedUrl: String? = null,
)

/**
 * 保存 RSSHub 订阅的 logical route 与当前物理实例。
 * Feed.url 仍保存本轮实际抓取地址；routePath 才是跨实例稳定的订阅身份。
 */
@Singleton
class RssHubSubscriptionRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: RssHubSettingsRepository,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun record(feedId: String, sourceUrl: String) {
        val normalized = sourceUrl.trim()
        if (feedId.isBlank() || normalized.isBlank()) return
        record(feedId, RssHubSubscriptionDescriptor(originalInput = normalized))
    }

    fun record(feedId: String, descriptor: RssHubSubscriptionDescriptor) {
        val originalInput = descriptor.originalInput.trim()
        if (feedId.isBlank() || originalInput.isBlank()) return
        val normalized = descriptor.copy(originalInput = originalInput)
        check(preferences.edit()
            .putString(KEY_PREFIX + feedId, originalInput)
            .putString(DESCRIPTOR_PREFIX + feedId, encode(normalized))
            .commit()) {
            "Failed to persist RSSHub subscription descriptor"
        }
    }

    fun descriptor(feedId: String): RssHubSubscriptionDescriptor? {
        val encoded = preferences.getString(DESCRIPTOR_PREFIX + feedId, null)
        if (!encoded.isNullOrBlank()) decode(encoded)?.let { return withExplicitRoute(it) }
        return preferences.getString(KEY_PREFIX + feedId, null)
            ?.takeIf(String::isNotBlank)
            ?.let { withExplicitRoute(RssHubSubscriptionDescriptor(originalInput = it)) }
    }

    // Older backups and remote CONFIG carry only originalInput. Reconstruct explicit routes
    // before recovery and duplicate detection, including the binding for key/code routes.
    private fun withExplicitRoute(descriptor: RssHubSubscriptionDescriptor): RssHubSubscriptionDescriptor {
        if (descriptor.routePath != null) return descriptor
        val route = RssHubInputParser.parseExplicit(
            descriptor.originalInput,
            settingsRepository.current().instances.map { it.url },
        ) ?: return descriptor
        return descriptor.copy(
            routePath = route.routePath,
            preferredInstance = descriptor.preferredInstance ?: route.preferredInstance,
        )
    }

    fun sourceUrl(feedId: String): String? =
        descriptor(feedId)?.originalInput

    fun remove(feedId: String) {
        check(preferences.edit()
            .remove(KEY_PREFIX + feedId)
            .remove(DESCRIPTOR_PREFIX + feedId)
            .commit()) {
            "Failed to remove RSSHub subscription source"
        }
    }

    /** Remote Sync materialization path. null means this Feed has no RSSHub provenance. */
    fun replaceSyncSource(feedId: String, sourceUrl: String?) {
        val normalized = sourceUrl?.trim().orEmpty()
        if (normalized.isBlank()) {
            remove(feedId)
        } else if (this.sourceUrl(feedId) != normalized) {
            record(feedId, normalized)
        }
    }

    /** Only user/config provenance is synchronized; resolver success/cooldown state lives elsewhere. */
    fun listSyncSources(feedIds: Set<String>): Map<String, String> =
        feedIds.mapNotNull { feedId ->
            sourceUrl(feedId)?.let { sourceUrl -> feedId to sourceUrl }
        }.toMap()

    /** 导出指定账户订阅所对应的原始页面 URL。 */
    fun exportMappings(feedIds: Set<String>): Map<String, String> =
        feedIds.mapNotNull { feedId -> sourceUrl(feedId)?.let { feedId to it } }.toMap()

    fun exportDescriptors(feedIds: Set<String>): Map<String, RssHubSubscriptionDescriptor> =
        feedIds.mapNotNull { feedId -> descriptor(feedId)?.let { feedId to it } }.toMap()

    fun findFeedIdsByRoute(routePath: String): List<String> {
        val normalized = RssHubInputParser.normalizeRoutePath(routePath) ?: return emptyList()
        return preferences.all.asSequence()
            .mapNotNull { (key, value) ->
                if (value !is String) null
                else when {
                    key.startsWith(DESCRIPTOR_PREFIX) -> key.removePrefix(DESCRIPTOR_PREFIX)
                    key.startsWith(KEY_PREFIX) -> key.removePrefix(KEY_PREFIX)
                    else -> null
                }
            }
            .distinct()
            .filter { descriptor(it)?.routePath == normalized }
            .toList()
    }

    /** 按订阅恢复阶段生成的新旧 ID 映射写回 RSSHub 原始页面地址。 */
    fun restoreMappings(mappings: Map<String, String>, feedIdMap: Map<String, String>) {
        mappings.forEach { (oldFeedId, sourceUrl) ->
            feedIdMap[oldFeedId]?.let { newFeedId -> record(newFeedId, sourceUrl) }
        }
    }

    fun restoreDescriptors(
        mappings: Map<String, RssHubSubscriptionDescriptor>,
        feedIdMap: Map<String, String>,
    ) {
        mappings.forEach { (oldFeedId, descriptor) ->
            feedIdMap[oldFeedId]?.let { newFeedId -> record(newFeedId, descriptor) }
        }
    }

    private fun encode(value: RssHubSubscriptionDescriptor): String =
        JSONObject()
            .put("originalInput", value.originalInput)
            .put("routePath", value.routePath)
            .put("preferredInstance", value.preferredInstance)
            .put("lastResolvedInstance", value.lastResolvedInstance)
            .put("lastResolvedUrl", value.lastResolvedUrl)
            .toString()

    private fun decode(value: String): RssHubSubscriptionDescriptor? =
        runCatching {
            val json = JSONObject(value)
            RssHubSubscriptionDescriptor(
                originalInput = json.getString("originalInput"),
                routePath = json.optNullableString("routePath"),
                preferredInstance = json.optNullableString("preferredInstance"),
                lastResolvedInstance = json.optNullableString("lastResolvedInstance"),
                lastResolvedUrl = json.optNullableString("lastResolvedUrl"),
            )
        }.getOrNull()

    private fun JSONObject.optNullableString(key: String): String? =
        opt(key)
            ?.takeUnless { it == JSONObject.NULL }
            ?.toString()
            ?.takeIf(String::isNotBlank)

    private companion object {
        const val PREFERENCES_NAME = "rsshub_subscriptions"
        const val KEY_PREFIX = "source_url_"
        const val DESCRIPTOR_PREFIX = "descriptor_"
    }
}
