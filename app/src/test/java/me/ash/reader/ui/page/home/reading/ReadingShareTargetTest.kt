package me.ash.reader.ui.page.home.reading

import me.ash.reader.infrastructure.preference.ReadingShareTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingShareTargetTest {
    @Test
    fun `saved notion target falls back to system after notion is uninstalled`() {
        assertEquals(
            ReadingShareTarget.SYSTEM,
            normalizeReadingShareTarget(
                target = ReadingShareTarget.NOTION,
                notionAvailable = false,
            ),
        )
    }

    @Test
    fun `saved notion target is preserved while notion remains installed`() {
        assertEquals(
            ReadingShareTarget.NOTION,
            normalizeReadingShareTarget(
                target = ReadingShareTarget.NOTION,
                notionAvailable = true,
            ),
        )
    }
}
