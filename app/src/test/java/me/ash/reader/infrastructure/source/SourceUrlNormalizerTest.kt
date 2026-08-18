package me.ash.reader.infrastructure.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceUrlNormalizerTest {
    @Test
    fun `normalizes harmless source url variations for duplicate checks`() {
        val expected = "https://example.com/feed"

        assertEquals(expected, SourceUrlNormalizer.comparisonKey(" HTTPS://EXAMPLE.COM:443/feed/ "))
        assertEquals(expected, SourceUrlNormalizer.comparisonKey("https://example.com/feed#latest"))
        assertEquals(expected, SourceUrlNormalizer.comparisonKey("https://example.com/feed?utm_source=app&fbclid=abc"))
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
}
