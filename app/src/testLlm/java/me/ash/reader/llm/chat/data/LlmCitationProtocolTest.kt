package me.ash.reader.llm.chat.data

import me.ash.reader.llm.chat.ui.buildRequestHistorySnapshot
import me.ash.reader.llm.chat.ui.applyToolResultCitationProtocol
import me.ash.reader.llm.chat.runtime.LlmChatRequestMessage
import me.ash.reader.llm.runtime.LlmContextComposer
import me.ash.reader.llm.runtime.LlmContextEvidenceBlock
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextPolicy
import me.ash.reader.llm.runtime.LlmContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCitationProtocolTest {
    @Test
    fun budgetIncludesOnlyWholeEvidenceBlocksBeforeAssigningProtocolIds() {
        val composed =
            LlmContextComposer().compose(
                items =
                    listOf(
                        LlmContextItem(
                            id = "article:1:original",
                            type = LlmContextType.ARTICLE,
                            content = "raw article fallback",
                            evidenceBlocks =
                                listOf(
                                    LlmContextEvidenceBlock("p:first", "Short supported evidence."),
                                    LlmContextEvidenceBlock("p:oversized", "x".repeat(600)),
                                ),
                        )
                    ),
                policy = LlmContextPolicy(maxTokens = 80),
            )

        assertEquals(listOf("p:first"), composed.renderedItems.single().evidenceBlockKeys)
        assertTrue(composed.renderedItems.single().truncated)
        assertFalse(composed.text.contains("p:oversized"))

        val prepared =
            prepareCitationProtocol(
                composed = composed,
                candidates =
                    listOf(
                        candidate("p:first", "block-1", "Short supported evidence."),
                        candidate("p:oversized", "block-2", "x".repeat(600)),
                    ),
            )

        assertEquals(listOf("E1"), prepared.protocolEntries.map { it.protocolId })
        assertTrue(prepared.text.contains("[ORIGREAD_EVIDENCE id=\"E1\"]"))
        assertFalse(prepared.text.contains("E2"))
        assertFalse(prepared.text.contains("p:first"))
        assertTrue(prepared.instruction.contains("closely related claim group"))
        assertTrue(prepared.instruction.contains("preserve coverage"))
    }

    @Test
    fun assistantTokensDeduplicateValidIdsAndRejectHallucinatedIds() {
        val entry =
            LlmCitationProtocolEntry(
                contextId = "article:1:original",
                stableLocatorKey = "p:first",
                contextRefId = "context-ref-1",
                evidenceBlockId = "block-1",
                targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
                quoteSnapshot = "Evidence one.",
                sourceUrl = "https://example.com/article",
                locatorSnapshot = locator("p:first", "hash-1"),
                protocolId = "E1",
            )

        val built =
            buildCitationRefsFromAssistantOutput(
                assistantText = "Claim [[E1]], repeated [[E1]], fake [[E999]].",
                allowedEntries = listOf(entry),
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                createdAt = 123L,
                idFactory = { "citation-1" },
            )

        assertEquals(listOf("E999"), built.invalidProtocolIds)
        assertEquals(1, built.refs.size)
        assertEquals("citation-1", built.refs.single().id)
        assertEquals("E1", built.refs.single().protocolId)
        assertEquals(1, built.refs.single().displayOrder)
        assertEquals("block-1", built.refs.single().evidenceBlockId)
    }

    @Test
    fun displayOrderFollowsFirstActualAppearanceInsteadOfProtocolNumber() {
        val entries =
            listOf(
                entry("E1", "p:first", "block-1"),
                entry("E2", "p:second", "block-2"),
            )
        var nextId = 0
        val built =
            buildCitationRefsFromAssistantOutput(
                assistantText = "Second first [[E2]], then first [[E1]], bad [[E404]].",
                allowedEntries = entries,
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                idFactory = { "citation-${++nextId}" },
            )

        assertEquals(listOf("E404"), built.invalidProtocolIds)
        assertEquals(listOf("E2", "E1"), built.refs.map { it.protocolId })
        assertEquals(listOf(1, 2), built.refs.map { it.displayOrder })
    }

    @Test
    fun identicalStableKeysInDifferentArticlesReceiveDistinctProtocolIds() {
        val sharedKey = "PARAGRAPH:shared:hash:0"
        val composed =
            LlmContextComposer().compose(
                items =
                    listOf(
                        LlmContextItem(
                            id = "article:a:original",
                            type = LlmContextType.ARTICLE,
                            content = "same",
                            evidenceBlocks = listOf(LlmContextEvidenceBlock(sharedKey, "Same evidence.")),
                            priority = 100,
                        ),
                        LlmContextItem(
                            id = "article:b:original",
                            type = LlmContextType.ARTICLE,
                            content = "same",
                            evidenceBlocks = listOf(LlmContextEvidenceBlock(sharedKey, "Same evidence.")),
                            priority = 90,
                        ),
                    ),
                policy = LlmContextPolicy(maxTokens = 256),
            )
        val prepared =
            prepareCitationProtocol(
                composed,
                listOf(
                    candidate(sharedKey, "block-a", "Same evidence.", contextId = "article:a:original"),
                    candidate(sharedKey, "block-b", "Same evidence.", contextId = "article:b:original"),
                ),
            )

        assertEquals(listOf("E1", "E2"), prepared.protocolEntries.map { it.protocolId })
        assertEquals(listOf("block-a", "block-b"), prepared.protocolEntries.map { it.evidenceBlockId })
        assertTrue(prepared.text.contains("id=\"E1\""))
        assertTrue(prepared.text.contains("id=\"E2\""))
    }

    @Test
    fun historicalAssistantCitationTokensNeverReachNextProviderRequest() {
        val messages =
            listOf(
                message("user-1", LlmChatRole.USER, "question", 1L),
                message("assistant-old", LlmChatRole.ASSISTANT, "First claim [[E1]], second [[E12]].", 2L),
                message("user-2", LlmChatRole.USER, "follow up", 3L),
            )
        val snapshot =
            buildRequestHistorySnapshot(
                messages = messages,
                toolCalls = emptyList(),
                excludedAssistantId = "assistant-new",
                citationFeatureEnabled = true,
            )

        val historicalAssistant = snapshot.messages.single { it.role == LlmChatRole.ASSISTANT }
        assertEquals("First claim, second.", historicalAssistant.content)
        assertFalse(snapshot.messages.any { it.content.contains("[[E") })
    }

    @Test
    fun disabledCitationGatePreservesHistoricalAssistantTextExactly() {
        val original = "Literal protocol-looking text [[E1]] must remain."
        val snapshot =
            buildRequestHistorySnapshot(
                messages =
                    listOf(
                        message("user-1", LlmChatRole.USER, "question", 1L),
                        message("assistant-old", LlmChatRole.ASSISTANT, original, 2L),
                    ),
                toolCalls = emptyList(),
                excludedAssistantId = "assistant-new",
                citationFeatureEnabled = false,
            )

        assertEquals(
            original,
            snapshot.messages.single { it.role == LlmChatRole.ASSISTANT }.content,
        )
    }

    @Test
    fun evidencePersistenceUsesSameStableIdentitiesAsContextBudgetInput() {
        val item =
            LlmContextItem(
                id = "article:1:original",
                type = LlmContextType.ARTICLE,
                content = "<h2>Section</h2><p>Evidence text.</p>",
                sourceId = "https://example.com/article",
                internalArticleId = "article-1",
            ).withArticleEvidenceBlocks()
        val contextRef =
            LlmContextRefEntity(
                id = "context-ref-1",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-1",
                contextId = item.id,
                type = item.type,
                title = null,
                sourceId = item.sourceId,
                articleId = item.internalArticleId,
                sourceUrl = item.sourceId,
                contentSnapshot = item.content,
                promptContentSnapshot = item.content,
                contentSha256 = "context-hash",
                priority = 100,
                includedInPrompt = true,
                truncatedInPrompt = false,
                createdAt = 1L,
            )
        var nextId = 0
        val state =
            buildEvidencePersistence(
                contextItems = listOf(item),
                contextRefs = listOf(contextRef),
                createdAt = 2L,
                idFactory = { "block-${++nextId}" },
            )

        assertEquals(item.evidenceBlocks.map { it.stableLocatorKey }, state.evidenceBlocks.map { it.stableLocatorKey })
        assertEquals(state.evidenceBlocks.map { it.id }, state.citationCandidates.map { it.evidenceBlockId })
        assertTrue(state.citationCandidates.all { it.contextRefId == contextRef.id })
    }

    @Test
    fun providerHistoryToolResultReceivesProtocolAfterComposedEvidence() {
        val composed =
            LlmContextComposer().compose(
                items =
                    listOf(
                        LlmContextItem(
                            id = "article:1:original",
                            type = LlmContextType.ARTICLE,
                            content = "Article evidence",
                            evidenceBlocks =
                                listOf(LlmContextEvidenceBlock("article-block", "Article evidence")),
                        )
                    ),
                policy = LlmContextPolicy(maxTokens = 256),
            )
        val prepared =
            prepareCitationProtocol(
                composed = composed,
                candidates =
                    listOf(
                        candidate("article-block", "block-article", "Article evidence"),
                        LlmCitationEvidenceCandidate(
                            contextId = "tool-result:local-call",
                            stableLocatorKey = "tool-block",
                            contextRefId = "context-tool",
                            evidenceBlockId = "block-tool",
                            targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
                            quoteSnapshot = "Tool evidence",
                            sourceUrl = null,
                            locatorSnapshot =
                                LlmEvidenceLocatorV1(
                                    sourceKind = LlmEvidenceSourceKind.TOOL_RESULT,
                                    stableLocatorKey = "tool-block",
                                    toolCallId = "local-call",
                                    toolId = "mcp:test:read",
                                    toolName = "read",
                                    normalizedHash = "tool-hash",
                                ),
                        ),
                    ),
                includedHistoryContextIds = listOf("tool-result:local-call"),
            )

        assertEquals(listOf("E1", "E2"), prepared.protocolEntries.map { it.protocolId })
        assertEquals(
            listOf("article:1:original", "tool-result:local-call"),
            prepared.protocolEntries.map { it.contextId },
        )
    }

    @Test
    fun toolResultProtocolWrapsOnlyMatchingProviderToolMessageAndKeepsCallId() {
        val toolCall =
            LlmToolCallEntity(
                id = "local-call",
                conversationId = "conversation-1",
                assistantMessageId = "assistant-old",
                providerCallId = "provider-call",
                toolId = "mcp:test:read",
                apiName = "read",
                argumentsJson = "{}",
                status = LlmToolCallStatus.COMPLETE,
                resultContent = "Tool evidence",
                createdAt = 1L,
                updatedAt = 1L,
            )
        val history =
            listOf(
                LlmChatRequestMessage(role = LlmChatRole.USER, content = "question"),
                LlmChatRequestMessage(
                    role = LlmChatRole.TOOL,
                    content = "Tool evidence",
                    toolCallId = "provider-call",
                ),
                LlmChatRequestMessage(
                    role = LlmChatRole.TOOL,
                    content = "Other tool evidence",
                    toolCallId = "provider-other",
                ),
            )
        val entry =
            LlmCitationProtocolEntry(
                contextId = "tool-result:local-call",
                stableLocatorKey = "tool-block",
                contextRefId = "context-tool",
                evidenceBlockId = "block-tool",
                targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
                quoteSnapshot = "Tool evidence",
                sourceUrl = null,
                locatorSnapshot =
                    LlmEvidenceLocatorV1(
                        sourceKind = LlmEvidenceSourceKind.TOOL_RESULT,
                        stableLocatorKey = "tool-block",
                        toolCallId = "local-call",
                        toolId = toolCall.toolId,
                        toolName = toolCall.apiName,
                        normalizedHash = "tool-hash",
                    ),
                protocolId = "E2",
            )

        val wrapped = applyToolResultCitationProtocol(history, listOf(toolCall), listOf(entry))
        val matching = wrapped.single { it.toolCallId == "provider-call" }
        val other = wrapped.single { it.toolCallId == "provider-other" }

        assertEquals("provider-call", matching.toolCallId)
        assertTrue(matching.content.contains("[ORIGREAD_EVIDENCE id=\"E2\"]"))
        assertTrue(matching.content.contains("Tool evidence"))
        assertEquals("Other tool evidence", other.content)
    }

    @Test
    fun automaticToolEvidencePersistsOnlySuccessfulFinalizedResults() {
        val refs =
            listOf(
                toolContextRef("complete", "complete result"),
                toolContextRef("denied", "denied result"),
            )
        val calls =
            listOf(
                toolCall("complete", LlmToolCallStatus.COMPLETE, "complete result"),
                toolCall("denied", LlmToolCallStatus.DENIED, "denied result"),
            )

        val state =
            buildEvidencePersistence(
                contextItems = emptyList(),
                contextRefs = refs,
                toolCalls = calls,
                createdAt = 2L,
                idFactory = { "block-auto" },
            )

        assertEquals(1, state.evidenceBlocks.size)
        assertEquals(LlmEvidenceSourceKind.TOOL_RESULT, state.evidenceBlocks.single().locator.sourceKind)
        assertEquals("complete", state.evidenceBlocks.single().locator.toolCallId)
        assertEquals("Frozen tool", state.evidenceBlocks.single().locator.toolName)
        assertEquals("server-1", state.evidenceBlocks.single().locator.toolSourceId)
        assertEquals("tool-result:complete", state.citationCandidates.single().contextId)
    }

    private fun candidate(
        key: String,
        blockId: String,
        quote: String,
        contextId: String = "article:1:original",
    ): LlmCitationEvidenceCandidate =
        LlmCitationEvidenceCandidate(
            contextId = contextId,
            stableLocatorKey = key,
            contextRefId = "context-ref-1",
            evidenceBlockId = blockId,
            targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
            quoteSnapshot = quote,
            sourceUrl = "https://example.com/article",
            locatorSnapshot = locator(key, "hash-$blockId"),
        )

    private fun entry(
        protocolId: String,
        key: String,
        blockId: String,
    ): LlmCitationProtocolEntry =
        LlmCitationProtocolEntry(
            contextId = "article:1:original",
            stableLocatorKey = key,
            contextRefId = "context-ref-1",
            evidenceBlockId = blockId,
            targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
            quoteSnapshot = "quote-$blockId",
            sourceUrl = "https://example.com/article",
            locatorSnapshot = locator(key, "hash-$blockId"),
            protocolId = protocolId,
        )

    private fun locator(key: String, hash: String): LlmEvidenceLocatorV1 =
        LlmEvidenceLocatorV1(
            sourceKind = LlmEvidenceSourceKind.ARTICLE,
            stableLocatorKey = key,
            articleId = "article-1",
            sourceUrl = "https://example.com/article",
            normalizedHash = hash,
        )

    private fun message(
        id: String,
        role: LlmChatRole,
        content: String,
        createdAt: Long,
    ): LlmMessageEntity =
        LlmMessageEntity(
            id = id,
            conversationId = "conversation-1",
            role = role,
            content = content,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    private fun toolContextRef(id: String, content: String): LlmContextRefEntity =
        LlmContextRefEntity(
            id = "context-$id",
            conversationId = "conversation-1",
            assistantMessageId = "assistant-new",
            contextId = "tool-result:$id",
            type = LlmContextType.TOOL_RESULT,
            title = "read",
            sourceId = "mcp:test:read",
            sourceUrl = null,
            contentSnapshot = content,
            promptContentSnapshot = content,
            contentSha256 = "hash-$id".padEnd(64, '0').take(64),
            priority = 100,
            includedInPrompt = true,
            truncatedInPrompt = false,
            createdAt = 1L,
        )

    private fun toolCall(
        id: String,
        status: LlmToolCallStatus,
        result: String?,
    ): LlmToolCallEntity =
        LlmToolCallEntity(
            id = id,
            conversationId = "conversation-1",
            assistantMessageId = "assistant-old",
            providerCallId = "provider-$id",
            toolId = "mcp:test:read",
            toolName = "Frozen tool",
            toolSourceId = "server-1",
            apiName = "read",
            argumentsJson = "{}",
            status = status,
            resultContent = result,
            createdAt = 1L,
            updatedAt = 1L,
        )
}
