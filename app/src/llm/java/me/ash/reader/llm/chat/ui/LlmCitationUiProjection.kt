package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LLM_EVIDENCE_CITATION_ENABLED
import me.ash.reader.llm.chat.data.LlmCitationNavigationAction
import me.ash.reader.llm.chat.data.LlmCitationRefEntity
import me.ash.reader.llm.chat.data.LlmCitationAnnotationWithRefs
import me.ash.reader.llm.chat.data.CitationTransportParser
import me.ash.reader.llm.chat.data.LlmEvidenceSourceKind
import me.ash.reader.llm.chat.data.resolveCitationNavigationAction
import me.ash.reader.llm.chat.data.stripDisabledLlmCitationTokens
import me.ash.reader.ui.component.reader.ReaderEvidenceMarker
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerLayerOrigin
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

internal fun shouldReplaceWithHistoricalCitationLayer(
    currentSnapshot: ReaderEvidenceMarkerSnapshot?,
): Boolean =
    currentSnapshot == null ||
        currentSnapshot.origin == ReaderEvidenceMarkerLayerOrigin.HISTORICAL

internal fun LlmAssistantCitationGroup.directNavigationRefOrNull(): LlmCitationRefEntity? {
    val actions = refs.map(LlmCitationRefEntity::resolveCitationNavigationAction)
    if (actions.all { it is LlmCitationNavigationAction.Reader }) {
        val destinations =
            actions.map { action ->
                val target = (action as LlmCitationNavigationAction.Reader).target
                listOf(
                    target.articleId?.trim().orEmpty(),
                    target.stableLocatorKey?.trim().orEmpty(),
                    target.normalizedHash?.trim().orEmpty(),
                    target.headingPath.joinToString("\u001F"),
                ).joinToString("\u001E")
            }.toSet()
        // 多来源 occurrence 只有在全部 ref 收敛到同一精确 Reader destination 时才允许直达。
        return representativeRef.takeIf { destinations.size == 1 }
    }
    if (actions.all { it is LlmCitationNavigationAction.ExternalUrl }) {
        val urls = actions.map { (it as LlmCitationNavigationAction.ExternalUrl).url }.toSet()
        return representativeRef.takeIf { urls.size == 1 }
    }
    return null
}

internal const val MAX_INLINE_CITATION_GROUPS = 20

/**
 * Project request-local [[E#]] protocol tokens into one Assistant message's transient UI Citation
 * groups. Persisted protocolId/displayOrder remain transport/audit identities and are deliberately
 * decoupled from the completed UI numbering used by Chat and Reader markers.
 */
internal fun projectLlmAssistantCitationDisplay(
    assistantMessageId: String,
    content: String,
    citationRefs: List<LlmCitationRefEntity>,
    citationAnnotations: List<LlmCitationAnnotationWithRefs> = emptyList(),
    citationFeatureEnabled: Boolean = LLM_EVIDENCE_CITATION_ENABLED,
    preserveStreamingCitationLayout: Boolean = false,
): LlmAssistantCitationDisplay {
    if (!citationFeatureEnabled) {
        return LlmAssistantCitationDisplay(
            markdown = stripDisabledLlmCitationTokens(content, citationFeatureEnabled = false),
            groupsByDisplayOrder = emptyMap(),
        )
    }

    val structured =
        citationAnnotations
            .filter { it.annotation.assistantMessageId == assistantMessageId }
            .sortedWith(compareBy({ it.annotation.occurrenceOrdinal }, { it.annotation.id }))
    if (structured.isNotEmpty()) {
        return projectStructuredCitationDisplay(content, structured)
    }

    // v15 及更早历史没有持久化 occurrence；也必须复用唯一 Parser 临时恢复 canonical 投影，
    // 禁止旧严格 Regex 让 malformed/半截 transport 在历史消息中重新泄漏。
    val legacyRefs =
        citationRefs
            .filter { it.assistantMessageId == assistantMessageId }
            .distinctBy(LlmCitationRefEntity::protocolId)
    val duplicateDisplayOrders =
        legacyRefs
            .filter { (it.displayOrder ?: 0) > 0 }
            .groupBy { it.displayOrder }
            .filterValues { it.size > 1 }
            .keys
    val validLegacyRefs = legacyRefs.filter { it.displayOrder !in duplicateDisplayOrders }
    val legacyByProtocolId = validLegacyRefs.associateBy(LlmCitationRefEntity::protocolId)
    val parserAllowedIds =
        if (preserveStreamingCitationLayout && legacyRefs.isEmpty()) {
            extractStreamingCitationProtocolIds(content)
        } else {
            legacyByProtocolId.keys
        }
    val parsedLegacy =
        CitationTransportParser.parse(
            transportText = content,
            allowedProtocolIds = parserAllowedIds,
            final = !preserveStreamingCitationLayout,
        )
    val legacyOccurrences =
        parsedLegacy.annotations.map { annotation ->
            LegacyCitationOccurrence(
                offset = annotation.canonicalInsertionOffset,
                protocolIds = annotation.protocolIds,
                refs = annotation.protocolIds.mapNotNull(legacyByProtocolId::get),
            )
        }
    return projectParsedCitationDisplay(
        canonicalText = parsedLegacy.canonicalText,
        occurrences = normalizeLegacyCitationOccurrences(legacyOccurrences),
        allowProvisionalGroups = preserveStreamingCitationLayout,
    )


}

/** 兼容历史 transport 的临时 occurrence 投影；不重新建立第二套 Citation 解析规则。 */
private fun projectParsedCitationDisplay(
    canonicalText: String,
    occurrences: List<LegacyCitationOccurrence>,
    allowProvisionalGroups: Boolean,
): LlmAssistantCitationDisplay {
    val navigable = occurrences.filter { it.refs.isNotEmpty() }
    val visibleNavigable = selectVisibleLegacyOccurrences(navigable)
    val visible =
        if (allowProvisionalGroups && navigable.isEmpty()) occurrences.take(MAX_INLINE_CITATION_GROUPS)
        else visibleNavigable
    val orderBySignature = linkedMapOf<String, Int>()
    visible.forEach { occurrence ->
        val signature = occurrence.protocolIds.sorted().joinToString("|")
        if (signature !in orderBySignature) orderBySignature[signature] = orderBySignature.size + 1
    }
    val groups =
        visible.mapNotNull { occurrence ->
            val refs = occurrence.refs
            if (refs.isEmpty()) return@mapNotNull null
            val order = orderBySignature.getValue(occurrence.protocolIds.sorted().joinToString("|"))
            order to LlmAssistantCitationGroup(order, refs.distinctBy(LlmCitationRefEntity::id))
        }.distinctBy { it.first }.toMap(linkedMapOf())
    val projected = buildString(canonicalText.length + visible.size * 4) {
        var cursor = 0
        visible.forEach { occurrence ->
            val offset = occurrence.offset.coerceIn(cursor, canonicalText.length)
            append(canonicalText, cursor, offset)
            val anotherMarkerAtSameOffset = cursor == offset && lastOrNull() == ']'
            if (!anotherMarkerAtSameOffset && lastOrNull()?.isWhitespace() != true) append(' ')
            val order = orderBySignature.getValue(occurrence.protocolIds.sorted().joinToString("|"))
            append("[$order]")
            cursor = offset
        }
        append(canonicalText, cursor, canonicalText.length)
    }.replace(MULTI_INLINE_SPACE_REGEX, " ")
    return LlmAssistantCitationDisplay(projected, groups)
}

private data class LegacyCitationOccurrence(
    val offset: Int,
    val protocolIds: List<String>,
    val refs: List<LlmCitationRefEntity>,
)

/** 相邻 token 只在精确 destination 相同时合并；不同来源/段落保持独立 occurrence。 */
private fun normalizeLegacyCitationOccurrences(
    occurrences: List<LegacyCitationOccurrence>,
): List<LegacyCitationOccurrence> {
    val normalized = mutableListOf<LegacyCitationOccurrence>()
    occurrences.forEach { occurrence ->
        val previous = normalized.lastOrNull()
        val sameOffset = previous?.offset == occurrence.offset
        val previousTargets = previous?.refs?.mapNotNull(::citationUiMergeTargetKey)?.toSet().orEmpty()
        val currentTargets = occurrence.refs.mapNotNull(::citationUiMergeTargetKey).toSet()
        if (
            sameOffset &&
                previousTargets.size == 1 &&
                currentTargets.size == 1 &&
                previousTargets == currentTargets
        ) {
            normalized[normalized.lastIndex] =
                previous!!.copy(
                    protocolIds = (previous.protocolIds + occurrence.protocolIds).distinct(),
                    refs = (previous.refs + occurrence.refs).distinctBy(LlmCitationRefEntity::id),
                )
        } else {
            normalized += occurrence
        }
    }
    return normalized
}

private fun selectVisibleLegacyOccurrences(
    occurrences: List<LegacyCitationOccurrence>,
): List<LegacyCitationOccurrence> {
    if (occurrences.size <= MAX_INLINE_CITATION_GROUPS) return occurrences
    val selected = linkedSetOf<LegacyCitationOccurrence>()
    val coveredSources = mutableSetOf<String>()
    occurrences.forEach { occurrence ->
        if (selected.size >= MAX_INLINE_CITATION_GROUPS) return@forEach
        val sources = occurrence.refs.map(::citationSourceKey).toSet()
        if (sources.any { it !in coveredSources }) {
            selected += occurrence
            coveredSources += sources
        }
    }
    occurrences.forEach { if (selected.size < MAX_INLINE_CITATION_GROUPS) selected += it }
    return occurrences.filter(selected::contains)
}

/** Streaming 尚未持久化 allowed refs 时，仅提取形状合法的 provisional IDs 供占位编号。 */
private fun extractStreamingCitationProtocolIds(content: String): Set<String> =
    Regex("""E\d+""").findAll(content).mapTo(linkedSetOf()) { it.value }

/** Parser 删除 transport 后保留既有可读分隔；Citation 前原有空格不能被 canonical trim 吞掉。 */
private val MULTI_INLINE_SPACE_REGEX = Regex("[ \\t]{2,}")

private fun citationUiMergeTargetKey(ref: LlmCitationRefEntity): String? =
    when (val action = ref.resolveCitationNavigationAction()) {
        is LlmCitationNavigationAction.Reader -> {
            val articleId = action.target.articleId?.trim()?.ifBlank { null } ?: return null
            val stableKey = action.target.stableLocatorKey?.trim()?.ifBlank { null } ?: return null
            "reader:$articleId:$stableKey"
        }
        is LlmCitationNavigationAction.ExternalUrl ->
            normalizedCitationUrl(action.url)?.let { "url:$it" }
        LlmCitationNavigationAction.SourcesDetail -> null
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

/** 从 canonical occurrence 投影 UI marker；正文不再包含任何 transport token。 */
private fun projectStructuredCitationDisplay(
    content: String,
    annotations: List<LlmCitationAnnotationWithRefs>,
): LlmAssistantCitationDisplay {
    val logical =
        annotations
            .mapNotNull { occurrence ->
                occurrence.refs
                    .takeIf(List<LlmCitationRefEntity>::isNotEmpty)
                    ?.distinctBy(LlmCitationRefEntity::id)
                    ?.let { refs -> occurrence to refs }
            }
    val visible = selectVisibleStructuredOccurrences(logical)
    val groups =
        visible.mapIndexed { index, (_, refs) ->
            val order = index + 1
            order to LlmAssistantCitationGroup(displayOrder = order, refs = refs)
        }.toMap(linkedMapOf())
    val markerByAnnotationId =
        visible.mapIndexed { index, (occurrence, _) -> occurrence.annotation.id to "[${index + 1}]" }.toMap()
    val projected = buildString(content.length + markerByAnnotationId.size * 4) {
        var cursor = 0
        annotations.forEach { occurrence ->
            val offset = occurrence.annotation.canonicalInsertionOffset.coerceIn(cursor, content.length)
            append(content, cursor, offset)
            markerByAnnotationId[occurrence.annotation.id]?.let(::append)
            cursor = offset
        }
        append(content, cursor, content.length)
    }
    return LlmAssistantCitationDisplay(markdown = projected, groupsByDisplayOrder = groups)
}

/**
 * 大量 Citation 时优先保住不同来源，再按 canonical occurrence 顺序补满剩余名额。
 * UI 编号仍由最终可见 occurrence 的 canonical 顺序生成，不持久化 displayOrder。
 */
private fun selectVisibleStructuredOccurrences(
    occurrences: List<Pair<LlmCitationAnnotationWithRefs, List<LlmCitationRefEntity>>>,
): List<Pair<LlmCitationAnnotationWithRefs, List<LlmCitationRefEntity>>> {
    if (occurrences.size <= MAX_INLINE_CITATION_GROUPS) return occurrences
    val selectedAnnotationIds = linkedSetOf<String>()
    val coveredSources = mutableSetOf<String>()
    occurrences.forEach { (occurrence, refs) ->
        if (selectedAnnotationIds.size >= MAX_INLINE_CITATION_GROUPS) return@forEach
        val sources = refs.map(::citationSourceKey).toSet()
        if (sources.any { it !in coveredSources }) {
            selectedAnnotationIds += occurrence.annotation.id
            coveredSources += sources
        }
    }
    occurrences.forEach { (occurrence, _) ->
        if (selectedAnnotationIds.size < MAX_INLINE_CITATION_GROUPS) {
            selectedAnnotationIds += occurrence.annotation.id
        }
    }
    return occurrences.filter { (occurrence, _) -> occurrence.annotation.id in selectedAnnotationIds }
}

internal fun buildLlmReaderMarkerSnapshot(
    ownerArticleId: String,
    conversationId: String,
    assistantMessageId: String,
    citationRefs: List<LlmCitationRefEntity>,
    citationAnnotations: List<LlmCitationAnnotationWithRefs> = emptyList(),
    assistantContent: String? = null,
    origin: ReaderEvidenceMarkerLayerOrigin = ReaderEvidenceMarkerLayerOrigin.INTERACTION,
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
            citationAnnotations = citationAnnotations,
            citationFeatureEnabled = true,
        )
    val structuredVisibleAnnotationIdsByDisplayOrder =
        citationAnnotations
            .filter { it.annotation.assistantMessageId == assistantMessageId }
            .sortedWith(compareBy({ it.annotation.occurrenceOrdinal }, { it.annotation.id }))
            .mapNotNull { occurrence ->
                occurrence.refs
                    .takeIf(List<LlmCitationRefEntity>::isNotEmpty)
                    ?.distinctBy(LlmCitationRefEntity::id)
                    ?.let { refs -> occurrence to refs }
            }
            .let(::selectVisibleStructuredOccurrences)
            .mapIndexed { index, (occurrence, _) -> index + 1 to occurrence.annotation.id }
            .toMap()
    val markers =
        display.groupsByDisplayOrder.flatMap { (displayOrder, group) ->
            val annotationId = structuredVisibleAnnotationIdsByDisplayOrder[displayOrder]
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
                .groupBy { (articleId, stableKey, _) -> articleId to stableKey }
                .map { (_, refsForLocator) ->
                    val (articleId, stableKey, ref) = refsForLocator.last()
                    ReaderEvidenceMarker(
                        citationId = ref.id,
                        annotationId = annotationId,
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
            origin = origin,
        )
    }
}
