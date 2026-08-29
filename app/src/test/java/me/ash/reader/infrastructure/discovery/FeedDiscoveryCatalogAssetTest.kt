package me.ash.reader.infrastructure.discovery

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedDiscoveryCatalogAssetTest {
    private val catalog: FeedCatalogData by lazy {
        val file =
            listOf(
                File("src/main/assets/source_catalog.json"),
                File("app/src/main/assets/source_catalog.json"),
            ).first(File::exists)
        Json { ignoreUnknownKeys = true }.decodeFromString<FeedCatalogData>(file.readText())
    }

    @Test
    fun `目录结构有效且包含四个上游来源`() {
        assertEquals(1, catalog.schemaVersion)
        assertEquals(catalog.feedCount, catalog.feeds.size)
        assertTrue(catalog.feedCount >= 2_400)
        assertTrue(catalog.categories.size <= 20)
        assertTrue(catalog.categories.all(SourceCategoryLabels::hasLocalizedLabel))
        assertTrue(catalog.sources.any { it.id == "awesome-rss-feeds" })
        assertTrue(catalog.sources.any { it.id == "bestblogs" })
        assertTrue(catalog.sources.any { it.id == "awesome-rss-feeds-list" })
        assertTrue(catalog.sources.any { it.id == "awesome-rsshub-routes" })
    }

    @Test
    fun `Feed URL 唯一且每条记录保留原始来源`() {
        assertEquals(catalog.feeds.size, catalog.feeds.map { it.feedUrl }.distinct().size)
        assertTrue(catalog.feeds.all { it.name.isNotBlank() })
        assertTrue(
            catalog.feeds.all {
                it.feedUrl.startsWith("http://") || it.feedUrl.startsWith("https://")
            }
        )
        assertTrue(catalog.feeds.all { it.categories.isNotEmpty() })
        assertTrue(catalog.feeds.all { it.origins.isNotEmpty() })
        assertTrue(
            catalog.feeds.sumOf { feed ->
                feed.origins.count { it.sourceId == "awesome-rss-feeds-list" }
            } >= 2_000
        )
        assertTrue(
            catalog.feeds.sumOf { feed ->
                feed.origins.count { it.sourceId == "awesome-rsshub-routes" }
            } >= 100
        )
    }

    @Test
    fun `展示分类精简且保留上游原分类`() {
        assertTrue("Programming" in catalog.categories)
        assertTrue("Tech & Engineering" in catalog.categories)
        assertTrue("Science & Nature" in catalog.categories)
        assertTrue("Business & Finance" in catalog.categories)
        assertTrue("Essays & Blogs" in catalog.categories)
        assertTrue(catalog.feeds.any { feed -> feed.origins.any { it.sourceId == "bestblogs" } })
        assertTrue(
            catalog.feeds.any { feed ->
                feed.origins.any {
                    it.sourceId == "awesome-rss-feeds-list" && it.category.startsWith("cn-")
                }
            }
        )
    }

    @Test
    fun `目录分类支持中英双语展示和搜索`() {
        assertEquals("编程", SourceCategoryLabels.localized("Programming", "zh-CN"))
        assertEquals("程式設計", SourceCategoryLabels.localized("Programming", "zh-TW"))
        assertEquals("Programming", SourceCategoryLabels.localized("Programming", "en-US"))
        assertTrue("网络安全" in SourceCategoryLabels.searchTerms("Security"))
    }

    @Test
    fun `真实目录支持站点地址和上游原分类搜索`() {
        val index = FeedCatalogIndex(catalog.feeds)
        val withSiteUrl = catalog.feeds.first { !it.siteUrl.isNullOrBlank() }
        val upstreamCategory = catalog.feeds.first().origins.first().category

        assertTrue(withSiteUrl in index.search(withSiteUrl.siteUrl!!))
        assertTrue(index.search(upstreamCategory).isNotEmpty())
    }

    @Test
    fun `真实目录站点地址按唯一或多源规则保守匹配`() {
        val index = FeedCatalogIndex(catalog.feeds)
        val siteGroups =
            catalog.feeds
                .filter { !it.siteUrl.isNullOrBlank() }
                .groupBy { FeedCatalogIndex.catalogComparisonKey(it.siteUrl!!) }
        val group = siteGroups.values.first()
        val sample = group.first()

        val match = index.matchUrl(sample.siteUrl!!)

        if (group.size == 1) {
            assertEquals(sample.id, match.preferred?.id)
            assertEquals(sample.feedUrl, match.preferredProbeUrl(sample.siteUrl!!))
        } else {
            assertEquals(null, match.preferred)
            assertEquals(group.size, match.totalSuggestions)
            assertTrue(match.suggestions.all { candidate -> candidate in group })
        }
    }
}
