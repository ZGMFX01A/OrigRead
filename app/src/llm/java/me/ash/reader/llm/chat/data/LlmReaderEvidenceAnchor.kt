package me.ash.reader.llm.chat.data

import me.ash.reader.ui.component.reader.ReaderEvidenceAnchorTarget

internal sealed interface LlmCitationNavigationAction {
    data class Reader(
        val target: ReaderEvidenceAnchorTarget,
    ) : LlmCitationNavigationAction

    data class ExternalUrl(
        val url: String,
    ) : LlmCitationNavigationAction

    data object SourcesDetail : LlmCitationNavigationAction
}

/**
 * Translate frozen Chat history into the renderer-neutral Reader anchor contract.
 * Web Search and Tool Result citations deliberately stay outside Reader navigation.
 */
internal fun LlmCitationRefEntity.toReaderEvidenceAnchorTarget(): ReaderEvidenceAnchorTarget? {
    val locator = locatorSnapshot ?: return null
    if (
        locator.sourceKind != LlmEvidenceSourceKind.ARTICLE &&
            locator.sourceKind != LlmEvidenceSourceKind.SELECTION
    ) {
        return null
    }
    if (locator.articleId.isNullOrBlank()) return null
    if (
        locator.sourceKind == LlmEvidenceSourceKind.SELECTION &&
            locator.stableLocatorKey.isNullOrBlank()
    ) {
        // R07.6: an ambiguous/unmapped Selection keeps its frozen quote but must not be fuzzy-located.
        return null
    }
    return ReaderEvidenceAnchorTarget(
        articleId = locator.articleId,
        stableLocatorKey = locator.stableLocatorKey,
        normalizedHash = locator.normalizedHash,
        headingPath = locator.headingPath.orEmpty(),
        quote = quoteSnapshot,
    )
}

internal fun LlmCitationRefEntity.resolveCitationNavigationAction(): LlmCitationNavigationAction {
    val locator = locatorSnapshot ?: return LlmCitationNavigationAction.SourcesDetail
    return when (locator.sourceKind) {
        LlmEvidenceSourceKind.ARTICLE,
        LlmEvidenceSourceKind.SELECTION ->
            toReaderEvidenceAnchorTarget()?.let(LlmCitationNavigationAction::Reader)
                ?: LlmCitationNavigationAction.SourcesDetail
        LlmEvidenceSourceKind.WEB_SEARCH ->
            trustedHttpCitationUrl(locator.sourceUrl ?: sourceUrl)
                ?.let(LlmCitationNavigationAction::ExternalUrl)
                ?: LlmCitationNavigationAction.SourcesDetail
        LlmEvidenceSourceKind.TOOL_RESULT ->
            trustedHttpCitationUrl(locator.sourceUrl ?: sourceUrl)
                ?.let(LlmCitationNavigationAction::ExternalUrl)
                ?: LlmCitationNavigationAction.SourcesDetail
    }
}

private fun trustedHttpCitationUrl(value: String?): String? {
    val normalized = value?.trim()?.ifBlank { null } ?: return null
    val scheme = normalized.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authority = normalized.substringAfter("://", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    return normalized.takeIf { authority.isNotBlank() && !authority.contains(' ') }
}
