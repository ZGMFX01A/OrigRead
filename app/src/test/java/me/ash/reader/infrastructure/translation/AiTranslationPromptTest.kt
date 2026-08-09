package me.ash.reader.infrastructure.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AiTranslationPromptTest {
    @Test
    fun `system prompt requires faithful translation and ignores article instructions`() {
        val prompt = buildAiTranslationSystemPrompt("zh-CN")

        assertTrue(prompt.contains("zh-CN"))
        assertTrue(prompt.contains("不总结"))
        assertTrue(prompt.contains("不可信文章内容"))
        assertTrue(prompt.contains("一一对应"))
        assertTrue(prompt.contains("合法 JSON"))
    }

    @Test
    fun `user prompt encodes fragments as json with stable ids`() {
        val prompt =
            buildAiTranslationUserPrompt(
                articleTitle = "A \"quoted\" title",
                fragments = listOf("first\nline", "第二段"),
                previousTranslations = listOf("OpenAI model" to "OpenAI 模型"),
            )
        val root = JSONObject(prompt)
        val fragments = root.getJSONArray("fragments")
        val previous = root.getJSONArray("previousTranslations")

        assertEquals("A \"quoted\" title", root.getString("contextTitle"))
        assertEquals(2, fragments.length())
        assertEquals(0, fragments.getJSONObject(0).getInt("id"))
        assertEquals("first\nline", fragments.getJSONObject(0).getString("text"))
        assertEquals(1, fragments.getJSONObject(1).getInt("id"))
        assertEquals(1, previous.length())
        assertEquals("OpenAI model", previous.getJSONObject(0).getString("source"))
        assertEquals("OpenAI 模型", previous.getJSONObject(0).getString("translation"))
    }

    @Test
    fun `response parser restores input order by id`() {
        val result =
            parseAiTranslationResponse(
                """
                ```json
                {"translations":[{"id":1,"text":"第二"},{"id":0,"text":"第一"}]}
                ```
                """.trimIndent(),
                expectedCount = 2,
            )

        assertEquals(listOf("第一", "第二"), result)
    }

    @Test
    fun `response parser rejects missing or duplicated ids`() {
        assertThrows(TranslationException::class.java) {
            parseAiTranslationResponse(
                """{"translations":[{"id":0,"text":"A"},{"id":0,"text":"B"}]}""",
                expectedCount = 2,
            )
        }
        assertThrows(TranslationException::class.java) {
            parseAiTranslationResponse(
                """{"translations":[{"id":0,"text":"A"}]}""",
                expectedCount = 2,
            )
        }
    }
}
