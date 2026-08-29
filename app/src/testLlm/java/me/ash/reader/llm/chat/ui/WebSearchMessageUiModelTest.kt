package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LlmChatRole
import me.ash.reader.llm.chat.data.LlmContextRefEntity
import me.ash.reader.llm.chat.data.LlmMessageEntity
import me.ash.reader.llm.chat.data.LlmMessageStatus
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.search.WebSearchRequestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchMessageUiModelTest {
    @Test
    fun `triggered streaming projects exact frozen search plan`() {
        val model =
            projectWebSearchMessage(
                message =
                    assistantMessage(
                        status = LlmMessageStatus.STREAMING,
                        webSearchStatus = WebSearchRequestStatus.TRIGGERED,
                        query = "Project Valhalla — latest updates",
                        provider = "Exa",
                    ),
                contextRefs = emptyList(),
            )

        requireNotNull(model)
        assertEquals(WebSearchActivityUiState.SEARCHING, model.state)
        assertEquals("Project Valhalla — latest updates", model.query)
        assertEquals("Exa", model.providerName)
        assertEquals(0, model.resultCount)
        assertFalse(model.canShowResults)
        assertEquals(WebSearchMessageErrorState.NONE, model.errorState)
    }

    @Test
    fun `success counts only current assistant web search refs`() {
        val message =
            assistantMessage(
                webSearchStatus = WebSearchRequestStatus.SUCCESS,
                query = "OrigRead latest",
                provider = "Exa",
            )
        val refs =
            listOf(
                contextRef(
                    assistantMessageId = message.id,
                    contextId = "search-1",
                    type = LlmContextType.WEB_SEARCH_RESULT,
                    title = "OpenAI result",
                    sourceId = "https://www.openai.com/news/a",
                    sourceUrl = "https://www.openai.com/news/a",
                ),
                contextRef(
                    assistantMessageId = message.id,
                    contextId = "search-2",
                    type = LlmContextType.WEB_SEARCH_RESULT,
                    title = "Android result",
                    sourceId = "https://developer.android.com/topic",
                    sourceUrl = "https://developer.android.com/topic",
                ),
                contextRef(
                    assistantMessageId = message.id,
                    contextId = "article",
                    type = LlmContextType.ARTICLE,
                    sourceId = "https://ignored.example/article",
                    sourceUrl = "https://ignored.example/article",
                ),
                contextRef(
                    assistantMessageId = "other-assistant",
                    contextId = "other-search",
                    type = LlmContextType.WEB_SEARCH_RESULT,
                    sourceId = "https://other.example/result",
                    sourceUrl = "https://other.example/result",
                ),
            )

        val model = requireNotNull(projectWebSearchMessage(message, refs))

        assertEquals(WebSearchActivityUiState.SUCCESS, model.state)
        assertEquals(2, model.resultCount)
        assertEquals(listOf("openai.com", "developer.android.com"), model.sourceLabels)
        assertTrue(model.canShowResults)
    }

    @Test
    fun `success source labels follow the same frozen priority order as result detail`() {
        val message =
            assistantMessage(
                webSearchStatus = WebSearchRequestStatus.SUCCESS,
                query = "OrigRead latest",
                provider = "Keenable",
            )
        val refs =
            listOf(
                contextRef(
                    assistantMessageId = message.id,
                    contextId = "third",
                    type = LlmContextType.WEB_SEARCH_RESULT,
                    sourceUrl = "https://third.example/result",
                ).copy(priority = 108, createdAt = 3L),
                contextRef(
                    assistantMessageId = message.id,
                    contextId = "first",
                    type = LlmContextType.WEB_SEARCH_RESULT,
                    sourceUrl = "https://first.example/result",
                ).copy(priority = 110, createdAt = 1L),
                contextRef(
                    assistantMessageId = message.id,
                    contextId = "fourth",
                    type = LlmContextType.WEB_SEARCH_RESULT,
                    sourceUrl = "https://fourth.example/result",
                ).copy(priority = 107, createdAt = 4L),
                contextRef(
                    assistantMessageId = message.id,
                    contextId = "second",
                    type = LlmContextType.WEB_SEARCH_RESULT,
                    sourceUrl = "https://second.example/result",
                ).copy(priority = 109, createdAt = 2L),
            )

        val card = requireNotNull(projectWebSearchMessage(message, refs))
        val detail = projectWebSearchResults(message.id, refs)

        assertEquals(listOf("first.example", "second.example", "third.example"), card.sourceLabels)
        assertEquals(
            detail.take(3).mapNotNull(WebSearchResultUiModel::domain),
            card.sourceLabels,
        )
    }

    @Test
    fun `success with no search refs exposes zero results`() {
        val message =
            assistantMessage(
                webSearchStatus = WebSearchRequestStatus.SUCCESS,
                query = "something rare",
                provider = "Tavily",
            )
        val model =
            requireNotNull(
                projectWebSearchMessage(
                    message,
                    listOf(contextRef(message.id, "article", LlmContextType.ARTICLE)),
                )
            )

        assertEquals(0, model.resultCount)
        assertTrue(model.sourceLabels.isEmpty())
        assertFalse(model.canShowResults)
    }

    @Test
    fun `old success history keeps null query instead of guessing`() {
        val message =
            assistantMessage(
                webSearchStatus = WebSearchRequestStatus.SUCCESS,
                query = null,
                provider = "Exa",
            )
        val model =
            requireNotNull(
                projectWebSearchMessage(
                    message,
                    listOf(
                        contextRef(
                            assistantMessageId = message.id,
                            contextId = "legacy-search",
                            type = LlmContextType.WEB_SEARCH_RESULT,
                            sourceUrl = "https://example.com/legacy",
                        )
                    ),
                )
            )

        assertNull(model.query)
        assertEquals(WebSearchActivityUiState.SUCCESS, model.state)
        assertTrue(model.canShowResults)
    }

    @Test
    fun `auto fallback projects weak failure state`() {
        val model =
            requireNotNull(
                projectWebSearchMessage(
                    assistantMessage(
                        webSearchStatus = WebSearchRequestStatus.FAILED_FALLBACK,
                        query = "latest news",
                        provider = "Brave",
                    ),
                    emptyList(),
                )
            )

        assertEquals(WebSearchActivityUiState.FAILED_FALLBACK, model.state)
        assertEquals(WebSearchMessageErrorState.AUTO_FALLBACK, model.errorState)
        assertEquals("latest news", model.query)
        assertEquals("Brave", model.providerName)
        assertEquals(0, model.resultCount)
        assertFalse(model.canShowResults)
    }

    @Test
    fun `force failure keeps search card when assistant is error`() {
        val model =
            requireNotNull(
                projectWebSearchMessage(
                    assistantMessage(
                        status = LlmMessageStatus.ERROR,
                        webSearchStatus = WebSearchRequestStatus.FAILED_REQUIRED,
                        query = "force search",
                        provider = "Keenable",
                        webSearchError = "Keenable request failed",
                    ),
                    emptyList(),
                )
            )

        assertEquals(WebSearchActivityUiState.FORCE_FAILURE, model.state)
        assertEquals(WebSearchMessageErrorState.FORCE_FAILURE, model.errorState)
        assertEquals("force search", model.query)
        assertEquals("Keenable", model.providerName)
        assertEquals(0, model.resultCount)
        assertFalse(model.canShowResults)
        assertEquals("Keenable request failed", model.errorMessage)
    }

    @Test
    fun `cancelled and legacy triggered stopped never project searching`() {
        val cancelled =
            requireNotNull(
                projectWebSearchMessage(
                    assistantMessage(
                        status = LlmMessageStatus.STOPPED,
                        webSearchStatus = WebSearchRequestStatus.CANCELLED,
                        query = "latest release",
                        provider = "Exa",
                    ),
                    emptyList(),
                )
            )
        val legacyStopped =
            requireNotNull(
                projectWebSearchMessage(
                    assistantMessage(
                        status = LlmMessageStatus.STOPPED,
                        webSearchStatus = WebSearchRequestStatus.TRIGGERED,
                        query = "old interrupted search",
                        provider = "Tavily",
                    ),
                    emptyList(),
                )
            )

        assertEquals(WebSearchActivityUiState.CANCELLED, cancelled.state)
        assertEquals(WebSearchActivityUiState.CANCELLED, legacyStopped.state)
        assertEquals(WebSearchMessageErrorState.NONE, cancelled.errorState)
    }

    @Test
    fun `not needed and non assistant messages do not project search card`() {
        assertNull(
            projectWebSearchMessage(
                assistantMessage(webSearchStatus = WebSearchRequestStatus.NOT_NEEDED),
                emptyList(),
            )
        )
        assertNull(
            projectWebSearchMessage(
                assistantMessage(webSearchStatus = WebSearchRequestStatus.SUCCESS).copy(
                    role = LlmChatRole.USER
                ),
                emptyList(),
            )
        )
    }

    @Test
    fun `unsafe search source can still show frozen detail and falls back to title initial`() {
        val message = assistantMessage(webSearchStatus = WebSearchRequestStatus.SUCCESS)
        val model =
            requireNotNull(
                projectWebSearchMessage(
                    message,
                    listOf(
                        contextRef(
                            assistantMessageId = message.id,
                            contextId = "unsafe-1",
                            type = LlmContextType.WEB_SEARCH_RESULT,
                            title = "Example result",
                            sourceId = "javascript:alert(1)",
                        ),
                        contextRef(
                            assistantMessageId = message.id,
                            contextId = "unsafe-2",
                            type = LlmContextType.WEB_SEARCH_RESULT,
                            title = "File result",
                            sourceId = "file:///tmp/result",
                        ),
                    ),
                )
            )

        assertTrue(model.canShowResults)
        assertEquals(listOf("E", "F"), model.sourceLabels)
    }

    private fun assistantMessage(
        status: LlmMessageStatus = LlmMessageStatus.COMPLETE,
        webSearchStatus: WebSearchRequestStatus? = null,
        query: String? = null,
        provider: String? = null,
        webSearchError: String? = null,
    ): LlmMessageEntity =
        LlmMessageEntity(
            id = "assistant-1",
            conversationId = "conversation-1",
            role = LlmChatRole.ASSISTANT,
            content = "response",
            status = status,
            webSearchStatus = webSearchStatus,
            webSearchQuery = query,
            webSearchProviderName = provider,
            webSearchErrorMessage = webSearchError,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private fun contextRef(
        assistantMessageId: String,
        contextId: String,
        type: LlmContextType,
        title: String? = null,
        sourceId: String? = null,
        sourceUrl: String? = null,
    ): LlmContextRefEntity =
        LlmContextRefEntity(
            id = "ref-$assistantMessageId-$contextId",
            conversationId = "conversation-1",
            assistantMessageId = assistantMessageId,
            contextId = contextId,
            type = type,
            title = title,
            sourceId = sourceId,
            sourceUrl = sourceUrl,
            contentSnapshot = "snapshot",
            contentSha256 = "hash",
            priority = 100,
            includedInPrompt = false,
            truncatedInPrompt = false,
            createdAt = 2L,
        )
}
