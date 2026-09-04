package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LLM_EVIDENCE_CITATION_ENABLED
import me.ash.reader.llm.chat.data.LlmCitationRefEntity
import me.ash.reader.llm.chat.data.LlmEvidenceSourceKind
import me.ash.reader.llm.chat.data.stripDisabledLlmCitationTokens
import me.ash.reader.ui.component.reader.ReaderEvidenceMarker
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerSnapshot

internal data class LlmAssistantCitationDisplay(
    val markdown: String,
    val refsByDisplayOrder: Map<Int, LlmCitationRefEntity>,
) {
    val validDisplayOrders: Set<Int> = refsByDisplayOrder.keys
}

/**
 * Project request-local [[E#]] protocol tokens into one Assistant message's persisted [1]/[2]
 * display order. protocolId and displayOrder are intentionally treated as different identities.
 */
internal fun projectLlmAssistantCitationDisplay(
    assistantMessageId: String,
    content: String,
    citationRefs: List<LlmCitationRefEntity>,
    citationFeatureEnabled: Boolean = LLM_EVIDENCE_CITATION_ENABLED,
    preserveStreamingCitationLayout: Boolean = false,
): LlmAssistantCitationDisplay {
    if (!citationFeatureEnabled) {
        return LlmAssistantCitationDisplay(
            markdown = stripDisabledLlmCitationTokens(content, citationFeatureEnabled = false),
            refsByDisplayOrder = emptyMap(),
        )
    }

    val scoped = citationRefs.filter { it.assistantMessageId == assistantMessageId }
    val protocolGroups = scoped.groupBy { it.protocolId }
    val displayGroups = scoped.filter { (it.displayOrder ?: 0) > 0 }.groupBy { it.displayOrder!! }
    val validRefs =
        scoped.filter { ref ->
            val displayOrder = ref.displayOrder ?: return@filter false
            displayOrder > 0 &&
                NEW_CITATION_PROTOCOL_ID_REGEX.matches(ref.protocolId) &&
                protocolGroups[ref.protocolId]?.size == 1 &&
                displayGroups[displayOrder]?.size == 1
        }
    val byProtocolId = validRefs.associateBy(LlmCitationRefEntity::protocolId)
    val byDisplayOrder = validRefs.associateBy { requireNotNull(it.displayOrder) }.toSortedMap()
    val provisionalDisplayOrders =
        if (preserveStreamingCitationLayout) {
            linkedMapOf<String, Int>().apply {
                NEW_CITATION_PROTOCOL_TOKEN_REGEX.findAll(content).forEach { match ->
                    val protocolId = match.groupValues[1]
                    if (protocolId !in this) this[protocolId] = size + 1
                }
            }
        } else {
            emptyMap()
        }

    val projected =
        content
            .replace(NEW_CITATION_PROTOCOL_TOKEN_REGEX) { match ->
                val protocolId = match.groupValues[1]
                val displayOrder =
                    byProtocolId[protocolId]?.displayOrder ?: provisionalDisplayOrders[protocolId]
                displayOrder?.let { "[$it]" }.orEmpty()
            }
            // Legacy whole-context [R#] never becomes a precise Evidence Citation.
            .replace(LEGACY_CITATION_TOKEN_REGEX, "")
            .replace(MULTI_INLINE_SPACE_REGEX, " ")
            .replace(PUNCTUATION_SPACE_REGEX, "$1")

    return LlmAssistantCitationDisplay(
        markdown = projected,
        refsByDisplayOrder = byDisplayOrder,
    )
}

internal fun buildLlmReaderMarkerSnapshot(
    ownerArticleId: String,
    conversationId: String,
    assistantMessageId: String,
    citationRefs: List<LlmCitationRefEntity>,
    citationFeatureEnabled: Boolean = LLM_EVIDENCE_CITATION_ENABLED,
): ReaderEvidenceMarkerSnapshot? {
    if (
        !citationFeatureEnabled ||
            ownerArticleId.isBlank() ||
            conversationId.isBlank() ||
            assistantMessageId.isBlank()
    ) {
        return null
    }
    val display =
        projectLlmAssistantCitationDisplay(
            assistantMessageId = assistantMessageId,
            content = "",
            citationRefs = citationRefs,
            citationFeatureEnabled = true,
        )
    val markers =
        display.refsByDisplayOrder.mapNotNull { (displayOrder, ref) ->
            val locator = ref.locatorSnapshot ?: return@mapNotNull null
            if (
                locator.sourceKind != LlmEvidenceSourceKind.ARTICLE &&
                    locator.sourceKind != LlmEvidenceSourceKind.SELECTION
            ) {
                return@mapNotNull null
            }
            val stableKey = locator.stableLocatorKey?.trim()?.ifBlank { null } ?: return@mapNotNull null
            ReaderEvidenceMarker(
                citationId = ref.id,
                stableLocatorKey = stableKey,
                displayOrder = displayOrder,
                articleId = locator.articleId?.trim()?.ifBlank { null },
            )
        }
    return markers.takeIf(List<ReaderEvidenceMarker>::isNotEmpty)?.let { markerList ->
        ReaderEvidenceMarkerSnapshot(
            ownerArticleId = ownerArticleId.trim(),
            conversationId = conversationId.trim(),
            assistantMessageId = assistantMessageId,
            markers = markerList,
        )
    }
}

private val NEW_CITATION_PROTOCOL_ID_REGEX = Regex("^E\\d+$")
private val NEW_CITATION_PROTOCOL_TOKEN_REGEX = Regex("""\[\[(E\d+)]]""")
private val LEGACY_CITATION_TOKEN_REGEX = Regex("[ \\t]*\\[R\\d+]", RegexOption.IGNORE_CASE)
private val MULTI_INLINE_SPACE_REGEX = Regex("[ \\t]{2,}")
private val PUNCTUATION_SPACE_REGEX = Regex("""[ \t]+([,.;:!?，。；：！？])""")
