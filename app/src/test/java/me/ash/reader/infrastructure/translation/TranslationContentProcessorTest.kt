package me.ash.reader.infrastructure.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationContentProcessorTest {
    private val processor = TranslationContentProcessor()

    @Test
    fun `translated mode replaces readable blocks and keeps images`() {
        val prepared =
            processor.prepare(
                """
                <article>
                  <p>Hello world</p>
                  <p>Second paragraph <img src="https://example.com/a.png"></p>
                </article>
                """.trimIndent()
            )

        val result =
            processor.render(
                prepared,
                listOf("你好世界", "第二段"),
                TranslationDisplayMode.TRANSLATED,
            )

        assertTrue(result.contains("你好世界"))
        assertTrue(result.contains("第二段"))
        assertTrue(result.contains("a.png"))
        assertFalse(result.contains("Hello world"))
    }

    @Test
    fun `bilingual mode keeps original blocks and appends translations`() {
        val prepared = processor.prepare("<p>Hello world</p><blockquote>Quoted text</blockquote>")
        val result =
            processor.render(
                prepared,
                listOf("你好世界", "引用文字"),
                TranslationDisplayMode.BILINGUAL,
            )

        assertTrue(result.contains("Hello world"))
        assertTrue(result.contains("你好世界"))
        assertTrue(result.contains("origread-translation"))
    }
}

