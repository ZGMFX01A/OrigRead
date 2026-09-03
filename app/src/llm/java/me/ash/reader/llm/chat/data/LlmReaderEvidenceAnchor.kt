package me.ash.reader.llm.chat.data

import me.ash.reader.ui.component.reader.ReaderEvidenceAnchorTarget

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
    return ReaderEvidenceAnchorTarget(
        articleId = locator.articleId,
        stableLocatorKey = locator.stableLocatorKey,
        normalizedHash = locator.normalizedHash,
        headingPath = locator.headingPath.orEmpty(),
        quote = quoteSnapshot,
    )
}
