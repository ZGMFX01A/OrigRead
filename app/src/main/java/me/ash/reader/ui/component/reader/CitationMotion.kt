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
