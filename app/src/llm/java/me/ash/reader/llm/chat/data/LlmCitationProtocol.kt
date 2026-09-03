package me.ash.reader.llm.chat.data

import java.util.UUID
import me.ash.reader.llm.runtime.ComposedLlmContext
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.runtime.llmEvidenceRequestIdentity

internal data class LlmCitationEvidenceCandidate(
    val contextId: String,
    val stableLocatorKey: String,
    val contextRefId: String,
    val evidenceBlockId: String?,
    val targetKind: LlmCitationTargetKind,
    val quoteSnapshot: String,
    val sourceUrl: String?,
    val locatorSnapshot: LlmEvidenceLocatorV1?,
)

internal data class LlmCitationProtocolEntry(
    val contextId: String,
    val stableLocatorKey: String,
    val contextRefId: String,
    val evidenceBlockId: String?,
    val targetKind: LlmCitationTargetKind,
    val quoteSnapshot: String,
    val sourceUrl: String?,
    val locatorSnapshot: LlmEvidenceLocatorV1?,
    val protocolId: String,
)

internal data class LlmCitationReadyContext(
    val text: String,
    val protocolEntries: List<LlmCitationProtocolEntry>,
    val instruction: String,
)

internal data class LlmResolvedAssistantCitations(
    val validProtocolIds: List<String>,
    val invalidProtocolIds: List<String>,
)

internal data class LlmBuiltCitationRefs(
    val refs: List<LlmCitationRefEntity>,
    val invalidProtocolIds: List<String>,
)

internal data class LlmEvidencePersistenceState(
    val evidenceBlocks: List<LlmEvidenceBlockEntity>,
    val citationCandidates: List<LlmCitationEvidenceCandidate>,
)

internal fun buildEvidencePersistence(
    contextItems: List<LlmContextItem>,
    contextRefs: List<LlmContextRefEntity>,
    toolCalls: List<LlmToolCallEntity> = emptyList(),
    createdAt: Long = System.currentTimeMillis(),
    idFactory: () -> String = { UUID.randomUUID().toString() },
): LlmEvidencePersistenceState {
    val contextRefByContextId = contextRefs.associateBy(LlmContextRefEntity::contextId)
    val articleHtmlByArticleId =
        contextItems
            .asSequence()
            .filter { it.type == LlmContextType.ARTICLE }
            .mapNotNull { item ->
                item.internalArticleId?.trim()?.ifBlank { null }?.let { it to item.content }
            }
            .toMap()
    val entities = mutableListOf<LlmEvidenceBlockEntity>()
    val candidates = mutableListOf<LlmCitationEvidenceCandidate>()

    contextItems
        .filter { it.evidenceBlocks.isNotEmpty() }
        .forEach { item ->
            val contextRef = requireNotNull(contextRefByContextId[item.id]) {
                "Evidence is missing ContextRef: ${item.id}"
            }
            val parsed =
                when (item.type) {
                    LlmContextType.ARTICLE ->
                        buildArticleEvidenceBlocks(
                            html = item.content,
                            source =
                                LlmArticleEvidenceSource(
                                    articleId = item.internalArticleId,
                                    sourceUrl = item.sourceId,
                                ),
                        )
                    LlmContextType.SELECTED_TEXT ->
                        listOfNotNull(
                            buildSelectionEvidenceBlock(
                                content = item.content,
                                source =
                                    LlmArticleEvidenceSource(
                                        articleId = item.internalArticleId,
                                        sourceUrl = item.sourceId,
                                    ),
                                articleHtml =
                                    item.internalArticleId
                                        ?.trim()
                                        ?.ifBlank { null }
                                        ?.let(articleHtmlByArticleId::get),
                            )
                        )
                    LlmContextType.WEB_SEARCH_RESULT ->
                        listOfNotNull(
                            buildWebSearchEvidenceBlock(
                                content = item.content,
                                sourceUrl = item.sourceId,
                                blockIndex = item.sourceOrdinal,
                            )
                        )
                    LlmContextType.TOOL_RESULT ->
                        listOfNotNull(
                            buildToolResultEvidenceBlock(
                                content = item.content,
                                source =
                                    LlmToolEvidenceSource(
                                        toolCallId = item.toolCallId,
                                        toolId = item.toolId,
                                        toolName = item.toolName ?: item.title,
                                        toolSourceId = item.toolSourceId,
                                        sourceUrl = contextRef.sourceUrl,
                                    ),
                            )
                        )
                    LlmContextType.ARTICLE_SUMMARY,
                    LlmContextType.ARTICLE_TRANSLATION,
                    LlmContextType.MANUAL -> emptyList()
                }
            require(parsed.map { it.stableLocatorKey } == item.evidenceBlocks.map { it.stableLocatorKey }) {
                "Evidence identities changed between Context and persistence: ${item.id}"
            }
            parsed.forEach { block ->
                appendEvidenceCandidate(
                    contextId = item.id,
                    contextRef = contextRef,
                    block = block,
                    createdAt = createdAt,
                    idFactory = idFactory,
                    entities = entities,
                    candidates = candidates,
                )
            }
        }

    toolCalls.forEach { call ->
        if (call.status != LlmToolCallStatus.COMPLETE || call.resultContent.isNullOrBlank()) return@forEach
        val contextId = "tool-result:${call.id}"
        val contextRef = contextRefByContextId[contextId] ?: return@forEach
        val block =
            buildToolResultEvidenceBlock(
                content = call.resultContent,
                source =
                    LlmToolEvidenceSource(
                        toolCallId = call.id,
                        toolId = call.toolId,
                        toolName = call.toolName ?: call.apiName,
                        toolSourceId = call.toolSourceId,
                        sourceUrl = contextRef.sourceUrl,
                    ),
                stableLocatorKeyOverride =
                    "TOOL_RESULT:${call.id}:${contextRef.contentSha256.take(20)}",
            ) ?: return@forEach
        appendEvidenceCandidate(
            contextId = contextId,
            contextRef = contextRef,
            block = block,
            createdAt = createdAt,
            idFactory = idFactory,
            entities = entities,
            candidates = candidates,
        )
    }
    return LlmEvidencePersistenceState(entities, candidates)
}

private fun appendEvidenceCandidate(
    contextId: String,
    contextRef: LlmContextRefEntity,
    block: BuiltLlmEvidenceBlock,
    createdAt: Long,
    idFactory: () -> String,
    entities: MutableList<LlmEvidenceBlockEntity>,
    candidates: MutableList<LlmCitationEvidenceCandidate>,
) {
    val entity =
        block.toEntity(
            id = idFactory(),
            contextRefId = contextRef.id,
            createdAt = createdAt,
        )
    entities += entity
    candidates +=
        LlmCitationEvidenceCandidate(
            contextId = contextId,
            stableLocatorKey = entity.stableLocatorKey,
            contextRefId = contextRef.id,
            evidenceBlockId = entity.id,
            targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
            quoteSnapshot = entity.textSnapshot,
            sourceUrl = entity.locator.sourceUrl ?: contextRef.sourceUrl,
            locatorSnapshot = entity.locator,
        )
}

/** Assign short E1/E2 IDs only after Context Budget has selected complete Evidence blocks. */
internal fun prepareCitationProtocol(
    composed: ComposedLlmContext,
    candidates: List<LlmCitationEvidenceCandidate>,
    includedHistoryContextIds: List<String> = emptyList(),
): LlmCitationReadyContext {
    val byRequestIdentity = linkedMapOf<String, LlmCitationEvidenceCandidate>()
    candidates.forEach { candidate ->
        val contextId = candidate.contextId
        val key = candidate.stableLocatorKey.trim()
        require(contextId.isNotBlank()) { "Citation evidence contextId must not be blank" }
        require(key.isNotBlank()) { "Citation evidence stableLocatorKey must not be blank" }
        val requestIdentity = llmEvidenceRequestIdentity(contextId, key)
        require(requestIdentity !in byRequestIdentity) {
            "Citation evidence identity duplicated: $contextId / $key"
        }
        byRequestIdentity[requestIdentity] = candidate.copy(stableLocatorKey = key)
    }

    val composedIdentities =
        composed.renderedItems.flatMap { item ->
            item.evidenceBlockKeys.map { key -> llmEvidenceRequestIdentity(item.id, key) }
        }
    val candidatesByContextId = candidates.groupBy(LlmCitationEvidenceCandidate::contextId)
    val historyIdentities =
        includedHistoryContextIds.flatMap { contextId ->
            candidatesByContextId[contextId].orEmpty().map { candidate ->
                llmEvidenceRequestIdentity(candidate.contextId, candidate.stableLocatorKey)
            }
        }
    val includedIdentities = composedIdentities + historyIdentities
    val seenIncluded = mutableSetOf<String>()
    val protocolEntries = mutableListOf<LlmCitationProtocolEntry>()
    includedIdentities.forEach { requestIdentity ->
        if (!seenIncluded.add(requestIdentity)) return@forEach
        val candidate = requireNotNull(byRequestIdentity[requestIdentity]) {
            "Prompt Evidence block is missing Citation metadata: $requestIdentity"
        }
        protocolEntries +=
            LlmCitationProtocolEntry(
                contextId = candidate.contextId,
                stableLocatorKey = candidate.stableLocatorKey,
                contextRefId = candidate.contextRefId,
                evidenceBlockId = candidate.evidenceBlockId,
                targetKind = candidate.targetKind,
                quoteSnapshot = candidate.quoteSnapshot,
                sourceUrl = candidate.sourceUrl,
                locatorSnapshot = candidate.locatorSnapshot,
                protocolId = "E${protocolEntries.size + 1}",
            )
    }

    var text = composed.text
    protocolEntries.forEach { entry ->
        val requestIdentity = llmEvidenceRequestIdentity(entry.contextId, entry.stableLocatorKey)
        text =
            text.replace(
                oldValue = "[ORIGREAD_EVIDENCE id=${quoteAttribute(requestIdentity)}]",
                newValue = "[ORIGREAD_EVIDENCE id=${quoteAttribute(entry.protocolId)}]",
            )
    }
    val instruction =
        if (protocolEntries.isEmpty()) {
            ""
        } else {
            listOf(
                "Evidence citation protocol:",
                "- Cite only evidence IDs present in ORIGREAD_EVIDENCE blocks.",
                "- Use the exact token [[E1]], [[E2]], etc. immediately after the supported claim.",
                "- Never invent an evidence ID and never cite context that was not included.",
            ).joinToString("\n")
        }
    return LlmCitationReadyContext(
        text = text,
        protocolEntries = protocolEntries,
        instruction = instruction,
    )
}

internal fun resolveAssistantCitationTokens(
    assistantText: String,
    allowedEntries: List<LlmCitationProtocolEntry>,
): LlmResolvedAssistantCitations {
    val allowed = allowedEntries.mapTo(mutableSetOf(), LlmCitationProtocolEntry::protocolId)
    val valid = mutableListOf<String>()
    val invalid = mutableListOf<String>()
    val seenValid = mutableSetOf<String>()
    val seenInvalid = mutableSetOf<String>()
    CITATION_TOKEN_REGEX.findAll(assistantText).forEach { match ->
        val protocolId = match.groupValues[1]
        if (protocolId in allowed) {
            if (seenValid.add(protocolId)) valid += protocolId
        } else if (seenInvalid.add(protocolId)) {
            invalid += protocolId
        }
    }
    return LlmResolvedAssistantCitations(valid, invalid)
}

internal fun buildCitationRefsFromAssistantOutput(
    assistantText: String,
    allowedEntries: List<LlmCitationProtocolEntry>,
    conversationId: String,
    assistantMessageId: String,
    createdAt: Long = System.currentTimeMillis(),
    idFactory: () -> String = { UUID.randomUUID().toString() },
): LlmBuiltCitationRefs {
    val resolved = resolveAssistantCitationTokens(assistantText, allowedEntries)
    val byProtocolId = allowedEntries.associateBy(LlmCitationProtocolEntry::protocolId)
    val refs =
        resolved.validProtocolIds.mapIndexed { index, protocolId ->
            val entry = requireNotNull(byProtocolId[protocolId])
            LlmCitationRefEntity(
                id = idFactory(),
                conversationId = conversationId,
                assistantMessageId = assistantMessageId,
                contextRefId = entry.contextRefId,
                evidenceBlockId = entry.evidenceBlockId,
                targetKind = entry.targetKind,
                protocolId = protocolId,
                displayOrder = index + 1,
                quoteSnapshot = entry.quoteSnapshot,
                sourceUrl = entry.sourceUrl,
                locatorSnapshot = entry.locatorSnapshot,
                schemaVersion = LLM_CITATION_SCHEMA_VERSION,
                createdAt = createdAt,
            )
        }
    return LlmBuiltCitationRefs(refs, resolved.invalidProtocolIds)
}

/** Historical E IDs are request-local transport tokens and must never enter a later Provider request. */
internal fun stripHistoricalCitationProtocolTokens(content: String): String =
    content
        .replace(HISTORICAL_CITATION_TOKEN_REGEX, "")
        .replace(PUNCTUATION_SPACE_REGEX, "$1")

internal fun wrapCitationEvidenceContent(
    content: String,
    protocolId: String,
): String {
    require(CITATION_PROTOCOL_ID_REGEX.matches(protocolId)) { "Invalid Citation protocol ID: $protocolId" }
    return "[ORIGREAD_EVIDENCE id=${quoteAttribute(protocolId)}]\n$content\n[/ORIGREAD_EVIDENCE]"
}

private fun quoteAttribute(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

private val CITATION_TOKEN_REGEX = Regex("""\[\[(E\d+)]]""")
private val CITATION_PROTOCOL_ID_REGEX = Regex("^E\\d+$")
private val HISTORICAL_CITATION_TOKEN_REGEX = Regex("""\s*\[\[E\d+]]""")
private val PUNCTUATION_SPACE_REGEX = Regex("""[ \t]+([,.;:!?，。；：！？])""")
