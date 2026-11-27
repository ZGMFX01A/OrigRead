package me.ash.reader.llm.chat

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.infrastructure.ai.AiErrorCode
import me.ash.reader.infrastructure.ai.AiException
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.ai.AiRuntimeConfig
import me.ash.reader.llm.chat.data.LlmChatRole
import me.ash.reader.llm.chat.runtime.LlmChatRequestMessage
import me.ash.reader.llm.chat.runtime.LlmChatTransport
import me.ash.reader.llm.runtime.ComposedLlmContext
import me.ash.reader.llm.runtime.LlmExecutionPlan
import me.ash.reader.llm.runtime.ModelCapability
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LlmChatTransportReliabilityTest {

    @Test fun `reasoning whitespace deltas survive parsing`() {
        listOf(" ", "\n", "  \n  ").forEach { chunk ->
            val delta = org.json.JSONObject().put("reasoning_content", chunk).put("reasoning", "fallback")
            val payload = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject().put("delta", delta)))
            assertEquals(chunk, me.ash.reader.llm.chat.runtime.parseStreamPayload(payload.toString())!!.reasoning)
        }
    }

    private val messages = listOf(LlmChatRequestMessage(LlmChatRole.USER, "hello"))
    private fun event(content: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n"
    private fun plan(server: MockWebServer, streaming: Boolean = true) = LlmExecutionPlan(
        providerId = "test", providerName = "Test",
        runtimeConfig = AiRuntimeConfig(server.url("/v1").toString(), "test-model", ""),
        capability = ModelCapability(supportsStreaming = streaming), reasoningParameter = null,
        tools = emptyList(), automaticToolCalling = false,
        context = ComposedLlmContext("", emptyList(), emptyList(), false), skillId = null,
    )

    @Test fun `slow collector receives all 220 deltas and completes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody((0 until 220).joinToString("") { event(it.toString()) } + "data: [DONE]\n\n"))
            server.start()
            val received = mutableListOf<String>()
            withTimeout(10000) {
                LlmChatTransport(AiHttpClient()).stream(plan(server), messages).collect {
                    delay(5)
                    received += it.content
                }
            }
            assertEquals((0 until 220).map(Int::toString), received)
        }
    }
    @Test fun `collector cancellation releases blocked backpressure producer`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody((0 until 220).joinToString("") { event(it.toString()) } + "data: [DONE]\n\n"))
            server.start()
            val received = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val startedCall = CompletableDeferred<Call>()
            val dispatcher = okhttp3.Dispatcher().apply { idleCallback = Runnable { released.complete(Unit) } }
            val client = OkHttpClient.Builder().dispatcher(dispatcher).eventListener(object : EventListener() {
                override fun callStart(call: Call) { startedCall.complete(call) }
            }).build()
            val job = launch {
                var count = 0
                LlmChatTransport(AiHttpClient(client)).stream(plan(server), messages).collect {
                    if (++count == 5) received.complete(Unit)
                    delay(50)
                }
            }
            withTimeout(5000) { received.await() }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(startedCall.await().isCanceled())
            withTimeout(2000) { released.await() }
            assertEquals(0, dispatcher.runningCallsCount())
        }
    }
    @Test fun `non streaming call timeout remains a network error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS)
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
            server.start()
            val client = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build()
            try {
                withTimeout(5000) { LlmChatTransport(AiHttpClient(client)).stream(plan(server, false), messages).toList() }
                fail("timeout must fail")
            } catch (error: CancellationException) {
                throw AssertionError("timeout misclassified as cancellation", error)
            } catch (error: AiException) {
                assertEquals(AiErrorCode.NETWORK, error.code)
                assertEquals("AI 请求超时", error.message)
            }
        }
    }
    @Test fun `streaming outlives base call timeout and retains every chunk`() = runBlocking {
        MockWebServer().use { server ->
            val line = event("x")
            server.enqueue(MockResponse().setBody(line.repeat(8) + "data: [DONE]\n\n")
                .throttleBody(line.toByteArray().size.toLong(), 80, TimeUnit.MILLISECONDS))
            server.start()
            val base = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build()
            val http = AiHttpClient(base)
            assertEquals(200, http.client.callTimeoutMillis)
            assertEquals(0, http.streamingClient.callTimeoutMillis)
            assertSame(base.connectionPool, http.streamingClient.connectionPool)
            val start = System.nanoTime()
            val result = withTimeout(5000) { LlmChatTransport(http).stream(plan(server), messages).toList() }
            assertEquals(List(8) { "x" }, result.map { it.content })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > 450)
        }
    }
    @Test fun `active collector cancellation interrupts underlying request`() = runBlocking {
        MockWebServer().use { server ->
            val line = event("x")
            server.enqueue(MockResponse().setBody(line.repeat(1000)).throttleBody(line.length.toLong(), 100, TimeUnit.MILLISECONDS))
            server.start()
            val started = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: java.io.IOException) { failed.complete(Unit) }
            }).build()
            val job = launch {
                LlmChatTransport(AiHttpClient(client)).stream(plan(server), messages).collect { started.complete(Unit) }
            }
            withTimeout(5000) { started.await() }
            withTimeout(2000) { job.cancelAndJoin(); failed.await() }
            assertTrue(job.isCancelled)
        }
    }
}
