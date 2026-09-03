package me.ash.reader.ui.component.reader

/**
 * One Citation navigation request that may outlive the article switch which it triggers.
 *
 * LLM types deliberately stay outside this main Reader contract. The frozen Chat citation is
 * converted to [ReaderEvidenceAnchorTarget] before entering the Reader lifecycle.
 */
data class PendingCitationNavigation(
    val assistantMessageId: String,
    val citationId: String,
    val articleId: String,
    val target: ReaderEvidenceAnchorTarget,
    val requestedAt: Long,
    val originArticleId: String? = null,
) {
    init {
        require(assistantMessageId.isNotBlank()) { "Pending Citation assistant message id must not be blank" }
        require(citationId.isNotBlank()) { "Pending Citation id must not be blank" }
        require(articleId.isNotBlank()) { "Pending Citation article id must not be blank" }
        require(target.articleId?.trim() == articleId.trim()) {
            "Pending Citation target article must match navigation article"
        }
    }

    fun sameRequest(other: PendingCitationNavigation?): Boolean =
        other != null &&
            assistantMessageId == other.assistantMessageId &&
            citationId == other.citationId &&
            requestedAt == other.requestedAt

    fun isTargetArticle(currentArticleId: String?): Boolean =
        currentArticleId?.trim()?.ifBlank { null } == articleId.trim()

    fun shouldInvalidateForArticle(currentArticleId: String?): Boolean {
        val current = currentArticleId?.trim()?.ifBlank { null } ?: return false
        val target = articleId.trim()
        val origin = originArticleId?.trim()?.ifBlank { null }
        return current != target && current != origin
    }
}
