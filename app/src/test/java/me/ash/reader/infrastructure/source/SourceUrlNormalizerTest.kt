package me.ash.reader.infrastructure.source

import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceUrlNormalizerTest {
    @Test
    fun `normalizes harmless source url variations for duplicate checks`() {
        val expected = "https://example.com/feed"

        assertEquals(expected, SourceUrlNormalizer.comparisonKey(" HTTPS://EXAMPLE.COM:443/feed "))
        assertEquals(expected, SourceUrlNormalizer.comparisonKey("https://example.com/feed#latest"))
        assertNotEquals(expected, SourceUrlNormalizer.comparisonKey("https://example.com/feed/"))
        assertNotEquals(expected, SourceUrlNormalizer.comparisonKey("https://example.com/feed?utm_source=app&fbclid=abc"))
    }

    @Test
    fun `keeps meaningful feed query parameters distinct`() {
        assertNotEquals(
            SourceUrlNormalizer.comparisonKey("https://example.com/feed?category=ai&type=article"),
            SourceUrlNormalizer.comparisonKey("https://example.com/feed?category=business&type=article"),
        )
    }

    @Test
    fun `does not collapse http and https into one source`() {
        assertNotEquals(
            SourceUrlNormalizer.comparisonKey("http://example.com/feed"),
            SourceUrlNormalizer.comparisonKey("https://example.com/feed"),
        )
    }

    @Test
    fun `does not collapse www and apex hosts`() {
        assertNotEquals(
            SourceUrlNormalizer.comparisonKey("https://example.com/feed"),
            SourceUrlNormalizer.comparisonKey("https://www.example.com/feed"),
        )
    }

    @Test
    fun `normalizes host default port and fragment while preserving path and query`() {
        val expected = "https://example.com/feed/?utm_medium=share&category=ai&gclid=tracking"
        val actual =
            SourceUrlNormalizer.comparisonKey(
                "HTTPS://EXAMPLE.COM:443/feed/?utm_medium=share&category=ai&gclid=tracking#latest"
            )

        assertEquals(expected, actual)
        assertEquals("https://example.com/a%2Fb?q=a%2Bb&q=c", SourceUrlNormalizer.comparisonKey("https://EXAMPLE.com:443/a%2Fb?q=a%2Bb&q=c#section"))
        assertEquals("https://[2001:db8::1]/feed/", SourceUrlNormalizer.comparisonKey("https://[2001:db8::1]:443/feed/#latest"))
    }

    @Test
    fun `feed merge reuses equivalent urls but keeps scheme and www distinct`() {
        val feeds =
            listOf(
                Feed("1", "HTTPS", null, "https://example.com/feed", "group", 1),
                Feed("2", "HTTP", null, "http://example.com/feed", "group", 1),
                Feed("3", "WWW", null, "https://www.example.com/feed", "group", 1),
            )

        assertEquals(
            "1",
            findFeedByComparisonUrl(
                feeds,
                " HTTPS://EXAMPLE.COM:443/feed#latest ",
            )?.id,
        )
        assertEquals("2", findFeedByComparisonUrl(feeds, "http://example.com/feed")?.id)
        assertEquals("3", findFeedByComparisonUrl(feeds, "https://www.example.com/feed")?.id)
        assertEquals(null, findFeedByComparisonUrl(feeds, "https://example.com/feed/"))
        assertEquals(null, findFeedByComparisonUrl(feeds, "https://example.com/feed?utm_source=backup"))
    }
}
