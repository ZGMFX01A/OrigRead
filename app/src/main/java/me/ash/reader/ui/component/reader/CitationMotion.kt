package me.ash.reader.ui.component.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState

/** Citation feedback leaves time to read the destination, after scrolling has completed. */
internal object CitationMotion {
    const val ScrollMillis = 820
    const val SettleScrollMillis = 140
    const val LongDistanceSettleMillis = 180
    const val FadeInMillis = 180
    const val HoldMillis = 2000L
    const val FadeOutMillis = 600
    const val HighlightMillis = FadeInMillis + HoldMillis + FadeOutMillis
}

/** Re-evaluate the remaining distance as previously unmeasured lazy items enter the viewport. */
internal suspend fun LazyListState.animateCitationScrollToItem(
    index: Int,
    scrollOffset: (LazyListLayoutInfo) -> Int = { 0 },
) {
    val visibleItemCount = layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val itemDistance = kotlin.math.abs(index - firstVisibleItemIndex)
    val longDistanceTarget =
        layoutInfo.visibleItemsInfo.none { it.index == index } &&
            itemDistance > visibleItemCount * 3
    if (longDistanceTarget) {
        // Let Compose own the long-distance traversal. LazyListState.animateScrollToItem() has
        // dedicated handling for targets well outside the measured window, so it can reach a
        // paragraph near the end of a long article without composing every intermediate item.
        // This replaces the old explicit scrollToItem() pre-jump: the long jump is no longer a
        // hard snap controlled by OrigRead, while the final centering still gets a short animated
        // correction after the real target item has been measured.
        animateScrollToItem(index = index, scrollOffset = scrollOffset(layoutInfo))
        settleCitationTarget(index, scrollOffset, CitationMotion.LongDistanceSettleMillis)
        return
    }

    scroll {
        val scope = LazyLayoutScrollScope(this@animateCitationScrollToItem, this)
        var previous = 0f
        animate(0f, 1f, animationSpec = tween(CitationMotion.ScrollMillis, easing = FastOutSlowInEasing)) { fraction, _ ->
            val remaining = scope.calculateDistanceTo(index, scrollOffset(layoutInfo))
            val step = (fraction - previous) / (1f - previous).coerceAtLeast(0.0001f)
            scope.scrollBy(remaining * step)
            previous = fraction
        }

        // Layout estimates can shift as previously unseen lazy items become measured. Finish with
        // a tiny animated correction instead of a hard snap so the destination never visibly jumps.
        val remaining = scope.calculateDistanceTo(index, scrollOffset(layoutInfo))
        if (kotlin.math.abs(remaining) > 1f) {
            var settled = 0f
            animate(
                0f,
                remaining.toFloat(),
                animationSpec = tween<Float>(CitationMotion.SettleScrollMillis, easing = FastOutSlowInEasing),
            ) { value: Float, _: Float ->
                scope.scrollBy(value - settled)
                settled = value
            }
        }
    }
}

private suspend fun LazyListState.settleCitationTarget(
    index: Int,
    scrollOffset: (LazyListLayoutInfo) -> Int,
    durationMillis: Int,
) {
    scroll {
        val scope = LazyLayoutScrollScope(this@settleCitationTarget, this)
        val remaining = scope.calculateDistanceTo(index, scrollOffset(layoutInfo))
        if (kotlin.math.abs(remaining) <= 1f) return@scroll

        var settled = 0f
        animate(
            initialValue = 0f,
            targetValue = remaining.toFloat(),
            animationSpec = tween<Float>(durationMillis, easing = FastOutSlowInEasing),
        ) { value: Float, _: Float ->
            scope.scrollBy(value - settled)
            settled = value
        }
    }
}
