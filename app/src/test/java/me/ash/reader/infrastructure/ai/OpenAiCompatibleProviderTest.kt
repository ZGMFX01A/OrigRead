package me.ash.reader.infrastructure.ai

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAiCompatibleProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAiCompatibleProvider(AiHttpClient())
    }

    @Test
    fun `preserves explicit reasoning content separately from final answer`() {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"reasoning_content":"先分析文章结构，再归纳结论。","content":"最终摘要"}}]}""",
            ),
        )

        val result =
            provider.completeDetailed(
                systemPrompt = "system",
                userPrompt = "article",
                config = AiRuntimeConfig(server.url("/v1").toString(), "reasoning-model", ""),
            )

        assertEquals("最终摘要", result.content)
        assertEquals("先分析文章结构，再归纳结论。", result.reasoning)
    }

    @Test
    fun `splits think tags from compatible model output`() {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"<think>内部返回的显式思考</think>\n\n最终答案"}}]}""",
            ),
        )

        val result =
            provider.completeDetailed(
                systemPrompt = "system",
                userPrompt = "article",
                config = AiRuntimeConfig(server.url("/v1").toString(), "think-model", ""),
            )

        assertEquals("最终答案", result.content)
        assertEquals("内部返回的显式思考", result.reasoning)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `resolves base url and full endpoint`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            resolveChatCompletionsEndpoint("https://api.example.com/v1"),
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            resolveChatCompletionsEndpoint("https://api.example.com"),
        )
        assertEquals(
            "https://api.example.com/custom/chat/completions?token=abc",
            resolveChatCompletionsEndpoint(
                "https://api.example.com/custom/chat/completions?token=abc"
            ),
        )
        assertEquals(
            "https://api.example.com/v1/models",
            resolveModelsEndpoint("https://api.example.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.example.com/models",
            resolveModelsEndpoint("https://api.example.com/chat/completions"),
        )
        assertEquals(
            "https://api.example.com/custom/models?token=abc",
            resolveModelsEndpoint(
                "https://api.example.com/custom/chat/completions?token=abc"
            ),
        )
    }

    @Test
    fun `normalizes duplicated model name`() {
        assertEquals(
            "deepseek-v4-flash",
            normalizeAiModelName(
                "deepseek-v4-flash\ndeepseek-v4-flash\ndeepseek-v4-flash"
            ),
        )
    }

    @Test
    fun `fetches model list with bearer key`() {
        server.enqueue(
            MockResponse().setBody(
                """{"object":"list","data":[{"id":"deepseek-v4-pro"},{"id":"deepseek-v4-flash"}]}"""
            )
        )

        val models =
            provider.listModels(
                AiRuntimeConfig(
                    endpoint = server.url("/chat/completions").toString(),
                    model = "",
                    apiKey = "secret",
                )
            )

        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), models)
        val request = server.takeRequest()
        assertEquals("/models", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test
    fun `sends chat completion request with bearer key`() {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"摘要结果"}}]}"""
            )
        )

        val result =
            provider.complete(
                systemPrompt = "system",
                userPrompt = "article",
                config =
                    AiRuntimeConfig(
                        endpoint = server.url("/v1").toString(),
                        model = "test-model",
                        apiKey = "secret",
                    ),
            )

        assertEquals("摘要结果", result)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("test-model", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun `supports keyless service and array content response`() {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]}}]}"""
            )
        )

        val result =
            provider.complete(
                systemPrompt = "system",
                userPrompt = "article",
                config =
                    AiRuntimeConfig(
                        endpoint = server.url("/chat/completions").toString(),
                        model = "local-model",
                        apiKey = "",
                    ),
            )

        assertEquals("第一段\n第二段", result)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `maps authentication error with response detail`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"invalid api key"}}"""),
        )

        try {
            provider.complete(
                systemPrompt = "system",
                userPrompt = "article",
                config =
                    AiRuntimeConfig(
                        endpoint = server.url("/v1").toString(),
                        model = "test-model",
                        apiKey = "bad",
                    ),
            )
            fail("Expected AiException")
        } catch (error: AiException) {
            assertEquals(AiErrorCode.AUTHENTICATION, error.code)
            assertTrue(error.message.orEmpty().contains("invalid api key"))
        }
    }
}

