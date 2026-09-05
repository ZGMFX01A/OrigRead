package me.ash.reader.infrastructure.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceInputClassifierTest {
    @Test
    fun `rss shaped urls produce rss hint without becoming authoritative`() {
        val urls =
            listOf(
                "https://example.com/feed.xml",
                "https://example.com/rss.xml",
                "https://example.com/atom",
                "https://myblog.blogspot.com/feeds/posts/default",
                "http://feeds.feedburner.com/GoogleOperatingSystem",
                "https://example.com/articles?format=rss",
            )

        urls.forEach { url ->
            assertTrue("Expected $url to look RSS-like", isRssLikelyUrl(url))
            assertEquals(SourceInputHint.RSS_LIKELY, sourceInputHint(url))
        }
    }

    @Test
    fun `json shaped urls produce json hint without becoming authoritative`() {
        val urls =
            listOf(
                "https://engineering.fb.com/wp-json/wp/v2/posts?_embed=1&per_page=30",
                "https://example.com/posts.json",
                "https://example.com/api/v1/posts",
                "https://example.com/data?format=json",
            )

        urls.forEach { url ->
            assertTrue("Expected $url to look JSON-like", isJsonLikelyUrl(url))
            assertEquals(SourceInputHint.JSON_LIKELY, sourceInputHint(url))
        }
    }

    @Test
    fun `conflicting rss and json features only reorder probes by weight`() {
        val strongRssUrls =
            listOf(
                "https://example.com/api/feed.xml",
                "https://example.com/api/v1/posts.atom",
                "https://example.com/api/stream?type=rss",
            )
        strongRssUrls.forEach { url ->
            assertTrue("Expected $url to look RSS-like", isRssLikelyUrl(url))
            assertTrue("Expected $url to also look JSON-like", isJsonLikelyUrl(url))
            assertEquals(SourceInputHint.RSS_LIKELY, sourceInputHint(url))
        }

        val weakRssInsideApi = "https://example.com/api/v1/feed"
        assertTrue(isRssLikelyUrl(weakRssInsideApi))
        assertTrue(isJsonLikelyUrl(weakRssInsideApi))
        assertEquals(SourceInputHint.JSON_LIKELY, sourceInputHint(weakRssInsideApi))
    }

    @Test
    fun `known rsshub route uses configured base path instead of host only`() {
        assertTrue(isKnownRssHubEndpoint("https://rsshub.app/bilibili/user/video/2267573"))
        assertFalse(isKnownRssHubEndpoint("https://rsshub.app"))

        val instances = listOf("https://hub.example.com/rsshub")
        assertTrue(isKnownRssHubEndpoint("https://hub.example.com/rsshub/bilibili/123", instances))
        assertFalse(isKnownRssHubEndpoint("https://hub.example.com/blog/article-1", instances))
        assertFalse(isKnownRssHubEndpoint("https://hub.example.com/rsshub", instances))
        assertFalse(isKnownRssHubEndpoint("https://hub.example.com/rsshub/healthz", instances))
    }

    @Test
    fun `ordinary pages stay generic`() {
        val urls =
            listOf(
                "https://example.com",
                "https://example.com/blog",
                "https://example.com/articles/how-to-code",
                "https://v2ex.com/t/123456",
                "https://github.com/torvalds/linux",
            )

        urls.forEach { url ->
            assertFalse(isRssLikelyUrl(url))
            assertFalse(isJsonLikelyUrl(url))
            assertEquals(SourceInputHint.GENERIC, sourceInputHint(url))
        }
    }
}
