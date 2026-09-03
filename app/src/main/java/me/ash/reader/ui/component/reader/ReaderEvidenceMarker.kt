package me.ash.reader.ui.component.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ReaderEvidenceMarker(
    val stableLocatorKey: String,
    val displayOrder: Int,
    val articleId: String? = null,
) {
    init {
        require(stableLocatorKey.isNotBlank()) { "Reader marker anchor key must not be blank" }
        require(displayOrder > 0) { "Reader marker display order must be positive" }
    }
}

data class ReaderEvidenceMarkerSnapshot(
    val assistantMessageId: String,
    val markers: List<ReaderEvidenceMarker>,
) {
    init {
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
