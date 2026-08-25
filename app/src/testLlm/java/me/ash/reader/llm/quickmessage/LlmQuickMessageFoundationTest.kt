package me.ash.reader.llm.quickmessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmQuickMessageFoundationTest {
    @Test
    fun `template resolves reading variables from current snapshot`() {
        val result =
            resolveQuickMessageTemplate(
                template = "标题={{article_title}}\n链接={{article_url}}\n选区={{selection}}\n摘要={{summary}}",
                context =
                    LlmQuickMessageContext(
                        articleTitle = "OrigRead P6.4",
                        articleUrl = "https://example.com/p64",
                        selection = "selected paragraph",
                        summary = "summary snapshot",
                    ),
            )

        assertTrue(result.ready)
        assertEquals(
            "标题=OrigRead P6.4\n链接=https://example.com/p64\n选区=selected paragraph\n摘要=summary snapshot",
            result.content,
        )
        assertTrue(result.unavailableVariables.isEmpty())
        assertTrue(result.unsupportedVariables.isEmpty())
    }

    @Test
    fun `missing declared reading variable blocks send instead of leaking placeholder`() {
        val result =
            resolveQuickMessageTemplate(
                template = "解释这段：{{selection}}",
                context =
                    LlmQuickMessageContext(
                        articleTitle = "Article",
                        articleUrl = null,
                        selection = null,
                        summary = null,
                    ),
            )

        assertFalse(result.ready)
        assertNull(result.content)
        assertEquals(listOf("selection"), result.unavailableVariables)
        assertTrue(result.unsupportedVariables.isEmpty())
    }

    @Test
    fun `unknown variable blocks send explicitly`() {
        val result =
            resolveQuickMessageTemplate(
                template = "{{article_title}} {{future_context}}",
                context =
                    LlmQuickMessageContext(
                        articleTitle = "Article",
                        articleUrl = null,
                        selection = null,
                        summary = null,
                    ),
            )

        assertFalse(result.ready)
        assertNull(result.content)
        assertEquals(listOf("future_context"), result.unsupportedVariables)
    }

    @Test
    fun `plain quick message remains a normal trimmed user message`() {
        val result =
            resolveQuickMessageTemplate(
                template = "  请检查文章中的论据。  ",
                context =
                    LlmQuickMessageContext(
                        articleTitle = "Article",
                        articleUrl = null,
                        selection = null,
                        summary = null,
                    ),
            )

        assertTrue(result.ready)
        assertEquals("请检查文章中的论据。", result.content)
    }

    @Test
    fun `normalize order removes duplicate ids and produces contiguous order`() {
        val normalized =
            LlmQuickMessageRepository.normalizeOrder(
                listOf(
                    LlmQuickMessage(id = "b", title = "B", content = "b", order = 8),
                    LlmQuickMessage(id = "a", title = "A", content = "a", order = 2),
                    LlmQuickMessage(id = "a", title = "A2", content = "a2", order = 0),
                )
            )

        assertEquals(listOf("a", "b"), normalized.map(LlmQuickMessage::id))
        assertEquals(listOf(0, 1), normalized.map(LlmQuickMessage::order))
    }
}
