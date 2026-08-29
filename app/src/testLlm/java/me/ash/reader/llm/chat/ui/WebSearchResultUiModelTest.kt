package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LlmContextRefEntity
import me.ash.reader.llm.runtime.LlmContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchResultUiModelTest {
    @Test
    fun `projects only current assistant search refs in frozen priority order`() {
        val refs =
            listOf(
                searchRef("assistant-1", "three", priority = 98, included = false),
                searchRef("assistant-1", "one", priority = 100, included = true),
                searchRef(
                    "assistant-1",
                    "two",
                    priority = 99,
                    included = true,
                    truncated = true,
                ),
                searchRef("assistant-2", "other", priority = 999, included = true),
                searchRef("assistant-1", "article", priority = 999, included = true).copy(
                    type = LlmContextType.ARTICLE
                ),
            )

        val models = projectWebSearchResults("assistant-1", refs)

        assertEquals(listOf("one", "two", "three"), models.map { it.id.removePrefix("ref-") })
        assertEquals(
            listOf(
                WebSearchResultUsageState.USED,
                WebSearchResultUsageState.USED_TRUNCATED,
                WebSearchResultUsageState.OMITTED,
            ),
            models.map(WebSearchResultUiModel::usageState),
        )
    }

    @Test
    fun `preview uses original frozen snapshot instead of prompt snapshot`() {
        val model =
            projectWebSearchResults(
                    "assistant-1",
                    listOf(
                        searchRef(
                            assistantId = "assistant-1",
                            id = "result",
                            content = "Original search material with full context",
                            promptContent = "Truncated prompt text",
                            included = true,
                            truncated = true,
                        )
                    ),
                )
                .single()

        assertEquals("Original search material with full context", model.preview)
        assertEquals(WebSearchResultUsageState.USED_TRUNCATED, model.usageState)
    }

    @Test
    fun `safe URL exposes normalized domain and title fallback`() {
        val model =
            projectWebSearchResults(
                    "assistant-1",
                    listOf(
                        searchRef(
                            assistantId = "assistant-1",
                            id = "result",
                            title = "   ",
                            sourceId = "https://www.example.com/path?q=1",
                            sourceUrl = "https://www.example.com/path?q=1",
                        )
                    ),
                )
                .single()

        assertEquals("example.com", model.domain)
        assertEquals("example.com", model.title)
        assertEquals("https://www.example.com/path?q=1", model.sourceUrl)
    }

    @Test
    fun `unsafe source remains visible but cannot be opened`() {
        val model =
            projectWebSearchResults(
                    "assistant-1",
                    listOf(
                        searchRef(
                            assistantId = "assistant-1",
                            id = "unsafe",
                            title = null,
                            sourceId = "javascript:alert(1)",
                            sourceUrl = null,
                        )
                    ),
                )
                .single()

        assertNull(model.sourceUrl)
        assertNull(model.domain)
        assertNull(model.title)
    }

    @Test
    fun `preview truncation does not mutate persisted snapshot`() {
        val original = "x".repeat(2_000)
        val ref =
            searchRef(
                assistantId = "assistant-1",
                id = "long",
                content = original,
            )

        val model = projectWebSearchResults("assistant-1", listOf(ref)).single()

        assertTrue(model.preview.orEmpty().length < original.length)
        assertEquals(original, ref.contentSnapshot)
    }

    private fun searchRef(
        assistantId: String,
        id: String,
        title: String? = "Title $id",
        sourceId: String? = "https://example.com/$id",
        sourceUrl: String? = sourceId,
        content: String = "snapshot $id",
        promptContent: String? = content,
        priority: Int = 100,
        included: Boolean = true,
        truncated: Boolean = false,
    ): LlmContextRefEntity =
        LlmContextRefEntity(
            id = "ref-$id",
            conversationId = "conversation-1",
            assistantMessageId = assistantId,
            contextId = "search-$id",
            type = LlmContextType.WEB_SEARCH_RESULT,
            title = title,
            sourceId = sourceId,
            sourceUrl = sourceUrl,
            contentSnapshot = content,
            promptContentSnapshot = promptContent,
            contentSha256 = "hash-$id",
            priority = priority,
            includedInPrompt = included,
            truncatedInPrompt = truncated,
            createdAt = priority.toLong(),
        )
}
