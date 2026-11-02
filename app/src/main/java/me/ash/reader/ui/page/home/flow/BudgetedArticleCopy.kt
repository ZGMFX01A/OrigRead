package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints

private enum class CopySlot { Title, OneLineSummary, TwoLineSummary }

/** Text reports line count synchronously during measurement. This is not snapshot state:
 * it neither invalidates composition nor changes the height on a later frame. */
private class MeasuredTitleLines : (TextLayoutResult) -> Unit {
    var count: Int = 3
        private set

    override fun invoke(result: TextLayoutResult) {
        count = result.lineCount
    }
}

/**
 * Only SHORT, non-bilingual rows need dependent text layout. Measure the actual styled title
 * first, then compose just the chosen ellipsized summary. Each Text is measured once per pass.
 */
@Composable
internal fun BudgetedArticleCopy(
    title: @Composable ((TextLayoutResult) -> Unit) -> Unit,
    summary: @Composable (Int) -> Unit,
) {
    val lines = remember { MeasuredTitleLines() }
    val titleContent: @Composable () -> Unit = { title(lines) }
    val oneLine: @Composable () -> Unit = { summary(1) }
    val twoLines: @Composable () -> Unit = { summary(2) }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val childConstraints = constraints.copy(minHeight = 0)
        val heading = subcompose(CopySlot.Title, titleContent).single().measure(childConstraints)
        val remainingHeight = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - heading.height).coerceAtLeast(0)
        } else Constraints.Infinity
        // Distinct slots keep the two maxLines values independent even in predictive layouts.
        val previewSlot = if (lines.count >= 3) subcompose(CopySlot.OneLineSummary, oneLine)
            else subcompose(CopySlot.TwoLineSummary, twoLines)
        val preview = previewSlot.single().measure(childConstraints.copy(maxHeight = remainingHeight))
        layout(
            width = maxOf(heading.width, preview.width),
            height = maxOf(constraints.minHeight, heading.height + preview.height),
        ) {
            heading.placeRelative(0, 0)
            preview.placeRelative(0, heading.height)
        }
    }
}
