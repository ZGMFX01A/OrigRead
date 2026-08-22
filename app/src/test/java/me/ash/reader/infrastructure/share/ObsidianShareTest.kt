package me.ash.reader.infrastructure.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ObsidianShareTest {
    @Test
    fun `sanitizes invalid and control characters from note names`() {
        assertEquals(
            "Article title with spaces",
            ObsidianShare.sanitizeFileName(" Article:/title\u0000 with   spaces "),
        )
    }

    @Test
    fun `uses fallback name for blank title`() {
        assertEquals("OrigRead", ObsidianShare.sanitizeFileName("...   "))
    }

    @Test
    fun `limits note name length`() {
        assertEquals(120, ObsidianShare.sanitizeFileName("a".repeat(200)).length)
    }
}
