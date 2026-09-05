package me.ash.reader.ui.component.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ReaderTextAnchorRange(
    val stableLocatorKey: String,
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(stableLocatorKey.isNotBlank()) { "Reader anchor key must not be blank" }
        require(start >= 0) { "Reader anchor start must be non-negative" }
        require(endExclusive >= start) { "Reader anchor range must not be reversed" }
    }
}

data class NativeReaderAnchorPlacement(
    val itemIndex: Int,
    val textStart: Int,
    val textEndExclusive: Int,
)

data class NativeReaderTrackedItem(
    val itemIndex: Int,
    val lazyItemKey: String?,
)

class NativeReaderAnchorMap private constructor(
    private val placementsByStableKey: Map<String, List<NativeReaderAnchorPlacement>>,
) {
    fun placements(stableLocatorKey: String): List<NativeReaderAnchorPlacement> =
        placementsByStableKey[stableLocatorKey].orEmpty()

    class Builder {
        private var committedPlacementsByStableKey: Map<String, List<NativeReaderAnchorPlacement>> =
            emptyMap()
        private var stagingPlacementsByStableKey = linkedMapOf<String, MutableList<NativeReaderAnchorPlacement>>()
        private var stagingItemSegmentCounters = mutableMapOf<String, Int>()
        private var stagingNextItemIndex: Int = 0
        private var passActive: Boolean = false

        fun beginPass() {
            stagingPlacementsByStableKey = linkedMapOf()
            stagingItemSegmentCounters = mutableMapOf()
            stagingNextItemIndex = 0
            passActive = true
        }

        fun commitPass() {
            if (!passActive) return
            committedPlacementsByStableKey =
                stagingPlacementsByStableKey.mapValues { (_, placements) -> placements.toList() }
            passActive = false
        }

        fun recordItem(anchorRanges: List<ReaderTextAnchorRange> = emptyList()): NativeReaderTrackedItem {
            if (!passActive) beginPass()
            val itemIndex = stagingNextItemIndex++
            val normalizedRanges =
                anchorRanges.filter { range ->
                    range.stableLocatorKey.isNotBlank() && range.endExclusive > range.start
                }
            normalizedRanges.forEach { range ->
                stagingPlacementsByStableKey.getOrPut(range.stableLocatorKey) { mutableListOf() } +=
                    NativeReaderAnchorPlacement(
                        itemIndex = itemIndex,
                        textStart = range.start,
                        textEndExclusive = range.endExclusive,
                    )
            }

            val primaryAnchor = normalizedRanges.firstOrNull()?.stableLocatorKey
            val lazyItemKey =
                primaryAnchor?.let { key ->
                    val segment = stagingItemSegmentCounters[key] ?: 0
                    stagingItemSegmentCounters[key] = segment + 1
                    "origread-reader-anchor:$key:$segment"
                }
            return NativeReaderTrackedItem(itemIndex = itemIndex, lazyItemKey = lazyItemKey)
        }

        fun snapshot(): NativeReaderAnchorMap = NativeReaderAnchorMap(committedPlacementsByStableKey)
    }
}

data class NativeReaderAnchorHighlight(
    val stableLocatorKey: String,
    val revision: Long,
)

enum class NativeReaderAnchorUnavailableReason {
    NOT_BOUND,
    NOT_ORIGINAL_CONTENT,
    ARTICLE_MISMATCH,
    ANCHOR_NOT_FOUND,
    RENDER_ITEM_NOT_FOUND,
}

sealed interface NativeReaderAnchorNavigationResult {
    data class Located(
        val stableLocatorKey: String,
        val strategy: ReaderEvidenceResolveStrategy,
        val itemIndex: Int,
    ) : NativeReaderAnchorNavigationResult

    data class Unavailable(
        val reason: NativeReaderAnchorUnavailableReason,
    ) : NativeReaderAnchorNavigationResult
}

private data class NativeReaderAnchorBinding(
    val articleId: String?,
    val originalContent: Boolean,
    val evidenceDocument: ReaderEvidenceDocument,
    val anchorMapBuilder: NativeReaderAnchorMap.Builder,
    val listState: LazyListState,
    val topInsetPx: Int,
    var ready: Boolean = false,
)

/**
 * Current native Reader anchor controller. It owns no LLM types: the LLM edition converts a frozen
 * Citation locator into [ReaderEvidenceAnchorTarget] at the edition bridge.
 */
@Stable
class NativeReaderAnchorState {
    private var binding: NativeReaderAnchorBinding? = null
    private var highlightRevision: Long = 0

    var readyArticleId: String? by mutableStateOf(null)
        private set
    var readyRevision: Long by mutableStateOf(0L)
        private set

    var highlight: NativeReaderAnchorHighlight? by mutableStateOf(null)
        private set

    internal fun bind(
        articleId: String?,
        originalContent: Boolean,
        evidenceDocument: ReaderEvidenceDocument,
        anchorMapBuilder: NativeReaderAnchorMap.Builder,
        listState: LazyListState,
        topInsetPx: Int,
    ) {
        binding =
            NativeReaderAnchorBinding(
                articleId = articleId,
                originalContent = originalContent,
                evidenceDocument = evidenceDocument,
                anchorMapBuilder = anchorMapBuilder,
                listState = listState,
                topInsetPx = topInsetPx.coerceAtLeast(0),
            )
        readyArticleId = null
    }

    internal fun markRenderReady() {
        val current = binding ?: return
        if (!current.originalContent || current.ready) return
        current.ready = true
        readyArticleId = current.articleId?.trim()?.ifBlank { null }
        readyRevision += 1
    }

    internal fun unbind() {
        binding = null
        readyArticleId = null
        readyRevision += 1
        highlight = null
    }

    suspend fun navigateTo(target: ReaderEvidenceAnchorTarget): NativeReaderAnchorNavigationResult {
        val current = binding
            ?: return NativeReaderAnchorNavigationResult.Unavailable(
                NativeReaderAnchorUnavailableReason.NOT_BOUND
            )
        if (!current.originalContent) {
            return NativeReaderAnchorNavigationResult.Unavailable(
                NativeReaderAnchorUnavailableReason.NOT_ORIGINAL_CONTENT
            )
        }
        val targetArticleId = target.articleId?.trim()?.ifBlank { null }
        val currentArticleId = current.articleId?.trim()?.ifBlank { null }
        if (targetArticleId != null && targetArticleId != currentArticleId) {
            return NativeReaderAnchorNavigationResult.Unavailable(
                NativeReaderAnchorUnavailableReason.ARTICLE_MISMATCH
            )
        }

        val resolved = current.evidenceDocument.resolveReaderEvidenceAnchor(target)
            ?: return NativeReaderAnchorNavigationResult.Unavailable(
                NativeReaderAnchorUnavailableReason.ANCHOR_NOT_FOUND
            )
        val placement = current.anchorMapBuilder.snapshot().placements(resolved.block.stableLocatorKey).firstOrNull()
            ?: return NativeReaderAnchorNavigationResult.Unavailable(
                NativeReaderAnchorUnavailableReason.RENDER_ITEM_NOT_FOUND
            )

        current.listState.animateCitationScrollToItem(placement.itemIndex) { layoutInfo ->
            val estimatedTargetSize =
                layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == placement.itemIndex }
                    ?.size
                    ?: layoutInfo.visibleItemsInfo
                        .map { it.size }
                        .filter { it > 0 }
                        .average()
                        .takeIf { !it.isNaN() }
                        ?.toInt()
                    ?: 0
            nativeReaderCenteredScrollOffset(
                viewportStartOffset = layoutInfo.viewportStartOffset,
                viewportEndOffset = layoutInfo.viewportEndOffset,
                topInsetPx = current.topInsetPx,
                estimatedItemSizePx = estimatedTargetSize,
            )
        }
        highlightRevision += 1
        highlight =
            NativeReaderAnchorHighlight(
                stableLocatorKey = resolved.block.stableLocatorKey,
                revision = highlightRevision,
            )
        return NativeReaderAnchorNavigationResult.Located(
            stableLocatorKey = resolved.block.stableLocatorKey,
            strategy = resolved.strategy,
            itemIndex = placement.itemIndex,
        )
    }
}

internal fun nativeReaderCenteredScrollOffset(
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    topInsetPx: Int,
    estimatedItemSizePx: Int,
): Int {
    val viewportHeight = (viewportEndOffset - viewportStartOffset).coerceAtLeast(0)
    val safeTopInset = topInsetPx.coerceIn(0, viewportHeight)
    val readableHeight = (viewportHeight - safeTopInset).coerceAtLeast(0)
    val itemSize = estimatedItemSizePx.coerceIn(0, readableHeight)
    val distanceFromViewportStart = safeTopInset + ((readableHeight - itemSize) / 2)
    return -distanceFromViewportStart
}
