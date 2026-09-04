package me.ash.reader.ui.component.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ReaderEvidenceMarker(
    val citationId: String,
    val stableLocatorKey: String,
    val displayOrder: Int,
    val articleId: String? = null,
) {
    init {
        require(citationId.isNotBlank()) { "Reader marker citation id must not be blank" }
        require(stableLocatorKey.isNotBlank()) { "Reader marker anchor key must not be blank" }
        require(displayOrder > 0) { "Reader marker display order must be positive" }
    }
}

data class ReaderEvidenceMarkerNavigationTarget(
    val ownerArticleId: String,
    val conversationId: String,
    val assistantMessageId: String,
    val citationId: String,
    val displayOrder: Int,
) {
    init {
        require(ownerArticleId.isNotBlank()) { "Reader marker owner article id must not be blank" }
        require(conversationId.isNotBlank()) { "Reader marker conversation id must not be blank" }
        require(assistantMessageId.isNotBlank()) { "Reader marker assistant message id must not be blank" }
        require(citationId.isNotBlank()) { "Reader marker citation id must not be blank" }
        require(displayOrder > 0) { "Reader marker display order must be positive" }
    }
}

data class ReaderEvidenceMarkerSnapshot(
    val ownerArticleId: String,
    val conversationId: String,
    val assistantMessageId: String,
    val markers: List<ReaderEvidenceMarker>,
) {
    init {
        require(ownerArticleId.isNotBlank()) { "Reader marker owner article id must not be blank" }
        require(conversationId.isNotBlank()) { "Reader marker conversation id must not be blank" }
        require(assistantMessageId.isNotBlank()) { "Reader marker assistant message id must not be blank" }
    }

    fun displayOrdersFor(
        currentArticleId: String?,
        stableLocatorKey: String,
    ): List<Int> {
        val normalizedArticleId = currentArticleId?.trim()?.ifBlank { null }
        return markers
            .asSequence()
            .filter { marker ->
                marker.stableLocatorKey == stableLocatorKey &&
                    marker.articleId?.trim()?.ifBlank { null } == normalizedArticleId
            }
            .map(ReaderEvidenceMarker::displayOrder)
            .distinct()
            .sorted()
            .toList()
    }

    fun navigationTargetFor(
        currentArticleId: String?,
        displayOrder: Int,
    ): ReaderEvidenceMarkerNavigationTarget? {
        val normalizedArticleId = currentArticleId?.trim()?.ifBlank { null } ?: return null
        val marker =
            markers.firstOrNull { marker ->
                marker.displayOrder == displayOrder &&
                    marker.articleId?.trim()?.ifBlank { null } == normalizedArticleId
            } ?: return null
        return ReaderEvidenceMarkerNavigationTarget(
            ownerArticleId = ownerArticleId,
            conversationId = conversationId,
            assistantMessageId = assistantMessageId,
            citationId = marker.citationId,
            displayOrder = marker.displayOrder,
        )
    }
}

@Stable
class ReaderEvidenceMarkerState {
    var snapshot: ReaderEvidenceMarkerSnapshot? by mutableStateOf(null)
        private set

    fun show(snapshot: ReaderEvidenceMarkerSnapshot?) {
        this.snapshot = snapshot
    }

    fun clear() {
        snapshot = null
    }
}

data class ReaderEvidenceMarkerInsertion(
    val endExclusive: Int,
    val displayOrders: List<Int>,
)

internal const val READER_EVIDENCE_MARKER_URL_PREFIX = "origread-citation://marker/"

internal fun readerEvidenceMarkerUrl(displayOrder: Int): String =
    "$READER_EVIDENCE_MARKER_URL_PREFIX${displayOrder.coerceAtLeast(1)}"

internal fun readerEvidenceMarkerDisplayOrder(url: String): Int? =
    url.takeIf { it.startsWith(READER_EVIDENCE_MARKER_URL_PREFIX) }
        ?.removePrefix(READER_EVIDENCE_MARKER_URL_PREFIX)
        ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

internal fun buildReaderEvidenceMarkerInsertions(
    anchorRanges: List<ReaderTextAnchorRange>,
    snapshot: ReaderEvidenceMarkerSnapshot?,
    articleId: String?,
    textLength: Int,
): List<ReaderEvidenceMarkerInsertion> {
    if (snapshot == null || textLength <= 0) return emptyList()
    val latestEndByStableKey =
        anchorRanges
            .filter { it.endExclusive > it.start }
            .groupBy(ReaderTextAnchorRange::stableLocatorKey)
            .mapValues { (_, ranges) -> ranges.maxOf(ReaderTextAnchorRange::endExclusive) }
    val ordersByEnd = linkedMapOf<Int, MutableSet<Int>>()
    latestEndByStableKey.forEach { (stableKey, rawEnd) ->
        val orders = snapshot.displayOrdersFor(articleId, stableKey)
        if (orders.isEmpty()) return@forEach
        val end = rawEnd.coerceIn(0, textLength)
        if (end <= 0) return@forEach
        ordersByEnd.getOrPut(end) { linkedSetOf() }.addAll(orders)
    }
    return ordersByEnd
        .map { (end, orders) ->
            ReaderEvidenceMarkerInsertion(
                endExclusive = end,
                displayOrders = orders.sorted(),
            )
        }
        .sortedBy(ReaderEvidenceMarkerInsertion::endExclusive)
}
