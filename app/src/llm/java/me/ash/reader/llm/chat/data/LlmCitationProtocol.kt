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

internal fun buildArticleEvidencePersistence(
    contextItems: List<LlmContextItem>,
    contextRefs: List<LlmContextRefEntity>,
    createdAt: Long = System.currentTimeMillis(),
    idFactory: () -> String = { UUID.randomUUID().toString() },
): LlmEvidencePersistenceState {
    val contextRefByContextId = contextRefs.associateBy(LlmContextRefEntity::contextId)
    val entities = mutableListOf<LlmEvidenceBlockEntity>()
    val candidates = mutableListOf<LlmCitationEvidenceCandidate>()

    contextItems
        .filter { it.type == LlmContextType.ARTICLE && it.evidenceBlocks.isNotEmpty() }
        .forEach { item ->
            val contextRef = requireNotNull(contextRefByContextId[item.id]) {
                "Article Evidence is missing ContextRef: ${item.id}"
            }
            val parsed =
                buildArticleEvidenceBlocks(
                    html = item.content,
                    source =
                        LlmArticleEvidenceSource(
                            articleId = item.internalArticleId,
                            sourceUrl = item.sourceId,
                        ),
                )
            require(parsed.map { it.stableLocatorKey } == item.evidenceBlocks.map { it.stableLocatorKey }) {
                "Article Evidence identities changed between Context and persistence: ${item.id}"
            }
            parsed.forEach { block ->
                val entity =
                    block.toEntity(
                        id = idFactory(),
                        contextRefId = contextRef.id,
                        createdAt = createdAt,
                    )
                entities += entity
                candidates +=
                    LlmCitationEvidenceCandidate(
                        contextId = item.id,
                        stableLocatorKey = entity.stableLocatorKey,
                        contextRefId = contextRef.id,
                        evidenceBlockId = entity.id,
                        targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
                        quoteSnapshot = entity.textSnapshot,
                        sourceUrl = entity.locator.sourceUrl ?: contextRef.sourceUrl,
                        locatorSnapshot = entity.locator,
                    )
            }
        }
    return LlmEvidencePersistenceState(entities, candidates)
}

/** Assign short E1/E2 IDs only after Context Budget has selected complete Evidence blocks. */
internal fun prepareCitationProtocol(
    composed: ComposedLlmContext,
    candidates: List<LlmCitationEvidenceCandidate>,
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

    val includedIdentities =
        composed.renderedItems.flatMap { item ->
            item.evidenceBlockKeys.map { key -> llmEvidenceRequestIdentity(item.id, key) }
        }
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

private fun quoteAttribute(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

private val CITATION_TOKEN_REGEX = Regex("""\[\[(E\d+)]]""")
private val HISTORICAL_CITATION_TOKEN_REGEX = Regex("""\s*\[\[E\d+]]""")
private val PUNCTUATION_SPACE_REGEX = Regex("""[ \t]+([,.;:!?，。；：！？])""")
