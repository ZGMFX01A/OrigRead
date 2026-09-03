package me.ash.reader.infrastructure.language

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemLanguageTest {
    @Test
    fun `known language tag is shown as readable name`() {
        val displayed = displayLanguageValue("zh-Hans", Locale.SIMPLIFIED_CHINESE)

        assertEquals("简体中文", displayed)
    }

    @Test
    fun `display name follows current ui language`() {
        val displayed = displayLanguageValue("zh-Hant", Locale.ENGLISH)

        assertEquals("Chinese (Traditional)", displayed)
    }

    @Test
    fun `traditional ui uses traditional chinese names`() {
        assertEquals(
            "簡體中文",
            displayLanguageValue("zh-Hans", Locale.forLanguageTag("zh-Hant-TW")),
        )
    }

    @Test
    fun `free form language names stay unchanged`() {
        assertEquals("English", displayLanguageValue("English", Locale.SIMPLIFIED_CHINESE))
        assertEquals("简体中文", displayLanguageValue("简体中文", Locale.ENGLISH))
    }

    @Test
    fun `non language values are not rewritten`() {
        assertEquals(
            "https://example.com",
            displayLanguageValue("https://example.com", Locale.ENGLISH),
        )
    }
}
