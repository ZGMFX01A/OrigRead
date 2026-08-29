package me.ash.reader.infrastructure.discovery

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 内置来源目录的数据源说明，用于在发现页保留上游归属。 */
@Serializable
data class FeedCatalogSource(
    val id: String,
    val name: String,
    val url: String,
    val license: String? = null,
)

/** 单个 Feed 在上游数据集中的原始分类。 */
@Serializable
data class FeedCatalogOrigin(
    val sourceId: String,
    val category: String,
)

/** 可直接交给现有订阅流程验证并订阅的 Feed 条目。 */
@Serializable
data class FeedCatalogEntry(
    val id: String,
    val name: String,
    val feedUrl: String,
    val siteUrl: String? = null,
    val categories: List<String> = emptyList(),
    val origins: List<FeedCatalogOrigin> = emptyList(),
)

@Serializable
data class FeedCatalogData(
    val schemaVersion: Int,
    val generatedAt: String? = null,
    val feedCount: Int,
    val categories: List<String>,
    val sources: List<FeedCatalogSource>,
    val feeds: List<FeedCatalogEntry>,
)

/**
 * 从 APK 内置 asset 读取来源发现目录。
 *
 * 目录只保存上游公开 OPML 的元数据，不在手机端联网抓取正文来做分类。
 */
@Singleton
class FeedDiscoveryCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val data: FeedCatalogData by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString<FeedCatalogData>(reader.readText()).also { catalog ->
                require(catalog.schemaVersion == SCHEMA_VERSION) {
                    "不支持的来源目录版本：${catalog.schemaVersion}"
                }
                require(catalog.feedCount == catalog.feeds.size) { "来源目录数量校验失败" }
            }
        }
    }

    private val index: FeedCatalogIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FeedCatalogIndex(data.feeds)
    }

    fun search(
        query: String,
        selectedCategory: String? = null,
    ): List<FeedCatalogEntry> = index.search(query, selectedCategory)

    fun matchUrl(url: String): FeedCatalogUrlMatch = index.matchUrl(url)

    companion object {
        private const val ASSET_NAME = "source_catalog.json"
        private const val SCHEMA_VERSION = 1
    }
}
