package me.ash.reader.infrastructure.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticleUrlPatternNormalizerTest {
    @Test
    fun `normalizes numeric date slug uuid and query ids`() {
        assertEquals(
            "example.com/news/{number}.html",
            normalize("https://www.example.com/news/123456.html"),
        )
        assertEquals(
            "example.com/news/{year}/{month}/{day}/{slug}",
            normalize("https://example.com/news/2026/08/05/origread-release-notes"),
        )
        assertEquals(
            "example.com/posts/{uuid}",
            normalize("https://example.com/posts/550e8400-e29b-41d4-a716-446655440000"),
        )
        assertEquals(
            "example.com/article?id={number}",
            normalize("https://example.com/article?id=98765&utm_source=test"),
        )
        assertEquals(
            "example.com/article?preview",
            normalize("https://example.com/article?preview"),
        )
    }

    @Test
    fun `accepts same-site subdomains and rejects external links`() {
        assertEquals(
            "example.com/article/{number}",
            ArticleUrlPatternNormalizer.normalize(
                "https://news.example.com/article/123",
                "www.example.com",
            )?.key,
        )
        assertNull(
            ArticleUrlPatternNormalizer.normalize(
                "https://external.example.net/article/123",
                "example.com",
            ),
        )
    }

    private fun normalize(url: String): String? =
        ArticleUrlPatternNormalizer.normalize(url, "example.com")?.key
}
