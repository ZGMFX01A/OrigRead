package me.ash.reader.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiRuleGenerationParsingTest {
    @Test
    fun `extract json object from markdown fence`() {
        val raw = """
            下面是规则：
            ```json
            {"id":"demo","name":"Demo"}
            ```
        """.trimIndent()

        assertEquals("{\"id\":\"demo\",\"name\":\"Demo\"}", extractJsonObject(raw))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject response without json object`() {
        extractJsonObject("无法生成")
    }
}
