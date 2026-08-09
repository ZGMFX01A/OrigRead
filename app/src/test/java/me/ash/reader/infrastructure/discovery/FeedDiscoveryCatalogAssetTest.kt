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
    fun `目录结构有效且达到首版来源规模`() {
        assertEquals(1, catalog.schemaVersion)
        assertEquals(catalog.feedCount, catalog.feeds.size)
        assertTrue(catalog.feedCount >= 700)
        assertTrue(catalog.categories.size >= 40)
        assertTrue(catalog.categories.all(SourceCategoryLabels::hasLocalizedLabel))
        assertTrue(catalog.sources.any { it.id == "awesome-rss-feeds" })
        assertTrue(catalog.sources.any { it.id == "bestblogs" })
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
    }

    @Test
    fun `保留上游原分类且包含 BestBlogs 文章源`() {
        assertTrue("Programming" in catalog.categories)
        assertTrue("Tech" in catalog.categories)
        assertTrue("Science" in catalog.categories)
        assertTrue("Business & Economy" in catalog.categories)
        assertTrue("Articles" in catalog.categories)
        assertTrue(catalog.feeds.any { feed -> feed.origins.any { it.sourceId == "bestblogs" } })
    }

    @Test
    fun `目录分类支持中英双语展示和搜索`() {
        assertEquals("编程", SourceCategoryLabels.localized("Programming", "zh-CN"))
        assertEquals("程式設計", SourceCategoryLabels.localized("Programming", "zh-TW"))
        assertEquals("Programming", SourceCategoryLabels.localized("Programming", "en-US"))
        assertTrue("网络安全" in SourceCategoryLabels.searchTerms("Cyber security"))
    }
}
