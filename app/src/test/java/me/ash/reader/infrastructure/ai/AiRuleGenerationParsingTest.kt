package me.ash.reader.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `empty candidate validation error is translated for users`() {
        val message = aiRuleGenerationUserMessage(
            IllegalArgumentException("AI 候选连续两次未通过本地验证：String must not be empty")
        )

        assertTrue(message.contains("列表规则本地校验"))
        assertTrue(message.contains("候选规则包含空字段"))
        assertTrue(message.contains("换用当前 AI 服务中的其他模型"))
        assertTrue(!message.contains("String must not be empty"))
    }

    @Test
    fun `static website failure explains browser retry without exposing implementation error`() {
        val message = aiRuleGenerationUserMessage(
            AiWebsiteDynamicRetryException(
                "静态网页没有生成可用的列表规则：候选选择器没有解析出有效文章。可以点击“用浏览器渲染重试”"
            )
        )

        assertTrue(message.contains("用浏览器渲染重试"))
        assertTrue(message.contains("候选选择器没有解析出有效文章"))
        assertTrue(!message.contains("AiWebsiteDynamicRetryException"))
    }
}
