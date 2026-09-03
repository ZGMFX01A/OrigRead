package me.ash.reader.llm.quickmessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmQuickMessageFoundationTest {
    @Test
    fun `template resolves original article variables from current snapshot`() {
        val result =
            resolveQuickMessageTemplate(
                template = "标题={{article_title}}\n链接={{article_url}}\n选区={{selection}}",
                context =
                    LlmQuickMessageContext(
                        articleTitle = "OrigRead P6.4",
                        articleUrl = "https://example.com/p64",
                        selection = "selected paragraph",
                    ),
            )

        assertTrue(result.ready)
        assertEquals(
            "标题=OrigRead P6.4\n链接=https://example.com/p64\n选区=selected paragraph",
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

    @Test
    fun `legacy builtins are recognized across supported app languages`() {
        val explain = LlmQuickMessageBuiltin.EXPLAIN
        val candidates =
            mapOf(
                explain to
                    setOf(
                        LlmQuickMessageText(
                            title = "Explain it",
                            content = "Explain the hardest parts of this article in clearer, more direct language.",
                        ),
                        LlmQuickMessageText(
                            title = "解释难点",
                            content = "请解释这篇文章最难理解的部分，用更直白的方式说明。",
                        ),
                        LlmQuickMessageText(
                            title = "解釋難點",
                            content = "請用更直接的方式解釋這篇文章最難理解的部分。",
                        ),
                    )
            )

        assertEquals(
            explain,
            inferLegacyQuickMessageBuiltin(
                id = explain.id,
                title = "Explain it",
                content = "Explain the hardest parts of this article in clearer, more direct language.",
                localizedCandidates = candidates,
            ),
        )
        assertEquals(
            explain,
            inferLegacyQuickMessageBuiltin(
                id = explain.id,
                title = "解释难点",
                content = "请解释这篇文章最难理解的部分，用更直白的方式说明。",
                localizedCandidates = candidates,
            ),
        )
        assertEquals(
            explain,
            inferLegacyQuickMessageBuiltin(
                id = explain.id,
                title = "解釋難點",
                content = "請用更直接的方式解釋這篇文章最難理解的部分。",
                localizedCandidates = candidates,
            ),
        )
    }

    @Test
    fun `edited legacy builtin is preserved as custom instead of being relocalized`() {
        val explain = LlmQuickMessageBuiltin.EXPLAIN
        val candidates =
            mapOf(
                explain to
                    setOf(
                        LlmQuickMessageText(
                            title = "解释难点",
                            content = "请解释这篇文章最难理解的部分，用更直白的方式说明。",
                        )
                    )
            )

        assertNull(
            inferLegacyQuickMessageBuiltin(
                id = explain.id,
                title = "解释难点（我的版本）",
                content = "请用我自己的方式解释。",
                localizedCandidates = candidates,
            )
        )
    }

    @Test
    fun `editing builtin converts it to custom while preserving identity and state`() {
        val original =
            LlmQuickMessage(
                id = LlmQuickMessageBuiltin.EVIDENCE.id,
                title = "",
                content = "",
                enabled = false,
                order = 7,
                builtin = LlmQuickMessageBuiltin.EVIDENCE,
            )

        val customized =
            customizeQuickMessage(
                message = original,
                title = "  我的证据检查  ",
                content = "  只检查原文明确给出的数据。  ",
            )

        assertEquals(original.id, customized.id)
        assertEquals(false, customized.enabled)
        assertEquals(7, customized.order)
        assertEquals("我的证据检查", customized.title)
        assertEquals("只检查原文明确给出的数据。", customized.content)
        assertNull(customized.builtin)
    }
}
