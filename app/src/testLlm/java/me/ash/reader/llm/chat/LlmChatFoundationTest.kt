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
import me.ash.reader.llm.chat.runtime.LlmChatTransport
import me.ash.reader.llm.chat.runtime.parseNonStreamingPayload
import me.ash.reader.llm.chat.runtime.parseStreamPayload
import me.ash.reader.llm.chat.data.deriveConversationTitle
import me.ash.reader.llm.chat.ui.buildArticleContextItems
import me.ash.reader.llm.runtime.ComposedLlmContext
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.runtime.LlmExecutionPlan
import me.ash.reader.llm.runtime.LlmReasoningEffort
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
    fun `article assistant builds stable article summary and translation contexts`() {
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
                )
            )

        assertEquals(
            listOf(
                LlmContextType.ARTICLE_SUMMARY,
                LlmContextType.ARTICLE_TRANSLATION,
                LlmContextType.ARTICLE,
            ),
            items.map { it.type },
        )
        assertEquals(
            listOf(
                "article:article-1:summary",
                "article:article-1:translation",
                "article:article-1:original",
            ),
            items.map { it.id },
        )
        assertEquals("Translated title", items[1].title)
        assertTrue(items[0].priority > items[1].priority)
        assertTrue(items[1].priority > items[2].priority)
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
