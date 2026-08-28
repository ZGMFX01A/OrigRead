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
                policy = LlmContextPolicy(maxTokens = 80),
            )

        assertTrue("article" in result.includedIds)
        assertTrue("manual" in result.omittedIds)
        assertTrue(result.truncated)
        assertTrue(LlmContextComposer().estimateTokens(result.text) <= 80)
        assertTrue(result.text.endsWith("[/ORIGREAD_CONTEXT]"))
        assertEquals(listOf("article"), result.renderedItems.map(LlmRenderedContextItem::id))
        assertFalse(result.renderedItems.single().truncated)
        assertEquals(200, result.renderedItems.single().content.length)
    }

    @Test
    fun `context composer preserves reserved article evidence after higher priority summary`() {
        val summary =
            LlmContextItem(
                id = "summary",
                type = LlmContextType.ARTICLE_SUMMARY,
                content = "S".repeat(20_000),
                priority = 130,
            )
        val article =
            LlmContextItem(
                id = "article",
                type = LlmContextType.ARTICLE,
                content = "A".repeat(20_000),
                reserveEvidenceBudget = true,
                priority = 100,
            )
        val composer = LlmContextComposer()

        listOf(1_000, 4_000, 128_000).forEach { budget ->
            val result =
                composer.compose(
                    items = listOf(summary, article),
                    policy = LlmContextPolicy(maxTokens = budget),
                )

            assertTrue("article evidence must survive budget=$budget", "article" in result.includedIds)
            assertTrue(
                "article evidence must contain prompt text for budget=$budget",
                result.renderedItems.single { it.id == "article" }.content.isNotBlank(),
            )
            assertTrue(composer.estimateTokens(result.text) <= budget)
        }
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
                policy = LlmContextPolicy(maxTokens = 1),
            )

        assertTrue(result.text.isEmpty())
        assertTrue("article" in result.omittedIds)
        assertTrue(result.truncated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `context composer rejects duplicate ids because result bookkeeping is id based`() {
        LlmContextComposer().compose(
            items =
                listOf(
                    LlmContextItem(
                        id = "same",
                        type = LlmContextType.ARTICLE,
                        content = "正文 A",
                    ),
                    LlmContextItem(
                        id = "same",
                        type = LlmContextType.MANUAL,
                        content = "正文 B",
                    ),
                ),
            policy = LlmContextPolicy(),
        )
    }

    @Test
    fun `context composer never truncates inside an emoji surrogate pair`() {
        val composer = LlmContextComposer()
        val item =
            LlmContextItem(
                id = "emoji",
                type = LlmContextType.ARTICLE,
                content = "A😀B",
            )
        val full =
            composer.compose(
                items = listOf(item),
                policy = LlmContextPolicy(maxTokens = 10_000),
            )
        val contentStart = full.text.indexOf('\n') + 1
        val footerLength = "\n[/ORIGREAD_CONTEXT]".length
        val prefix = full.text.substring(0, contentStart)
        val footer = full.text.takeLast(footerLength)
        val fixedTokens = composer.estimateTokens(prefix) + composer.estimateTokens(footer)
        // 正文只留 1 token：ASCII A 可进入，但随后 emoji 作为完整 code point 不再有预算。
        val budget = fixedTokens + 1
        val truncated =
            composer.compose(
                items = listOf(item),
                policy = LlmContextPolicy(maxTokens = budget),
            )
        val renderedContent =
            truncated.text.substring(
                startIndex = contentStart,
                endIndex = truncated.text.length - footerLength,
            )

        assertEquals("A", renderedContent)
        assertEquals("A", truncated.renderedItems.single().content)
        assertTrue(truncated.renderedItems.single().truncated)
        assertTrue(truncated.truncated)
        assertFalse(renderedContent.any(Char::isSurrogate))
    }

    @Test
    fun `context token estimator is conservative for cjk and denser for latin words`() {
        val composer = LlmContextComposer()

        assertEquals(4, composer.estimateTokens("中文测试"))
        assertEquals(1, composer.estimateTokens("test"))
        assertEquals(1, composer.estimateTokens("😀"))
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
