package me.ash.reader.llm.chat

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.ai.AiRuntimeConfig
import me.ash.reader.llm.chat.data.LlmChatRole
import me.ash.reader.llm.chat.runtime.LlmChatRequestMessage
import me.ash.reader.llm.chat.runtime.LlmChatRequestToolCall
import me.ash.reader.llm.chat.runtime.LlmChatTransport
import me.ash.reader.llm.chat.runtime.parseNonStreamingPayload
import me.ash.reader.llm.chat.runtime.parseStreamPayload
import me.ash.reader.llm.chat.data.deriveConversationTitle
import me.ash.reader.llm.chat.data.buildContextRefEntities
import me.ash.reader.llm.chat.data.buildToolResultContextRefEntities
import me.ash.reader.llm.chat.data.LlmToolCallEntity
import me.ash.reader.llm.chat.data.LlmToolCallStatus
import me.ash.reader.llm.chat.ui.buildArticleContextItems
import me.ash.reader.llm.chat.ui.shouldExposeManualToolFallback
import me.ash.reader.llm.runtime.ComposedLlmContext
import me.ash.reader.llm.runtime.LlmContextComposer
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextPolicy
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.runtime.LlmExecutionPlan
import me.ash.reader.llm.runtime.LlmReasoningEffort
import me.ash.reader.llm.runtime.LlmToolDescriptor
import me.ash.reader.llm.runtime.LlmToolSource
import me.ash.reader.llm.runtime.ModelCapability
import me.ash.reader.llm.runtime.ReasoningParameterStyle
import me.ash.reader.ui.page.home.reading.ArticleAssistantContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmChatFoundationTest {
    @Test
    fun `article assistant builds stable selection summary translation and article contexts`() {
        val items =
            buildArticleContextItems(
                ArticleAssistantContext(
                    articleId = "article-1",
                    title = "Original title",
                    link = "https://example.com/article-1",
                    originalContent = "original body",
                    summary = "summary body",
                    translatedTitle = "Translated title",
                    translatedContent = "translated body",
                    selectedText = "selected excerpt",
                )
            )

        assertEquals(
            listOf(
                LlmContextType.SELECTED_TEXT,
                LlmContextType.ARTICLE_SUMMARY,
                LlmContextType.ARTICLE_TRANSLATION,
                LlmContextType.ARTICLE,
            ),
            items.map { it.type },
        )
        assertEquals(
            listOf(
                "article:article-1:selection",
                "article:article-1:summary",
                "article:article-1:translation",
                "article:article-1:original",
            ),
            items.map { it.id },
        )
        assertEquals("selected excerpt", items[0].content)
        assertEquals("Translated title", items[2].title)
        assertTrue(items[0].priority > items[1].priority)
        assertTrue(items[1].priority > items[2].priority)
        assertTrue(items[2].priority > items[3].priority)
    }

    @Test
    fun `article assistant omits blank selected text context`() {
        val items =
            buildArticleContextItems(
                ArticleAssistantContext(
                    articleId = "article-1",
                    title = "Original title",
                    link = "https://example.com/article-1",
                    originalContent = "original body",
                    selectedText = "  \n\t  ",
                )
            )

        assertEquals(listOf(LlmContextType.ARTICLE), items.map { it.type })
    }

    @Test
    fun `article assistant selection is rendered into prompt and frozen as context ref`() {
        val items =
            buildArticleContextItems(
                ArticleAssistantContext(
                    articleId = "article-1",
                    title = "Original title",
                    link = "https://example.com/article-1",
                    originalContent = "original body",
                    selectedText = "selected excerpt",
                )
            )
        val composed =
            LlmContextComposer().compose(
                items = items,
                policy = LlmContextPolicy(maxTokens = 512),
            )
        val refs =
            buildContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant",
                candidates = items,
                composed = composed,
                createdAt = 123L,
            )

        val selectionRef = refs.single { it.type == LlmContextType.SELECTED_TEXT }
        assertEquals("article:article-1:selection", selectionRef.contextId)
        assertEquals("selected excerpt", selectionRef.contentSnapshot)
        assertEquals("selected excerpt", selectionRef.promptContentSnapshot)
        assertEquals("https://example.com/article-1", selectionRef.sourceUrl)
        assertTrue(selectionRef.includedInPrompt)
        assertTrue(composed.text.contains("selected excerpt"))
    }

    @Test
    fun `conversation title normalizes whitespace and limits local title length`() {
        val title = deriveConversationTitle("  这是   一个\n用于测试的会话标题  " + "A".repeat(80))

        assertTrue(title.startsWith("这是 一个 用于测试的会话标题"))
        assertTrue(title.length <= 48)
    }

    @Test
    fun `blank conversation title falls back locally`() {
        assertEquals("New chat", deriveConversationTitle(" \n\t "))
    }

    @Test
    fun `stream payload parses content delta`() {
        val delta =
            parseStreamPayload(
                """{"choices":[{"delta":{"content":"hello"}}]}"""
            )

        assertEquals("hello", delta?.content)
        assertEquals("", delta?.reasoning)
    }

    @Test
    fun `stream payload keeps explicit reasoning separate from answer`() {
        val delta =
            parseStreamPayload(
                """{"choices":[{"delta":{"reasoning_content":"reason","content":"answer"}}]}"""
            )

        assertEquals("answer", delta?.content)
        assertEquals("reason", delta?.reasoning)
    }

    @Test
    fun `stream payload without choice content is ignored`() {
        assertNull(parseStreamPayload("""{"choices":[{"delta":{}}]}"""))
    }

    @Test
    fun `stream usage only payload keeps provider token counts`() {
        val delta =
            parseStreamPayload(
                """{"choices":[],"usage":{"prompt_tokens":372,"completion_tokens":3000}}"""
            )

        assertEquals(372, delta?.promptTokens)
        assertEquals(3000, delta?.completionTokens)
        assertEquals("", delta?.content)
    }

    @Test
    fun `non streaming fallback parses compatible response`() {
        val delta =
            parseNonStreamingPayload(
                """{"choices":[{"message":{"content":"final","reasoning":"visible reasoning"}}],"usage":{"input_tokens":120,"output_tokens":80}}"""
            )

        assertEquals("final", delta.content)
        assertEquals("visible reasoning", delta.reasoning)
        assertEquals(120, delta.promptTokens)
        assertEquals(80, delta.completionTokens)
    }

    @Test
    fun `stream payload parses incremental tool call`() {
        val delta =
            parseStreamPayload(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\"q\":"}}]}}]}"""
            )

        assertEquals(1, delta?.toolCalls?.size)
        assertEquals(0, delta?.toolCalls?.single()?.index)
        assertEquals("call_1", delta?.toolCalls?.single()?.id)
        assertEquals("lookup", delta?.toolCalls?.single()?.name)
        assertEquals("{\"q\":", delta?.toolCalls?.single()?.argumentsDelta)
    }

    @Test
    fun `non streaming response parses complete tool call`() {
        val delta =
            parseNonStreamingPayload(
                """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_2","type":"function","function":{"name":"search","arguments":"{\"query\":\"news\"}"}}]}}]}"""
            )

        assertEquals("", delta.content)
        assertEquals("call_2", delta.toolCalls.single().id)
        assertEquals("search", delta.toolCalls.single().name)
        assertEquals("{\"query\":\"news\"}", delta.toolCalls.single().argumentsDelta)
    }

    @Test
    fun `tool calling request sends function schema assistant call and tool result`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"done"}}]}""")
        )
        server.start()
        try {
            val tool =
                LlmToolDescriptor(
                    id = "mcp:server:search",
                    name = "search",
                    description = "Search documents",
                    source = LlmToolSource.MCP,
                    sourceId = "server",
                    inputSchemaJson = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}",
                )
            val plan =
                LlmExecutionPlan(
                    providerId = "test-provider",
                    providerName = "Test",
                    runtimeConfig = AiRuntimeConfig(server.url("/v1").toString(), "test-model", ""),
                    capability = ModelCapability(supportsStreaming = false, supportsToolCalling = true),
                    reasoningParameter = null,
                    tools = listOf(tool),
                    automaticToolCalling = true,
                    context = ComposedLlmContext("", emptyList(), emptyList(), false),
                    skillId = null,
                )

            LlmChatTransport(AiHttpClient())
                .stream(
                    plan,
                    listOf(
                        LlmChatRequestMessage(LlmChatRole.USER, "find it"),
                        LlmChatRequestMessage(
                            role = LlmChatRole.ASSISTANT,
                            content = "",
                            toolCalls =
                                listOf(
                                    LlmChatRequestToolCall(
                                        id = "call_1",
                                        name = "or_fake_search",
                                        argumentsJson = "{\"q\":\"x\"}",
                                    )
                                ),
                        ),
                        LlmChatRequestMessage(
                            role = LlmChatRole.TOOL,
                            content = "result",
                            toolCallId = "call_1",
                        ),
                    ),
                )
                .toList()

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"tools\""))
            assertTrue(body.contains("\"parameters\""))
            assertTrue(body.contains("\"tool_calls\""))
            assertTrue(body.contains("\"role\":\"tool\""))
            assertTrue(body.contains("\"tool_call_id\":\"call_1\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `manual tool fallback is exposed only when tools exist but automatic calling is unavailable`() {
        val tool =
            LlmToolDescriptor(
                id = "mcp:server:search",
                name = "search",
                description = "Search documents",
                source = LlmToolSource.MCP,
                sourceId = "server",
            )
        val basePlan =
            LlmExecutionPlan(
                providerId = "test-provider",
                providerName = "Test",
                runtimeConfig = AiRuntimeConfig("https://example.com/v1", "test-model", ""),
                capability = ModelCapability(supportsToolCalling = false),
                reasoningParameter = null,
                tools = listOf(tool),
                automaticToolCalling = false,
                context = ComposedLlmContext("", emptyList(), emptyList(), false),
                skillId = null,
            )

        assertTrue(shouldExposeManualToolFallback(basePlan))
        assertFalse(shouldExposeManualToolFallback(basePlan.copy(automaticToolCalling = true)))
        assertFalse(shouldExposeManualToolFallback(basePlan.copy(tools = emptyList())))
    }

    @Test
    fun `context refs preserve full source snapshot separately from rendered prompt fragment`() {
        val article =
            LlmContextItem(
                id = "article:1:original",
                type = LlmContextType.ARTICLE,
                content = "A".repeat(200),
                title = "Article",
                sourceId = "https://example.com/article",
                priority = 100,
            )
        val tool =
            LlmContextItem(
                id = "manual-tool:1",
                type = LlmContextType.TOOL_RESULT,
                content = "tool-result",
                title = "Tool",
                sourceId = "mcp-server-id",
                priority = 10,
            )
        val composed =
            LlmContextComposer().compose(
                items = listOf(article, tool),
                policy = LlmContextPolicy(maxTokens = 80),
            )

        val refs =
            buildContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant",
                candidates = listOf(article, tool),
                composed = composed,
                createdAt = 123L,
            )
        val articleRef = refs.single { it.contextId == article.id }
        val toolRef = refs.single { it.contextId == tool.id }

        assertEquals(article.content, articleRef.contentSnapshot)
        assertTrue(articleRef.promptContentSnapshot.orEmpty().length < article.content.length)
        assertTrue(articleRef.includedInPrompt)
        assertTrue(articleRef.truncatedInPrompt)
        assertEquals(article.sourceId, articleRef.sourceUrl)
        assertEquals(64, articleRef.contentSha256.length)
        assertFalse(toolRef.includedInPrompt)
        assertNull(toolRef.promptContentSnapshot)
        assertNull(toolRef.sourceUrl)
    }

    @Test
    fun `tool result context refs include only finalized provider history results`() {
        val now = 123L
        val calls =
            listOf(
                LlmToolCallEntity(
                    id = "complete-local",
                    conversationId = "conversation",
                    assistantMessageId = "previous-assistant",
                    providerCallId = "call-1",
                    toolId = "mcp:deepwiki:read",
                    apiName = "read_wiki",
                    argumentsJson = "{}",
                    status = LlmToolCallStatus.COMPLETE,
                    resultContent = "wiki result",
                    createdAt = now,
                    updatedAt = now,
                ),
                LlmToolCallEntity(
                    id = "pending-local",
                    conversationId = "conversation",
                    assistantMessageId = "previous-assistant",
                    providerCallId = "call-2",
                    toolId = "mcp:deepwiki:write",
                    apiName = "write_wiki",
                    argumentsJson = "{}",
                    status = LlmToolCallStatus.PENDING_APPROVAL,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

        val refs =
            buildToolResultContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "next-assistant",
                toolCalls = calls,
                createdAt = now,
            )

        assertEquals(1, refs.size)
        val ref = refs.single()
        assertEquals("tool-result:complete-local", ref.contextId)
        assertEquals(LlmContextType.TOOL_RESULT, ref.type)
        assertEquals("wiki result", ref.contentSnapshot)
        assertEquals("wiki result", ref.promptContentSnapshot)
        assertTrue(ref.includedInPrompt)
        assertFalse(ref.truncatedInPrompt)
        assertEquals("mcp:deepwiki:read", ref.sourceId)
    }

    @Test
    fun `chat transport streams sse deltas and sends conversation history`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()

        try {
            val plan =
                LlmExecutionPlan(
                    providerId = "test-provider",
                    providerName = "Test",
                    runtimeConfig =
                        AiRuntimeConfig(
                            endpoint = server.url("/v1").toString(),
                            model = "test-model",
                            apiKey = "secret",
                        ),
                    capability = ModelCapability(),
                    reasoningParameter = null,
                    tools = emptyList(),
                    automaticToolCalling = false,
                    context =
                        ComposedLlmContext(
                            text = "",
                            includedIds = emptyList(),
                            omittedIds = emptyList(),
                            truncated = false,
                        ),
                    skillId = null,
                )
            val deltas =
                LlmChatTransport(AiHttpClient())
                    .stream(
                        plan = plan,
                        messages =
                            listOf(
                                LlmChatRequestMessage(
                                    role = LlmChatRole.USER,
                                    content = "hello?",
                                )
                            ),
                    )
                    .toList()

            assertEquals("hello", deltas.joinToString("") { it.content })
            val request = server.takeRequest()
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("\"model\":\"test-model\""))
            assertTrue(body.contains("hello?"))
            assertFalse(body.contains("\"role\":\"system\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `reasoning model request omits temperature`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )
        server.start()

        try {
            val plan =
                LlmExecutionPlan(
                    providerId = "test-provider",
                    providerName = "Test",
                    runtimeConfig =
                        AiRuntimeConfig(
                            endpoint = server.url("/v1").toString(),
                            model = "reasoning-model",
                            apiKey = "",
                        ),
                    capability =
                        ModelCapability(
                            supportedReasoningEfforts = setOf(LlmReasoningEffort.HIGH),
                            reasoningParameterStyle = ReasoningParameterStyle.OPENAI_REASONING_EFFORT,
                        ),
                    reasoningParameter = null,
                    tools = emptyList(),
                    automaticToolCalling = false,
                    context =
                        ComposedLlmContext(
                            text = "",
                            includedIds = emptyList(),
                            omittedIds = emptyList(),
                            truncated = false,
                        ),
                    skillId = null,
                )

            LlmChatTransport(AiHttpClient())
                .stream(
                    plan = plan,
                    messages = listOf(LlmChatRequestMessage(LlmChatRole.USER, "test")),
                )
                .toList()

            val body = server.takeRequest().body.readUtf8()
            assertFalse(body.contains("\"temperature\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `non streaming capability sends stream false and parses json response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"plain response"}}]}""")
        )
        server.start()

        try {
            val plan =
                LlmExecutionPlan(
                    providerId = "test-provider",
                    providerName = "Test",
                    runtimeConfig =
                        AiRuntimeConfig(
                            endpoint = server.url("/v1").toString(),
                            model = "non-stream-model",
                            apiKey = "",
                        ),
                    capability = ModelCapability(supportsStreaming = false),
                    reasoningParameter = null,
                    tools = emptyList(),
                    automaticToolCalling = false,
                    context =
                        ComposedLlmContext(
                            text = "",
                            includedIds = emptyList(),
                            omittedIds = emptyList(),
                            truncated = false,
                        ),
                    skillId = null,
                )

            val deltas =
                LlmChatTransport(AiHttpClient())
                    .stream(
                        plan = plan,
                        messages = listOf(LlmChatRequestMessage(LlmChatRole.USER, "hello")),
                    )
                    .toList()

            assertEquals("plain response", deltas.joinToString("") { it.content })
            val request = server.takeRequest()
            assertEquals("application/json", request.getHeader("Accept"))
            assertTrue(request.body.readUtf8().contains("\"stream\":false"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `stream request cancels blocked okhttp call when coroutine is stopped`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()

        try {
            val plan =
                LlmExecutionPlan(
                    providerId = "test-provider",
                    providerName = "Test",
                    runtimeConfig =
                        AiRuntimeConfig(
                            endpoint = server.url("/v1").toString(),
                            model = "slow-model",
                            apiKey = "",
                        ),
                    capability = ModelCapability(supportsStreaming = true),
                    reasoningParameter = null,
                    tools = emptyList(),
                    automaticToolCalling = false,
                    context =
                        ComposedLlmContext(
                            text = "",
                            includedIds = emptyList(),
                            omittedIds = emptyList(),
                            truncated = false,
                        ),
                    skillId = null,
                )
            val job =
                launch(Dispatchers.IO) {
                    LlmChatTransport(AiHttpClient())
                        .stream(
                            plan = plan,
                            messages = listOf(LlmChatRequestMessage(LlmChatRole.USER, "stop me")),
                        )
                        .toList()
                }

            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            server.shutdown()
        }
    }
}
