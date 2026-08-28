package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private enum class StableSummarySlot {
    Article,
    Summary,
}

/**
 * 摘要面板与正文的稳定布局。
 *
 * 摘要以 overlay 方式绘制，不再通过 `Column + weight` 压缩正文 viewport；摘要实际高度会等量加入正文顶部
 * 的滚动占位，因此标题仍从摘要下方开始，同时总滚动范围与原布局保持一致。
 */
@Composable
internal fun StableSummaryReadingLayout(
    modifier: Modifier = Modifier,
    summaryTopOffset: Dp,
    summaryContent: (@Composable () -> Unit)?,
    articleContent: @Composable (summaryReservedHeight: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val summaryTopOffsetPx = with(density) { summaryTopOffset.roundToPx() }

    SubcomposeLayout(
        modifier = modifier.clipToBounds(),
    ) { constraints ->
        val summaryPlaceable =
            summaryContent
                ?.let {
                    subcompose(StableSummarySlot.Summary, it)
                        .firstOrNull()
                        ?.measure(constraints.copy(minHeight = 0))
                }
        val summaryReservedHeight =
            summaryPlaceable?.height?.let { height -> with(density) { height.toDp() } } ?: 0.dp
        val articlePlaceable =
            subcompose(StableSummarySlot.Article) { articleContent(summaryReservedHeight) }
                .first()
                .measure(constraints)

        layout(constraints.maxWidth, constraints.maxHeight) {
            // 正文始终占完整 viewport；摘要后绘制，确保滚动内容经过其区域时被摘要面板自然覆盖。
            articlePlaceable.placeRelative(x = 0, y = 0)
            summaryPlaceable?.placeRelative(x = 0, y = summaryTopOffsetPx)
        }
    }
}
