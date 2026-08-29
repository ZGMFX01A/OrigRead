package me.ash.reader.infrastructure.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedCatalogIndexTest {
    private val primary =
        FeedCatalogEntry(
            id = "primary",
            name = "Example Engineering",
            feedUrl = "https://feeds.example.com/main.xml",
            siteUrl = "https://www.example.com/",
            categories = listOf("Tech & Engineering"),
            origins = listOf(FeedCatalogOrigin("upstream", "cn-tech-teams")),
        )
    private val secondary =
        FeedCatalogEntry(
            id = "secondary",
            name = "Example AI",
            feedUrl = "https://feeds.example.com/ai.xml",
            siteUrl = "https://example.com/ai",
            categories = listOf("AI"),
            origins = listOf(FeedCatalogOrigin("upstream", "cn-ai-research")),
        )
    private val index = FeedCatalogIndex(listOf(primary, secondary))

    @Test
    fun `search includes name feed url site url display category and upstream category`() {
        assertEquals(listOf(primary), index.search("Example Engineering"))
        assertEquals(listOf(primary, secondary), index.search("feeds.example.com"))
        assertEquals(listOf(primary, secondary), index.search("https://example.com"))
        assertEquals(listOf(primary), index.search("科技与工程"))
        assertEquals(listOf(primary), index.search("cn-tech-teams"))
        assertEquals(listOf(secondary), index.search("cn-ai-research"))
    }

    @Test
    fun `search normalizes scheme www and trailing slash without changing stored urls`() {
        assertEquals(listOf(primary, secondary), index.search("www.example.com/"))
        assertEquals(listOf(primary, secondary), index.search("https://www.example.com/"))
        assertEquals("https://feeds.example.com/main.xml", primary.feedUrl)
    }

    @Test
    fun `exact feed match never creates a duplicate probe`() {
        val match = index.matchUrl("http://www.feeds.example.com/main.xml/")

        assertEquals(primary, match.preferred)
        assertTrue(match.suggestions.isEmpty())
        assertNull(match.preferredProbeUrl("https://feeds.example.com/main.xml"))
    }

    @Test
    fun `unique exact site match can validate known feed while original chain remains available`() {
        val match = index.matchUrl("https://www.example.com/")

        assertEquals(primary, match.preferred)
        assertEquals(listOf(primary), match.suggestions)
        assertEquals("https://feeds.example.com/main.xml", match.preferredProbeUrl("https://example.com"))
    }

    @Test
    fun `same host with a different path is suggestion only and never auto replaces input`() {
        val match = index.matchUrl("https://example.com/articles/42")

        assertNull(match.preferred)
        assertEquals(2, match.totalSuggestions)
        assertEquals(setOf(primary.id, secondary.id), match.suggestions.map { it.id }.toSet())
        assertNull(match.preferredProbeUrl("https://example.com/articles/42"))
    }

    @Test
    fun `multiple exact site matches are suggestion only`() {
        val another =
            FeedCatalogEntry(
                id = "another",
                name = "Example News",
                feedUrl = "https://feeds.example.com/news.xml",
                siteUrl = "https://www.example.com/",
            )
        val multiIndex = FeedCatalogIndex(listOf(primary, another))

        val match = multiIndex.matchUrl("https://www.example.com/")

        assertNull(match.preferred)
        assertEquals(2, match.totalSuggestions)
    }
}
