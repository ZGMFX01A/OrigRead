package me.ash.reader.infrastructure.content

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleWebSessionManagerTest {
    @Test
    fun `webview ua is normalized to browser style`() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        val normalized = BrowserUserAgentPolicy.normalize(raw)

        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            normalized,
        )
    }

    @Test
    fun `source discovery ua is normalized to desktop chrome`() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        val normalized = BrowserUserAgentPolicy.normalizeDesktop(raw)

        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            normalized,
        )
    }
}
