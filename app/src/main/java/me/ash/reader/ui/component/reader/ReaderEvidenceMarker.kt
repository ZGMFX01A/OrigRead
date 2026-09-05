package me.ash.reader.ui.component.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver

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
    val originArticleId: String? = null,
) {
    init {
        require(ownerArticleId.isNotBlank()) { "Reader marker owner article id must not be blank" }
        require(conversationId.isNotBlank()) { "Reader marker conversation id must not be blank" }
        require(assistantMessageId.isNotBlank()) { "Reader marker assistant message id must not be blank" }
        require(citationId.isNotBlank()) { "Reader marker citation id must not be blank" }
        require(displayOrder > 0) { "Reader marker display order must be positive" }
    }

    fun isOwnerArticle(currentArticleId: String?): Boolean =
        currentArticleId?.trim()?.ifBlank { null } == ownerArticleId.trim()

    fun shouldInvalidateForArticle(currentArticleId: String?): Boolean {
        val current = currentArticleId?.trim()?.ifBlank { null } ?: return false
        val owner = ownerArticleId.trim()
        val origin = originArticleId?.trim()?.ifBlank { null }
        return current != owner && current != origin
    }
}

enum class ReaderEvidenceMarkerLayerOrigin {
    INTERACTION,
    HISTORICAL,
}

data class ReaderEvidenceMarkerSnapshot(
    val ownerArticleId: String,
    val conversationId: String,
    val assistantMessageId: String,
    val markers: List<ReaderEvidenceMarker>,
    val origin: ReaderEvidenceMarkerLayerOrigin = ReaderEvidenceMarkerLayerOrigin.INTERACTION,
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
            originArticleId = normalizedArticleId,
        )
    }
}

@Stable
class ReaderEvidenceMarkerState {
    companion object {
        /** Preserve the owner and targets across recreation, including a completed A -> B jump. */
        val Saver = listSaver<ReaderEvidenceMarkerState, String>(
            save = { state ->
                state.snapshot?.let { layer ->
                    listOf(layer.ownerArticleId, layer.conversationId, layer.assistantMessageId, layer.origin.name) +
                        layer.markers.flatMap { marker ->
                            listOf(marker.citationId, marker.stableLocatorKey, marker.displayOrder.toString(), marker.articleId.orEmpty())
                        }
                }.orEmpty()
            },
            restore = { saved ->
                ReaderEvidenceMarkerState().apply {
                    if (saved.size >= 4 && (saved.size - 4) % 4 == 0) {
                        show(runCatching {
                            ReaderEvidenceMarkerSnapshot(
                                ownerArticleId = saved[0], conversationId = saved[1], assistantMessageId = saved[2],
                                origin = ReaderEvidenceMarkerLayerOrigin.valueOf(saved[3]),
                                markers = saved.drop(4).chunked(4).map {
                                    ReaderEvidenceMarker(it[0], it[1], it[2].toInt(), it[3].ifBlank { null })
                                },
                            )
                        }.getOrNull())
                    }
                }
            },
        )
    }

    var snapshot: ReaderEvidenceMarkerSnapshot? by mutableStateOf(null)
        private set

    fun show(snapshot: ReaderEvidenceMarkerSnapshot?) {
        this.snapshot = snapshot
    }

    fun clear() {
        snapshot = null
    }

    /**
     * Keep the currently visible markers while the Chat surface closes, but release interaction
     * priority so the Room-backed historical layer can replace them as soon as it is available.
     * This avoids a deterministic empty-marker frame without letting an old interaction layer pin
     * itself forever.
     */
    fun retainAsHistoricalFallback() {
        snapshot = snapshot?.copy(origin = ReaderEvidenceMarkerLayerOrigin.HISTORICAL)
    }
}

data class ReaderEvidenceMarkerInsertion(
    val endExclusive: Int,
    val displayOrders: List<Int>,
)

internal const val READER_EVIDENCE_MARKER_URL_PREFIX = "origread-citation://marker/"
internal const val READER_EVIDENCE_MARKER_SELECTION_SENTINEL = '\u2063'

/**
 * Remove only marker text that OrigRead itself injected into a selectable Reader surface.
 * Ordinary article text such as "[1]" is intentionally left untouched because it has no sentinel.
 */
internal fun stripReaderEvidenceMarkersFromSelectedText(text: String): String =
    text.replace(READER_EVIDENCE_MARKER_SELECTION_REGEX, "")

internal fun readerEvidenceMarkerUrl(displayOrder: Int): String =
    "$READER_EVIDENCE_MARKER_URL_PREFIX${displayOrder.coerceAtLeast(1)}"

internal fun readerEvidenceMarkerDisplayOrder(url: String): Int? =
    url.takeIf { it.startsWith(READER_EVIDENCE_MARKER_URL_PREFIX) }
        ?.removePrefix(READER_EVIDENCE_MARKER_URL_PREFIX)
        ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

private val READER_EVIDENCE_MARKER_SELECTION_REGEX =
    Regex(
        Regex.escape(READER_EVIDENCE_MARKER_SELECTION_SENTINEL.toString()) +
            "[ \\t]*\\[\\d+]" +
            Regex.escape(READER_EVIDENCE_MARKER_SELECTION_SENTINEL.toString())
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
