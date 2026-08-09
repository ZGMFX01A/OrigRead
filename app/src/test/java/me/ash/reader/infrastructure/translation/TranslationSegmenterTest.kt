package me.ash.reader.infrastructure.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSegmenterTest {
    @Test
    fun `long text is split and can be merged without losing characters`() {
        val source = "第一句。第二句很长，需要继续拆分。Third sentence keeps going."
        val segments = TranslationSegmenter.splitAll(listOf(source), maxCharacters = 12)

        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.text.length <= 12 })
        assertEquals(source, segments.joinToString("") { it.text })
        assertEquals(
            listOf(source.uppercase()),
            TranslationSegmenter.merge(
                segments,
                segments.map { it.text.uppercase() },
                sourceCount = 1,
            ),
        )
    }

    @Test
    fun `multiple source blocks preserve their original indexes`() {
        val source = listOf("alpha beta gamma", "delta epsilon")
        val segments = TranslationSegmenter.splitAll(source, maxCharacters = 7)
        val merged =
            TranslationSegmenter.merge(
                segments,
                segments.map { "[${it.text}]" },
                sourceCount = source.size,
            )

        assertEquals(2, merged.size)
        assertTrue(merged[0].contains("alpha"))
        assertTrue(merged[1].contains("delta"))
    }
}

