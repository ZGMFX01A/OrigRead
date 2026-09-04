package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LLM_EVIDENCE_CITATION_ENABLED
import me.ash.reader.llm.chat.data.LlmCitationNavigationAction
import me.ash.reader.llm.chat.data.LlmCitationRefEntity
import me.ash.reader.llm.chat.data.LlmEvidenceSourceKind
import me.ash.reader.llm.chat.data.resolveCitationNavigationAction
import me.ash.reader.llm.chat.data.stripDisabledLlmCitationTokens
import me.ash.reader.ui.component.reader.ReaderEvidenceMarker
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerSnapshot

internal data class LlmAssistantCitationDisplay(
    val markdown: String,
    val groupsByDisplayOrder: Map<Int, LlmAssistantCitationGroup>,
) {
    val validDisplayOrders: Set<Int> = groupsByDisplayOrder.keys
    val refsByDisplayOrder: Map<Int, LlmCitationRefEntity> =
        groupsByDisplayOrder.mapValues { (_, group) -> group.representativeRef }
}

internal data class LlmAssistantCitationGroup(
    val displayOrder: Int,
    val refs: List<LlmCitationRefEntity>,
) {
    init {
        require(displayOrder > 0) { "Citation UI group order must be positive" }
        require(refs.isNotEmpty()) { "Citation UI group must contain at least one persisted CitationRef" }
    }

    val representativeRef: LlmCitationRefEntity
        get() = refs.last()
}

internal fun LlmAssistantCitationGroup.directNavigationRefOrNull(): LlmCitationRefEntity? {
    val actions = refs.map(LlmCitationRefEntity::resolveCitationNavigationAction)
    if (actions.all { it is LlmCitationNavigationAction.Reader }) {
        val articleIds =
            actions.mapNotNull { action ->
                (action as? LlmCitationNavigationAction.Reader)
                    ?.target
                    ?.articleId
                    ?.trim()
                    ?.ifBlank { null }
            }.toSet()
        return representativeRef.takeIf { articleIds.size == 1 }
    }
    if (actions.all { it is LlmCitationNavigationAction.ExternalUrl }) {
        val urls = actions.map { (it as LlmCitationNavigationAction.ExternalUrl).url }.toSet()
        return representativeRef.takeIf { urls.size == 1 }
    }
    return null
}

private data class LlmCitationOccurrence(
    val start: Int,
    val endExclusive: Int,
    val ref: LlmCitationRefEntity,
)

private data class LlmCitationOccurrenceGroup(
    val occurrences: List<LlmCitationOccurrence>,
) {
    val refs: List<LlmCitationRefEntity> =
        occurrences.map(LlmCitationOccurrence::ref).distinctBy(LlmCitationRefEntity::id)
    val signature: String = refs.map(LlmCitationRefEntity::id).sorted().joinToString("|")
    val sourceKeys: Set<String> = refs.mapTo(linkedSetOf(), ::citationSourceKey)
    val firstStart: Int = occurrences.first().start
}

private data class LlmCitationReplacement(
    val start: Int,
    val endExclusive: Int,
    val text: String,
)

internal const val MAX_INLINE_CITATION_GROUPS = 20
private const val MAX_SAME_SOURCE_CLAIM_GAP_CHARS = 120
private const val MAX_SAME_SOURCE_GROUP_SPAN_CHARS = 220

/**
 * Project request-local [[E#]] protocol tokens into one Assistant message's transient UI Citation
 * groups. Persisted protocolId/displayOrder remain transport/audit identities and are deliberately
 * decoupled from the completed UI numbering used by Chat and Reader markers.
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
            groupsByDisplayOrder = emptyMap(),
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

    if (preserveStreamingCitationLayout) {
        val projected =
            content
                .replace(NEW_CITATION_PROTOCOL_TOKEN_REGEX) { match ->
                    val protocolId = match.groupValues[1]
                    val displayOrder =
                        byProtocolId[protocolId]?.displayOrder ?: provisionalDisplayOrders[protocolId]
                    displayOrder?.let { "[$it]" }.orEmpty()
                }
                .replace(LEGACY_CITATION_TOKEN_REGEX, "")
                .replace(MULTI_INLINE_SPACE_REGEX, " ")
                .replace(PUNCTUATION_SPACE_REGEX, "$1")
        val persistedGroups =
            validRefs
                .sortedBy { it.displayOrder }
                .associate { ref ->
                    val order = requireNotNull(ref.displayOrder)
                    order to LlmAssistantCitationGroup(order, listOf(ref))
                }
        return LlmAssistantCitationDisplay(projected, persistedGroups)
    }

    val occurrenceGroups = buildCitationOccurrenceGroups(content, byProtocolId)
    val logicalGroups =
        occurrenceGroups
            .distinctBy(LlmCitationOccurrenceGroup::signature)
            .sortedBy(LlmCitationOccurrenceGroup::firstStart)
    val visibleSignatures = selectVisibleCitationGroupSignatures(logicalGroups)
    val visibleLogicalGroups = logicalGroups.filter { it.signature in visibleSignatures }
    val groupsBySignature =
        visibleLogicalGroups.mapIndexed { index, group ->
            group.signature to
                LlmAssistantCitationGroup(
                    displayOrder = index + 1,
                    refs = group.refs,
                )
        }.toMap()
    val groupsByDisplayOrder =
        groupsBySignature.values.associateBy(LlmAssistantCitationGroup::displayOrder).toSortedMap()

    val replacements = mutableListOf<LlmCitationReplacement>()
    val groupedTokenStarts = mutableSetOf<Int>()
    occurrenceGroups.forEach { occurrenceGroup ->
        occurrenceGroup.occurrences.forEach { groupedTokenStarts += it.start }
        val uiGroup = groupsBySignature[occurrenceGroup.signature]
        val occurrences = occurrenceGroup.occurrences
        if (occurrences.size > 1 && occurrenceGroup.isPureCitationCluster(content)) {
            replacements +=
                LlmCitationReplacement(
                    start = occurrences.first().start,
                    endExclusive = occurrences.last().endExclusive,
                    text = uiGroup?.let { "[${it.displayOrder}]" }.orEmpty(),
                )
        } else {
            occurrences.forEachIndexed { index, occurrence ->
                replacements +=
                    LlmCitationReplacement(
                        start = occurrence.start,
                        endExclusive = occurrence.endExclusive,
                        text =
                            if (index == occurrences.lastIndex) {
                                uiGroup?.let { "[${it.displayOrder}]" }.orEmpty()
                            } else {
                                ""
                            },
                    )
            }
        }
    }
    NEW_CITATION_PROTOCOL_TOKEN_REGEX.findAll(content).forEach { match ->
        if (match.range.first !in groupedTokenStarts) {
            replacements +=
                LlmCitationReplacement(
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    text = "",
                )
        }
    }

    val projected =
        applyCitationReplacements(content, replacements)
            // Legacy whole-context [R#] never becomes a precise Evidence Citation.
            .replace(LEGACY_CITATION_TOKEN_REGEX, "")
            .replace(MULTI_INLINE_SPACE_REGEX, " ")
            .replace(PUNCTUATION_SPACE_REGEX, "$1")

    return LlmAssistantCitationDisplay(
        markdown = projected,
        groupsByDisplayOrder = groupsByDisplayOrder,
    )
}

private fun buildCitationOccurrenceGroups(
    content: String,
    byProtocolId: Map<String, LlmCitationRefEntity>,
): List<LlmCitationOccurrenceGroup> {
    val occurrences =
        NEW_CITATION_PROTOCOL_TOKEN_REGEX.findAll(content).mapNotNull { match ->
            val ref = byProtocolId[match.groupValues[1]] ?: return@mapNotNull null
            LlmCitationOccurrence(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                ref = ref,
            )
        }.toList()
    if (occurrences.isEmpty()) return emptyList()

    val groups = mutableListOf<MutableList<LlmCitationOccurrence>>()
    occurrences.forEach { occurrence ->
        val current = groups.lastOrNull()
        if (
            current != null &&
                canMergeCitationOccurrence(
                    content = content,
                    group = current,
                    next = occurrence,
                )
        ) {
            current += occurrence
        } else {
            groups += mutableListOf(occurrence)
        }
    }
    return groups.map { LlmCitationOccurrenceGroup(it.toList()) }
}

private fun canMergeCitationOccurrence(
    content: String,
    group: List<LlmCitationOccurrence>,
    next: LlmCitationOccurrence,
): Boolean {
    val previous = group.last()
    val gap = content.substring(previous.endExclusive, next.start)
    if (gap.contains('\n')) return false
    if (citationSourceKey(previous.ref) != citationSourceKey(next.ref)) return false
    if (gap.none(Char::isLetterOrDigit)) return true
    if (next.start - group.first().endExclusive > MAX_SAME_SOURCE_GROUP_SPAN_CHARS) return false
    if (gap.length > MAX_SAME_SOURCE_CLAIM_GAP_CHARS) return false
    return citationEvidenceIsNearby(previous.ref, next.ref)
}

private fun citationEvidenceIsNearby(
    first: LlmCitationRefEntity,
    second: LlmCitationRefEntity,
): Boolean {
    if (first.id == second.id || first.evidenceBlockId == second.evidenceBlockId) return true
    val firstLocator = first.locatorSnapshot ?: return first.contextRefId == second.contextRefId
    val secondLocator = second.locatorSnapshot ?: return first.contextRefId == second.contextRefId
    val firstIndex = firstLocator.blockIndex
    val secondIndex = secondLocator.blockIndex
    return if (firstIndex != null && secondIndex != null) {
        kotlin.math.abs(firstIndex - secondIndex) <= 2
    } else {
        first.contextRefId == second.contextRefId
    }
}

private fun citationSourceKey(ref: LlmCitationRefEntity): String {
    val locator = ref.locatorSnapshot
    return when (locator?.sourceKind) {
        LlmEvidenceSourceKind.ARTICLE,
        LlmEvidenceSourceKind.SELECTION ->
            locator.articleId?.trim()?.ifBlank { null }?.let { "article:$it" }
                ?: normalizedCitationUrl(locator.sourceUrl ?: ref.sourceUrl)?.let { "url:$it" }
                ?: "context:${ref.contextRefId}"
        LlmEvidenceSourceKind.WEB_SEARCH ->
            normalizedCitationUrl(locator.sourceUrl ?: ref.sourceUrl)?.let { "web:$it" }
                ?: "context:${ref.contextRefId}"
        LlmEvidenceSourceKind.TOOL_RESULT ->
            locator.toolCallId?.trim()?.ifBlank { null }?.let { "tool-call:$it" }
                ?: locator.toolSourceId?.trim()?.ifBlank { null }?.let { "tool-source:$it" }
                ?: normalizedCitationUrl(locator.sourceUrl ?: ref.sourceUrl)?.let { "tool-url:$it" }
                ?: "context:${ref.contextRefId}"
        null -> "context:${ref.contextRefId}"
    }
}

private fun normalizedCitationUrl(value: String?): String? =
    value?.trim()?.ifBlank { null }?.substringBefore('#')?.trimEnd('/')

private fun selectVisibleCitationGroupSignatures(
    groups: List<LlmCitationOccurrenceGroup>,
    maxGroups: Int = MAX_INLINE_CITATION_GROUPS,
): Set<String> {
    if (groups.size <= maxGroups) return groups.mapTo(linkedSetOf(), LlmCitationOccurrenceGroup::signature)
    val selected = linkedSetOf<String>()
    val coveredSources = mutableSetOf<String>()
    groups.forEach { group ->
        if (selected.size >= maxGroups) return@forEach
        if (group.sourceKeys.any { it !in coveredSources }) {
            selected += group.signature
            coveredSources += group.sourceKeys
        }
    }
    groups.forEach { group ->
        if (selected.size >= maxGroups) return@forEach
        selected += group.signature
    }
    return selected
}

private fun LlmCitationOccurrenceGroup.isPureCitationCluster(content: String): Boolean =
    occurrences.zipWithNext().all { (first, second) ->
        val gap = content.substring(first.endExclusive, second.start)
        !gap.contains('\n') && gap.none(Char::isLetterOrDigit)
    }

private fun applyCitationReplacements(
    content: String,
    replacements: List<LlmCitationReplacement>,
): String {
    if (replacements.isEmpty()) return content
    val ordered = replacements.sortedBy(LlmCitationReplacement::start)
    return buildString(content.length) {
        var cursor = 0
        ordered.forEach { replacement ->
            if (replacement.start < cursor) return@forEach
            append(content, cursor, replacement.start)
            append(replacement.text)
            cursor = replacement.endExclusive
        }
        if (cursor < content.length) append(content, cursor, content.length)
    }
}

internal fun buildLlmReaderMarkerSnapshot(
    ownerArticleId: String,
    conversationId: String,
    assistantMessageId: String,
    citationRefs: List<LlmCitationRefEntity>,
    assistantContent: String? = null,
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
            content =
                assistantContent
                    ?: citationRefs
                        .filter { it.assistantMessageId == assistantMessageId }
                        .sortedBy { it.displayOrder }
                        .joinToString(" ") { "[[${it.protocolId}]]" },
            citationRefs = citationRefs,
            citationFeatureEnabled = true,
        )
    val markers =
        display.groupsByDisplayOrder.flatMap { (displayOrder, group) ->
            group.refs
                .asSequence()
                .mapNotNull { ref ->
                    val locator = ref.locatorSnapshot ?: return@mapNotNull null
                    if (
                        locator.sourceKind != LlmEvidenceSourceKind.ARTICLE &&
                            locator.sourceKind != LlmEvidenceSourceKind.SELECTION
                    ) {
                        return@mapNotNull null
                    }
                    val articleId = locator.articleId?.trim()?.ifBlank { null }
                    val stableKey = locator.stableLocatorKey?.trim()?.ifBlank { null } ?: return@mapNotNull null
                    Triple(articleId, stableKey, ref)
                }
                .groupBy { it.first }
                .map { (_, refsForArticle) ->
                    val (articleId, stableKey, ref) = refsForArticle.last()
                    ReaderEvidenceMarker(
                        citationId = ref.id,
                        stableLocatorKey = stableKey,
                        displayOrder = displayOrder,
                        articleId = articleId,
                    )
                }
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
