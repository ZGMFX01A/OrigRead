package me.ash.reader.infrastructure.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedJsonExtractorTest {
    @Test
    fun `should extract next data`() {
        val html = """
            <html><body>
            <script id="__NEXT_DATA__" type="application/json">{"props":{"items":[1]}}</script>
            </body></html>
        """.trimIndent()

        assertEquals(
            "{\"props\":{\"items\":[1]}}",
            EmbeddedJsonExtractor.extractNextData(html, "https://example.com/news"),
        )
    }

    @Test
    fun `should extract nuxt data attribute variant`() {
        val html = """
            <script type="application/json" data-nuxt-data="app">[{"items":[1]}]</script>
        """.trimIndent()

        assertEquals(
            "[{\"items\":[1]}]",
            EmbeddedJsonExtractor.extractNuxtData(html, "https://example.com/"),
        )
        assertNull(EmbeddedJsonExtractor.extractNextData(html, "https://example.com/"))
    }
}
