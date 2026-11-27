package me.ash.reader.infrastructure.android
import org.junit.Assert.assertEquals
import org.junit.Test
class TextToSpeechBoundaryTest {
    @Test fun `language sample supports empty single character and maximum boundary`() {
        assertEquals("", speechLocaleSample(""))
        assertEquals("字", speechLocaleSample("字"))
        assertEquals("x".repeat(500), speechLocaleSample("x".repeat(501)))
    }
}
