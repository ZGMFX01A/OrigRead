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
import me.ash.reader.llm.chat.data.LlmChatConverters
import me.ash.reader.llm.chat.data.LlmConversationArticleEntity
import me.ash.reader.llm.chat.data.LlmMessageEntity
import me.ash.reader.llm.chat.data.LlmMessageStatus
import me.ash.reader.llm.chat.runtime.LlmChatRequestMessage
import me.ash.reader.llm.chat.runtime.LlmChatRequestToolCall
import me.ash.reader.llm.chat.runtime.LlmChatTransport
import me.ash.reader.llm.chat.runtime.buildLlmChatSystemPrompt
import me.ash.reader.llm.chat.runtime.parseNonStreamingPayload
import me.ash.reader.llm.chat.runtime.parseStreamPayload
import me.ash.reader.llm.chat.data.deriveConversationTitle
import me.ash.reader.llm.chat.data.buildContextRefEntities
import me.ash.reader.llm.chat.data.buildRequestCitationReferences
import me.ash.reader.llm.chat.data.buildRequestContextRefEntities
import me.ash.reader.llm.chat.data.buildToolResultContextRefEntities
import me.ash.reader.llm.chat.data.citationToken
import me.ash.reader.llm.chat.data.LlmToolCallEntity
import me.ash.reader.llm.chat.data.LlmToolCallStatus
import me.ash.reader.llm.chat.ui.buildArticleContextItems
import me.ash.reader.llm.chat.ui.buildAdditionalArticleContextItems
import me.ash.reader.llm.chat.ui.buildRequestArticleContextItems
import me.ash.reader.llm.chat.ui.LlmArticleAttachment
import me.ash.reader.llm.chat.ui.normalizedAdditionalArticleAttachments
import me.ash.reader.llm.chat.ui.toArticleAttachment
import me.ash.reader.llm.chat.ui.toConversationArticleEntity
import me.ash.reader.llm.chat.ui.upsertAdditionalArticleAttachment
import me.ash.reader.llm.chat.ui.buildRequestHistorySnapshot
import me.ash.reader.llm.chat.ui.buildLlmCitationLink
import me.ash.reader.llm.chat.ui.parseLlmCitationUri
import me.ash.reader.llm.chat.ui.resolveRequestSkillId
import me.ash.reader.llm.chat.ui.shouldShowArticleAssistantConfigurationHint
import me.ash.reader.llm.chat.ui.shouldExposeManualToolFallback
import me.ash.reader.llm.runtime.LlmCitationReference
import me.ash.reader.llm.runtime.ComposedLlmContext
import me.ash.reader.llm.runtime.LlmContextComposer
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextPolicy
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.runtime.LlmExecutionPlan
import me.ash.reader.llm.runtime.LlmExecutionTask
import me.ash.reader.llm.runtime.LlmReasoningEffort
import me.ash.reader.llm.runtime.LlmToolDescriptor
import me.ash.reader.llm.runtime.LlmToolSource
import me.ash.reader.llm.runtime.ModelCapability
import me.ash.reader.llm.runtime.ReasoningParameterStyle
import me.ash.reader.llm.runtime.estimateLlmTokens
import me.ash.reader.ui.page.home.reading.ArticleAssistantContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmChatFoundationTest {
    @Test
    fun `configured article chat empty state hides explanatory copy`() {
        assertFalse(shouldShowArticleAssistantConfigurationHint(configured = true))
        assertTrue(shouldShowArticleAssistantConfigurationHint(configured = false))
    }

    @Test
    fun `p6 combined context budget keeps explicit and current article evidence ahead of attachments`() {
        val current =
            ArticleAssistantContext(
                articleId = "current",
                title = "Current",
                link = "https://example.com/current",
                originalContent = "current original evidence",
                summary = "current summary evidence",
                translatedTitle = "Current translated",
                translatedContent = "current translated evidence",
                selectedText = "explicit selected evidence",
            )
        val attachments =
            listOf(
                LlmArticleAttachment(
                    articleId = "second",
                    title = "Second",
                    link = "https://example.com/second",
                    originalContent = "second original evidence",
                    summary = "second summary evidence",
                )
            )
        val webSearch =
            LlmContextItem(
                id = "web-search:test:1",
                type = LlmContextType.WEB_SEARCH_RESULT,
                title = "Fresh evidence",
                sourceId = "https://example.com/web",
                content = "fresh web evidence",
                priority = 110,
            )
        val candidates =
            buildRequestArticleContextItems(
                currentArticle = current,
                attachments = attachments,
                includeAdditionalArticles = true,
            ) + webSearch
        val expectedIncluded =
            listOf(
                "article:current:selection",
                "article:current:summary",
                "article:current:translation",
                "web-search:test:1",
                "article:current:original",
            )
        val composer = LlmContextComposer()
        val core =
            composer.compose(
                items = candidates.filter { it.id in expectedIncluded },
                policy = LlmContextPolicy(maxTokens = 4_096),
            )
        val composed =
            composer.compose(
                items = candidates,
                // 用刚好容纳高优先级核心来源的预算验证低优先级附加文章不会挤掉当前文章正文。
                policy = LlmContextPolicy(maxTokens = estimateLlmTokens(core.text)),
            )

        assertEquals(expectedIncluded, composed.includedIds)
        assertEquals(
            listOf("article:second:summary", "article:second:original"),
            composed.omittedIds,
        )

        val refs =
            buildRequestContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant",
                candidates = candidates,
                composed = composed,
                toolCalls = emptyList(),
                createdAt = 1L,
            )
        assertEquals(
            listOf("[R1]", "[R2]", "[R3]", "[R4]", "[R5]"),
            refs.sortedBy { it.citationIndex ?: Int.MAX_VALUE }.mapNotNull { it.citationToken() },
        )
        refs.filter { it.contextId.startsWith("article:second:") }.forEach { ref ->
            assertFalse(ref.includedInPrompt)
            assertNull(ref.citationIndex)
            assertNull(ref.citationToken())
        }
    }

    @Test
    fun `p6 frozen request refs survive later summary and attachment changes`() {
        val composer = LlmContextComposer()
        val firstCandidates =
            buildRequestArticleContextItems(
                currentArticle =
                    ArticleAssistantContext(
                        articleId = "current",
                        title = "Current",
                        link = "https://example.com/current",
                        originalContent = "stable original",
                        summary = "old summary",
                    ),
                attachments =
                    listOf(
                        LlmArticleAttachment(
                            articleId = "second",
                            title = "Second",
                            link = "https://example.com/second",
                            originalContent = "old second snapshot",
                        )
                    ),
                includeAdditionalArticles = true,
            )
        val firstComposed = composer.compose(firstCandidates, LlmContextPolicy(maxTokens = 4_096))
        val firstRefs =
            buildRequestContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant-old",
                candidates = firstCandidates,
                composed = firstComposed,
                toolCalls = emptyList(),
                createdAt = 1L,
            )

        val secondCandidates =
            buildRequestArticleContextItems(
                currentArticle =
                    ArticleAssistantContext(
                        articleId = "current",
                        title = "Current",
                        link = "https://example.com/current",
                        originalContent = "stable original",
                        summary = "new summary",
                    ),
                attachments = emptyList(),
                includeAdditionalArticles = true,
            )
        val secondComposed = composer.compose(secondCandidates, LlmContextPolicy(maxTokens = 4_096))
        val secondRefs =
            buildRequestContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant-new",
                candidates = secondCandidates,
                composed = secondComposed,
                toolCalls = emptyList(),
                createdAt = 2L,
            )

        val oldSummary = firstRefs.single { it.contextId == "article:current:summary" }
        val oldAttachment = firstRefs.single { it.contextId == "article:second:original" }
        val newSummary = secondRefs.single { it.contextId == "article:current:summary" }
        assertEquals("old summary", oldSummary.contentSnapshot)
        assertEquals("old second snapshot", oldAttachment.contentSnapshot)
        assertEquals("new summary", newSummary.contentSnapshot)
        assertTrue(secondRefs.none { it.contextId.startsWith("article:second:") })
        assertFalse(oldSummary.id == newSummary.id)
        assertEquals("[R1]", oldSummary.citationToken())
        assertEquals("[R1]", newSummary.citationToken())
    }

    @Test
    fun `multi article context reuses runtime items and keeps current article priority first`() {
        val current =
            ArticleAssistantContext(
                articleId = "current",
                title = "Current article",
                link = "https://example.com/current",
                originalContent = "current original",
                summary = "current summary",
                selectedText = "current selection",
            )
        val attachments =
            listOf(
                LlmArticleAttachment(
                    articleId = "second",
                    title = "Second article",
                    link = "https://example.com/second",
                    originalContent = "second original",
                    summary = "second summary",
                ),
                LlmArticleAttachment(
                    articleId = "third",
                    title = "Third article",
                    link = "https://example.com/third",
                    originalContent = "third original",
                ),
            )

        val items =
            buildRequestArticleContextItems(
                currentArticle = current,
                attachments = attachments,
                includeAdditionalArticles = true,
            )

        assertEquals(
            listOf(
                "article:current:selection",
                "article:current:summary",
                "article:current:original",
                "article:second:summary",
                "article:second:original",
                "article:third:original",
            ),
            items.map { it.id },
        )
        assertEquals(
            listOf(160, 130, 100, 95, 90, 90),
            items.map { it.priority },
        )
        val composed = LlmContextComposer().compose(items, LlmContextPolicy(maxTokens = 4_096))
        assertEquals(items.map { it.id }, composed.includedIds)
        val refs =
            buildRequestContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant",
                candidates = items,
                composed = composed,
                toolCalls = emptyList(),
                createdAt = 1L,
            )
        assertEquals(
            listOf("[R1]", "[R2]", "[R3]", "[R4]", "[R5]", "[R6]"),
            refs.sortedBy { it.citationIndex }.mapNotNull { it.citationToken() },
        )
        assertEquals(
            listOf(LlmContextType.ARTICLE_SUMMARY, LlmContextType.ARTICLE),
            refs.filter { it.contextId.startsWith("article:second:") }.map { it.type },
        )
    }

    @Test
    fun `additional article normalization rejects current duplicate and blank snapshots`() {
        val normalized =
            normalizedAdditionalArticleAttachments(
                currentArticleId = "current",
                attachments =
                    listOf(
                        LlmArticleAttachment(" current ", "same", null, "body"),
                        LlmArticleAttachment("second", " Second ", " https://example.com/2 ", " body 2 ", " summary 2 "),
                        LlmArticleAttachment("second", "duplicate", null, "duplicate body"),
                        LlmArticleAttachment("third", "blank", null, "   ", "   "),
                    ),
            )

        assertEquals(1, normalized.size)
        assertEquals("second", normalized.single().articleId)
        assertEquals("Second", normalized.single().title)
        assertEquals("https://example.com/2", normalized.single().link)
        assertEquals("body 2", normalized.single().originalContent)
        assertEquals("summary 2", normalized.single().summary)
    }

    @Test
    fun `additional article upsert replaces in place and article analysis excludes attachments`() {
        val existing =
            listOf(
                LlmArticleAttachment("second", "Old second", null, "old second"),
                LlmArticleAttachment("third", "Third", null, "third body"),
            )
        val updated =
            upsertAdditionalArticleAttachment(
                currentArticleId = "current",
                existing = existing,
                attachment = LlmArticleAttachment("second", "New second", null, "new second"),
            )
        assertEquals(listOf("second", "third"), updated.map { it.articleId })
        assertEquals("New second", updated.first().title)

        val current = ArticleAssistantContext("current", "Current", null, "current body")
        val analysisItems =
            buildRequestArticleContextItems(
                currentArticle = current,
                attachments = updated,
                includeAdditionalArticles = false,
            )
        assertEquals(listOf("article:current:original"), analysisItems.map { it.id })
        assertTrue(buildAdditionalArticleContextItems("current", updated).isNotEmpty())
    }

    @Test
    fun `conversation article snapshots round trip without changing attachment order`() {
        val attachments =
            listOf(
                LlmArticleAttachment(
                    articleId = "second",
                    title = "Second",
                    link = "https://example.com/2",
                    originalContent = "second body",
                    summary = "second summary",
                ),
                LlmArticleAttachment(
                    articleId = "third",
                    title = "Third",
                    link = null,
                    originalContent = "third body",
                ),
            )

        val entities: List<LlmConversationArticleEntity> =
            attachments.mapIndexed { index, attachment ->
                attachment.toConversationArticleEntity(
                    conversationId = "conversation",
                    position = index,
                    createdAt = 1234L,
                )
            }
        val restored = entities.sortedBy { it.position }.map { it.toArticleAttachment() }

        assertEquals(listOf(0, 1), entities.map { it.position })
        assertEquals(listOf("second", "third"), restored.map { it.articleId })
        assertEquals(attachments, restored)
    }

    @Test
    fun `citation ui links only accept request valid R tokens and strict internal uri`() {
        val validIndices = setOf(1, 3)

        assertEquals("origread-citation://1", buildLlmCitationLink("[R1]", validIndices))
        assertEquals("origread-citation://3", buildLlmCitationLink("[R3]", validIndices))
        assertNull(buildLlmCitationLink("[R2]", validIndices))
        assertNull(buildLlmCitationLink("[R0]", validIndices))
        assertNull(buildLlmCitationLink("R1", validIndices))
        assertNull(buildLlmCitationLink("[r1]", validIndices))

        assertEquals(1, parseLlmCitationUri("origread-citation://1"))
        assertEquals(3, parseLlmCitationUri("origread-citation://3"))
        assertNull(parseLlmCitationUri("origread-citation://0"))
        assertNull(parseLlmCitationUri("origread-citation://3/extra"))
        assertNull(parseLlmCitationUri("https://example.com/R1"))
    }

    @Test
    fun `article analysis consumes fixed analysis skill instead of chat auto route`() {
        assertEquals(
            "analysis-skill",
            resolveRequestSkillId(
                requestTask = LlmExecutionTask.ARTICLE_ANALYSIS,
                autoChatSkillId = "accidental-chat-skill",
                articleAnalysisSkillId = "analysis-skill",
            ),
        )
        assertEquals(
            "chat-skill",
            resolveRequestSkillId(
                requestTask = LlmExecutionTask.CHAT,
                autoChatSkillId = "chat-skill",
                articleAnalysisSkillId = "analysis-skill",
            ),
        )
    }

    @Test
    fun `article analysis system prompt keeps hard task skill custom context ordering`() {
        val prompt =
            buildLlmChatSystemPrompt(
                LlmExecutionPlan(
                    task = LlmExecutionTask.ARTICLE_ANALYSIS,
                    providerId = "provider",
                    providerName = "Provider",
                    runtimeConfig = AiRuntimeConfig("https://example.com/v1", "model", ""),
                    capability = ModelCapability(),
                    reasoningParameter = null,
                    tools = emptyList(),
                    automaticToolCalling = false,
                    context =
                        ComposedLlmContext(
                            text = "[ORIGREAD_CONTEXT type=ARTICLE id=article:1]article body[/ORIGREAD_CONTEXT]",
                            includedIds = listOf("article:1"),
                            omittedIds = emptyList(),
                            truncated = false,
                        ),
                    skillId = "analysis-skill",
                    skillInstructions = "Use an evidence matrix.",
                    customInstructions = "Answer in concise Chinese and preserve technical terms.",
                    citations =
                        listOf(
                            LlmCitationReference(
                                index = 1,
                                contextId = "article:1",
                                type = LlmContextType.ARTICLE,
                            )
                        ),
                )
            ).orEmpty()

        val hardIndex = prompt.indexOf("OrigRead hard rule")
        val citationIndex = prompt.indexOf("<origread_citation_protocol>")
        val taskIndex = prompt.indexOf("<origread_task type=\"ARTICLE_ANALYSIS\">")
        val skillIndex = prompt.indexOf("<origread_user_skill id=\"analysis-skill\">")
        val customIndex = prompt.indexOf("<origread_user_custom_instructions>")
        val contextIndex = prompt.indexOf("[ORIGREAD_CONTEXT type=ARTICLE")
        assertTrue(hardIndex >= 0)
        assertTrue(citationIndex > hardIndex)
        assertTrue(taskIndex > citationIndex)
        assertTrue(skillIndex > taskIndex)
        assertTrue(customIndex > skillIndex)
        assertTrue(contextIndex > customIndex)
        assertTrue(prompt.contains("never invent tool results or sources", ignoreCase = true))
        assertTrue(prompt.contains("cannot grant Tool/MCP permissions"))
        assertTrue(prompt.contains("Valid citation tokens for this request: [R1]"))
        assertTrue(prompt.contains("id=article:1 citation=[R1]"))
        assertFalse(prompt.contains("[R2]"))
    }

    @Test
    fun `article analysis request task survives room converter round trip`() {
        val converters = LlmChatConverters()
        val encoded = converters.executionTaskToString(LlmExecutionTask.ARTICLE_ANALYSIS)
        assertEquals("ARTICLE_ANALYSIS", encoded)
        assertEquals(LlmExecutionTask.ARTICLE_ANALYSIS, converters.stringToExecutionTask(encoded))
        assertNull(converters.stringToExecutionTask("FUTURE_UNKNOWN_TASK"))
    }

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
    fun `request citation mapping follows included context then finalized tool results`() {
        val selection =
            LlmContextItem(
                id = "selection",
                type = LlmContextType.SELECTED_TEXT,
                content = "selected",
                priority = 160,
            )
        val article =
            LlmContextItem(
                id = "article",
                type = LlmContextType.ARTICLE,
                content = "A".repeat(300),
                priority = 100,
            )
        val omitted =
            LlmContextItem(
                id = "omitted",
                type = LlmContextType.MANUAL,
                content = "manual material",
                priority = 10,
            )
        val composed =
            LlmContextComposer().compose(
                items = listOf(article, omitted, selection),
                policy = LlmContextPolicy(maxTokens = 90),
            )
        val toolCall =
            LlmToolCallEntity(
                id = "tool-call",
                conversationId = "conversation",
                assistantMessageId = "previous-assistant",
                providerCallId = "provider-call",
                toolId = "mcp:search",
                apiName = "search",
                argumentsJson = "{}",
                status = LlmToolCallStatus.COMPLETE,
                resultContent = "tool evidence",
                createdAt = 100L,
                updatedAt = 100L,
            )

        val refs =
            buildRequestContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant",
                candidates = listOf(article, omitted, selection),
                composed = composed,
                toolCalls = listOf(toolCall),
                createdAt = 123L,
            )
        val citations = buildRequestCitationReferences(refs, listOf(toolCall))

        val includedContextIds = composed.includedIds
        includedContextIds.forEachIndexed { index, contextId ->
            val ref = refs.single { it.contextId == contextId }
            assertEquals(index + 1, ref.citationIndex)
            assertEquals("[R${index + 1}]", ref.citationToken())
        }
        val toolRef = refs.single { it.contextId == "tool-result:tool-call" }
        assertEquals(includedContextIds.size + 1, toolRef.citationIndex)
        assertEquals("[R${includedContextIds.size + 1}]", toolRef.citationToken())
        val toolCitation = citations.single { it.contextId == toolRef.contextId }
        assertEquals(toolRef.citationIndex, toolCitation.index)
        assertEquals("provider-call", toolCitation.toolCallId)
        citations.filter { it.toolCallId == null }.forEach { citation ->
            assertTrue(citation.contextId in includedContextIds)
        }
        refs.filterNot { it.includedInPrompt }.forEach { ref ->
            assertNull(ref.citationIndex)
            assertNull(ref.citationToken())
        }
    }

    @Test
    fun `request citation mapping is isolated per assistant request`() {
        val item =
            LlmContextItem(
                id = "article",
                type = LlmContextType.ARTICLE,
                content = "article body",
                priority = 100,
            )
        val composed =
            LlmContextComposer().compose(
                items = listOf(item),
                policy = LlmContextPolicy(maxTokens = 128),
            )

        val first =
            buildRequestContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant-1",
                candidates = listOf(item),
                composed = composed,
                toolCalls = emptyList(),
                createdAt = 1L,
            ).single()
        val second =
            buildRequestContextRefEntities(
                conversationId = "conversation",
                assistantMessageId = "assistant-2",
                candidates = listOf(item),
                composed = composed,
                toolCalls = emptyList(),
                createdAt = 2L,
            ).single()

        assertEquals(1, first.citationIndex)
        assertEquals(1, second.citationIndex)
        assertEquals("[R1]", first.citationToken())
        assertEquals("[R1]", second.citationToken())
        assertFalse(first.id == second.id)
    }

    @Test
    fun `request tool citations include only tool results actually present in provider history`() {
        fun message(
            id: String,
            role: LlmChatRole,
            status: LlmMessageStatus = LlmMessageStatus.COMPLETE,
            content: String = "",
        ) =
            LlmMessageEntity(
                id = id,
                conversationId = "conversation",
                role = role,
                content = content,
                status = status,
                createdAt = 1L,
                updatedAt = 1L,
            )
        fun toolCall(localId: String, assistantId: String) =
            LlmToolCallEntity(
                id = localId,
                conversationId = "conversation",
                assistantMessageId = assistantId,
                // 故意复用同一个 Provider call id，验证绑定不依赖“跨轮全局唯一”假设。
                providerCallId = "provider-reused",
                toolId = "mcp:search",
                apiName = "search",
                argumentsJson = "{}",
                status = LlmToolCallStatus.COMPLETE,
                resultContent = "result-$localId",
                createdAt = 1L,
                updatedAt = 1L,
            )
        val visibleAssistant = message("assistant-visible", LlmChatRole.ASSISTANT)
        val hiddenAssistant =
            message(
                id = "assistant-hidden",
                role = LlmChatRole.ASSISTANT,
                status = LlmMessageStatus.ERROR,
            )
        val user = message("user", LlmChatRole.USER, content = "continue")
        val visible = toolCall("visible", visibleAssistant.id)
        val hidden = toolCall("hidden", hiddenAssistant.id)

        val snapshot =
            buildRequestHistorySnapshot(
                messages = listOf(visibleAssistant, hiddenAssistant, user),
                toolCalls = listOf(hidden, visible),
                excludedAssistantId = "assistant-current",
            )

        assertEquals(listOf("visible"), snapshot.toolCalls.map(LlmToolCallEntity::id))
        assertFalse(snapshot.toolCalls.any { it.id == "hidden" })
        val toolMessages =
            snapshot.messages.filter { it.role == LlmChatRole.TOOL }
        assertEquals(1, toolMessages.size)
        assertEquals("provider-reused", toolMessages.single().toolCallId)
        assertEquals("result-visible", toolMessages.single().content)
        assertTrue(
            snapshot.messages.any {
                it.role == LlmChatRole.USER && it.content == "continue"
            }
        )
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
    fun `chat transport labels cited tool result without changing original history`() = runBlocking {
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
                    runtimeConfig = AiRuntimeConfig(server.url("/v1").toString(), "test-model", ""),
                    capability = ModelCapability(),
                    reasoningParameter = null,
                    tools = emptyList(),
                    automaticToolCalling = false,
                    context = ComposedLlmContext("", emptyList(), emptyList(), false),
                    skillId = null,
                    citations =
                        listOf(
                            LlmCitationReference(
                                index = 1,
                                contextId = "tool-result:local-call",
                                type = LlmContextType.TOOL_RESULT,
                                toolCallId = "provider-call",
                            )
                        ),
                )
            val originalToolContent = "external tool evidence"
            val history =
                listOf(
                    LlmChatRequestMessage(
                        role = LlmChatRole.ASSISTANT,
                        content = "",
                        toolCalls =
                            listOf(
                                LlmChatRequestToolCall(
                                    id = "provider-call",
                                    name = "search",
                                    argumentsJson = "{}",
                                )
                            ),
                    ),
                    LlmChatRequestMessage(
                        role = LlmChatRole.TOOL,
                        content = originalToolContent,
                        toolCallId = "provider-call",
                    ),
                    LlmChatRequestMessage(LlmChatRole.USER, "What does it show?"),
                )

            LlmChatTransport(AiHttpClient())
                .stream(plan = plan, messages = history)
                .toList()

            val body = JSONObject(server.takeRequest().body.readUtf8())
            val messages = body.getJSONArray("messages")
            val systemContent = messages.getJSONObject(0).getString("content")
            val toolContent = messages.getJSONObject(2).getString("content")
            assertTrue(systemContent.contains("Valid citation tokens for this request: [R1]"))
            assertFalse(systemContent.contains("[R2]"))
            assertEquals("[ORIGREAD_CITATION token=[R1]]\n$originalToolContent", toolContent)
            assertEquals(originalToolContent, history[1].content)
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
