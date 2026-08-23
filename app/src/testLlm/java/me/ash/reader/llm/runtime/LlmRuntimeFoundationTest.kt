package me.ash.reader.llm.runtime

import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class LlmRuntimeFoundationTest {

    @Test
    fun `unknown compatible provider uses conservative capabilities`() {
        val capability =
            ModelCapabilityResolver().resolve(
                provider =
                    AiProviderProfile(
                        name = "自建服务",
                        endpoint = "https://example.com/v1",
                    ),
                model = "custom-model",
            )

        assertTrue(capability.supportsStreaming)
        assertFalse(capability.supportsToolCalling)
        assertTrue(capability.supportedReasoningEfforts.isEmpty())
        assertEquals(ReasoningParameterStyle.NONE, capability.reasoningParameterStyle)
    }

    @Test
    fun `provider display name does not impersonate official capability`() {
        val capability =
            ModelCapabilityResolver().resolve(
                provider =
                    AiProviderProfile(
                        name = "OpenAI Proxy",
                        endpoint = "https://proxy.example.com/v1",
                    ),
                model = "gpt-5-custom",
            )

        assertFalse(capability.supportsToolCalling)
        assertTrue(capability.supportedReasoningEfforts.isEmpty())
        assertEquals(ReasoningParameterStyle.NONE, capability.reasoningParameterStyle)
    }

    @Test
    fun `capability override only changes explicitly supplied fields`() {
        val resolver = ModelCapabilityResolver()
        val provider =
            AiProviderProfile(
                name = "自建服务",
                endpoint = "https://example.com/v1",
            )
        val capability =
            resolver.resolve(
                provider = provider,
                model = "custom-model",
                override =
                    ModelCapabilityOverride(
                        supportsToolCalling = true,
                        supportedReasoningEfforts = setOf(LlmReasoningEffort.HIGH),
                        reasoningParameterStyle = ReasoningParameterStyle.OPENAI_REASONING_EFFORT,
                    ),
            )

        assertTrue(capability.supportsStreaming)
        assertTrue(capability.supportsToolCalling)
        assertEquals(setOf(LlmReasoningEffort.HIGH), capability.supportedReasoningEfforts)
    }

    @Test
    fun `context composer keeps high priority context within budget`() {
        val result =
            LlmContextComposer().compose(
                items =
                    listOf(
                        LlmContextItem(
                            id = "article",
                            type = LlmContextType.ARTICLE,
                            content = "A".repeat(200),
                            priority = 10,
                        ),
                        LlmContextItem(
                            id = "manual",
                            type = LlmContextType.MANUAL,
                            content = "B".repeat(200),
                            priority = 1,
                        ),
                    ),
                policy = LlmContextPolicy(maxCharacters = 160),
            )

        assertTrue("article" in result.includedIds)
        assertTrue("manual" in result.omittedIds)
        assertTrue(result.truncated)
        assertTrue(result.text.length <= 160)
        assertTrue(result.text.endsWith("[/ORIGREAD_CONTEXT]"))
    }

    @Test
    fun `context composer omits item when budget cannot preserve context wrapper`() {
        val result =
            LlmContextComposer().compose(
                items =
                    listOf(
                        LlmContextItem(
                            id = "article",
                            type = LlmContextType.ARTICLE,
                            content = "正文",
                        ),
                    ),
                policy = LlmContextPolicy(maxCharacters = 8),
            )

        assertTrue(result.text.isEmpty())
        assertTrue("article" in result.omittedIds)
        assertTrue(result.truncated)
    }

    @Test
    fun `sensitive tool requires confirmation and profile authorization`() = runBlocking {
        val runtime = LlmToolRuntime()
        runtime.register(
            object : LlmTool {
                override val descriptor =
                    LlmToolDescriptor(
                        id = "write-note",
                        name = "Write note",
                        description = "writes data",
                        source = LlmToolSource.MCP,
                        sourceId = "notes-server",
                        risk = LlmToolRisk.WRITE,
                    )

                override suspend fun execute(argumentsJson: String): LlmToolResult =
                    LlmToolResult.Success(argumentsJson)
            }
        )
        val call = LlmToolCall(id = "call-1", toolId = "write-note", argumentsJson = "{}")

        val denied = runtime.execute(call, LlmExecutionProfile())
        assertTrue(denied is LlmToolResult.Failure)

        val profile = LlmExecutionProfile(enabledToolIds = setOf("write-note"))
        val pending = runtime.execute(call, profile)
        assertTrue(pending is LlmToolResult.ConfirmationRequired)

        val allowed = runtime.execute(call, profile, confirmed = true)
        assertEquals(LlmToolResult.Success("{}"), allowed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mcp tool must keep source server identity`() {
        LlmToolDescriptor(
            id = "search",
            name = "Search",
            description = "searches remote service",
            source = LlmToolSource.MCP,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate tool id cannot silently replace another tool`() {
        val runtime = LlmToolRuntime()
        val first = testTool("same-id")
        val second = testTool("same-id")
        runtime.register(first)
        runtime.register(second)
    }

    @Test
    fun `unsupported reasoning effort is omitted instead of downgraded`() {
        val adapter =
            OpenAiCompatibleLlmAdapter(
                settingsRepository = mock<AiSettingsRepository>(),
                capabilityResolver = ModelCapabilityResolver(),
            )
        val capability =
            ModelCapability(
                supportedReasoningEfforts = setOf(LlmReasoningEffort.HIGH),
                reasoningParameterStyle = ReasoningParameterStyle.OPENAI_REASONING_EFFORT,
            )
        val parameter =
            adapter.reasoningParameter(
                capability = capability,
                requested = LlmReasoningEffort.MAXIMUM,
            )

        assertNull(parameter)
    }

    private fun testTool(id: String): LlmTool =
        object : LlmTool {
            override val descriptor =
                LlmToolDescriptor(
                    id = id,
                    name = id,
                    description = "test",
                    source = LlmToolSource.ORIGREAD_INTERNAL,
                )

            override suspend fun execute(argumentsJson: String): LlmToolResult =
                LlmToolResult.Success(argumentsJson)
        }
}
