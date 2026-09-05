package me.ash.reader.ui.component.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.max
import kotlin.math.min

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

internal data class NativeReaderRenderedTextItem(
    val articleId: String?,
    val renderGeneration: Long,
    val itemIndex: Int,
    val lazyItemKey: String?,
    val textIdentity: Int,
    val renderedRanges: List<ReaderTextAnchorRange>,
    val textLayoutResult: TextLayoutResult,
    val coordinates: LayoutCoordinates,
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
    val renderGeneration: Long,
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
    private var nextRenderGeneration: Long = 0
    private var viewportCoordinates: LayoutCoordinates? = null
    private val renderedTextItems = mutableMapOf<Int, NativeReaderRenderedTextItem>()

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
        nextRenderGeneration += 1
        renderedTextItems.clear()
        viewportCoordinates = null
        binding =
            NativeReaderAnchorBinding(
                articleId = articleId,
                originalContent = originalContent,
                evidenceDocument = evidenceDocument,
                anchorMapBuilder = anchorMapBuilder,
                listState = listState,
                topInsetPx = topInsetPx.coerceAtLeast(0),
                renderGeneration = nextRenderGeneration,
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
        renderedTextItems.clear()
        viewportCoordinates = null
    }

    internal fun currentRenderGeneration(): Long? = binding?.renderGeneration

    internal fun currentArticleId(): String? = binding?.articleId

    /** 保存当前 Lazy viewport 坐标引用；滚动期间直接读取 attached coordinates，不写 Compose State。 */
    internal fun updateViewportCoordinates(coordinates: LayoutCoordinates) {
        viewportCoordinates = coordinates
    }

    /** 注册当前已 compose 的 Text 布局；只保留可见窗口附近的少量对象。 */
    internal fun registerRenderedTextItem(item: NativeReaderRenderedTextItem) {
        val current = binding ?: return
        if (item.renderGeneration != current.renderGeneration) return
        if (item.articleId?.trim() != current.articleId?.trim()) return
        if (!item.coordinates.isAttached) return
        renderedTextItems[item.itemIndex] = item
    }

    internal fun unregisterRenderedTextItem(
        itemIndex: Int,
        renderGeneration: Long,
        textIdentity: Int,
    ) {
        val current = renderedTextItems[itemIndex] ?: return
        if (current.renderGeneration == renderGeneration && current.textIdentity == textIdentity) {
            renderedTextItems.remove(itemIndex)
        }
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

        // If the exact rendered evidence range is already fully visible, do not start a LazyList
        // scroll mutation merely to center it. Besides being unnecessary motion, starting a scroll
        // can trigger a lazy-item relayout and briefly detach the Text coordinates that are the
        // stronger source of truth for this Citation.
        awaitRenderedAnchorRect(current, placement, resolved.block.stableLocatorKey)
            ?.takeIf { isRenderedAnchorVisible(current, it) }
            ?.let {
                return locatedResult(resolved, placement)
            }

        current.listState.animateCitationScroll { scope ->
            exactRenderedDistanceOrNull(current, placement, resolved.block.stableLocatorKey)
                ?: scope.calculateDistanceTo(placement.itemIndex, 0).toFloat()
        }
        val finalRect = awaitRenderedAnchorRect(current, placement, resolved.block.stableLocatorKey)
        if (finalRect == null || !isRenderedAnchorVisible(current, finalRect)) {
            return NativeReaderAnchorNavigationResult.Unavailable(
                NativeReaderAnchorUnavailableReason.RENDER_ITEM_NOT_FOUND
            )
        }
        return locatedResult(resolved, placement)
    }

    private fun locatedResult(
        resolved: ReaderEvidenceResolvedAnchor,
        placement: NativeReaderAnchorPlacement,
    ): NativeReaderAnchorNavigationResult.Located {
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

    /** 目标已布局时按真实文本行范围计算相对 safe viewport 中心的剩余滚动距离。 */
    private fun exactRenderedDistanceOrNull(
        binding: NativeReaderAnchorBinding,
        placement: NativeReaderAnchorPlacement,
        stableLocatorKey: String,
    ): Float? {
        val rect = renderedAnchorRectOrNull(binding, placement, stableLocatorKey) ?: return null
        val viewport = effectiveViewportRect(binding) ?: return null
        return rect.center.y - viewport.center.y
    }

    /**
     * A Reader navigation request itself can trigger a Lazy item recomposition. During that layout
     * hand-off the old coordinates are already detached while the replacement Text has not yet run
     * `onGloballyPositioned`. Wait only for layout frames, not a wall-clock delay, before degrading
     * to item-level positioning or declaring the exact evidence range unavailable.
     */
    private suspend fun awaitRenderedAnchorRect(
        binding: NativeReaderAnchorBinding,
        placement: NativeReaderAnchorPlacement,
        stableLocatorKey: String,
    ): Rect? {
        repeat(MAX_RENDERED_ANCHOR_LAYOUT_FRAMES) {
            renderedAnchorRectOrNull(binding, placement, stableLocatorKey)?.let { return it }
            withFrameNanos { }
        }
        return renderedAnchorRectOrNull(binding, placement, stableLocatorKey)
    }

    private fun renderedAnchorRectOrNull(
        binding: NativeReaderAnchorBinding,
        placement: NativeReaderAnchorPlacement,
        stableLocatorKey: String,
    ): Rect? {
        val item = renderedTextItems[placement.itemIndex] ?: return null
        if (item.renderGeneration != binding.renderGeneration || !item.coordinates.isAttached) return null
        val range =
            item.renderedRanges.firstOrNull { it.stableLocatorKey == stableLocatorKey } ?: return null
        val textLength = item.textLayoutResult.layoutInput.text.length
        val start = range.start.coerceIn(0, (textLength - 1).coerceAtLeast(0))
        val endOffset = (range.endExclusive - 1).coerceIn(start, (textLength - 1).coerceAtLeast(start))
        if (textLength == 0 || range.endExclusive <= range.start) return null
        val startLine = item.textLayoutResult.getLineForOffset(start)
        val endLine = item.textLayoutResult.getLineForOffset(endOffset)
        val local =
            Rect(
                left = 0f,
                top = item.textLayoutResult.getLineTop(startLine),
                right = item.textLayoutResult.size.width.toFloat(),
                bottom = item.textLayoutResult.getLineBottom(endLine),
            )
        val origin = item.coordinates.localToRoot(local.topLeft)
        return local.translate(origin.x - local.left, origin.y - local.top)
    }

    private fun effectiveViewportRect(binding: NativeReaderAnchorBinding): Rect? {
        val coordinates = viewportCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val bounds = coordinates.boundsInRoot()
        val safeTop = (bounds.top + binding.topInsetPx).coerceAtMost(bounds.bottom)
        return Rect(bounds.left, safeTop, bounds.right, bounds.bottom)
    }

    /** Located 的唯一正确性条件：目标真实 rect 已达到当前 viewport 能容纳的最大可见高度。 */
    private fun isRenderedAnchorVisible(
        binding: NativeReaderAnchorBinding,
        target: Rect,
    ): Boolean {
        val viewport = effectiveViewportRect(binding) ?: return false
        val visibleTop = max(target.top, viewport.top)
        val visibleBottom = min(target.bottom, viewport.bottom)
        val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0f)
        val requiredHeight = min(target.height, viewport.height).coerceAtLeast(1f)
        return visibleHeight + 1f >= requiredHeight
    }
}

private const val MAX_RENDERED_ANCHOR_LAYOUT_FRAMES = 3

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
