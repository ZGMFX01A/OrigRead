package me.ash.reader.infrastructure.rsshub

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保存 RSSHub 订阅对应的原始页面 URL。
 * 最终 RSSHub 地址已保存在 Feed.url；原始 URL 仅用于实例或路由失效后的重新匹配。
 */
@Singleton
class RssHubSubscriptionRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun record(feedId: String, sourceUrl: String) {
        val normalized = sourceUrl.trim()
        if (feedId.isBlank() || normalized.isBlank()) return
        preferences.edit().putString(KEY_PREFIX + feedId, normalized).apply()
    }

    fun sourceUrl(feedId: String): String? =
        preferences.getString(KEY_PREFIX + feedId, null)?.takeIf(String::isNotBlank)

    fun remove(feedId: String) {
        preferences.edit().remove(KEY_PREFIX + feedId).apply()
    }

    /** 导出指定账户订阅所对应的原始页面 URL。 */
    fun exportMappings(feedIds: Set<String>): Map<String, String> =
        feedIds.mapNotNull { feedId -> sourceUrl(feedId)?.let { feedId to it } }.toMap()

    /** 按订阅恢复阶段生成的新旧 ID 映射写回 RSSHub 原始页面地址。 */
    fun restoreMappings(mappings: Map<String, String>, feedIdMap: Map<String, String>) {
        mappings.forEach { (oldFeedId, sourceUrl) ->
            feedIdMap[oldFeedId]?.let { newFeedId -> record(newFeedId, sourceUrl) }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "rsshub_subscriptions"
        const val KEY_PREFIX = "source_url_"
    }
}
