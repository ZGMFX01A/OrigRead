package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ash.reader.domain.model.constant.ElevationTokens
import me.ash.reader.infrastructure.preference.FlowArticleListCardStylePreference
import me.ash.reader.infrastructure.preference.LocalAmoledDarkTheme
import me.ash.reader.infrastructure.preference.LocalFlowArticleListCardStyle
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.theme.palette.onDark

internal val ArticleListContentInset = 24.dp

internal fun articleListPageElevation(cardStyle: FlowArticleListCardStylePreference, articleListTonalElevation: Int) =
    if (cardStyle == FlowArticleListCardStylePreference.Segmented) ElevationTokens.Level1.dp
    else articleListTonalElevation.dp

@Composable
internal fun articleListCardColor(): Color = with(MaterialTheme.colorScheme) {
    // In AMOLED, Low and Container both use tone 4; High is the next visible step.
    val darkCard = if (LocalAmoledDarkTheme.current.value) surfaceContainerHigh else surfaceContainer
    surfaceContainerLowest onDark darkCard
}

@Composable
internal fun articleListBackgroundColor(
    cardStyle: FlowArticleListCardStylePreference,
    articleListTonalElevation: Int,
): Color = with(MaterialTheme.colorScheme) {
    // Give grouped cards a darker canvas in night mode. Lowest is pure black in AMOLED.
    // Flat lists keep their regular dark surface and light-theme elevations stay unchanged.
    val darkPage = if (cardStyle == FlowArticleListCardStylePreference.Segmented) surfaceContainerLowest else surface
    surfaceColorAtElevation(articleListPageElevation(cardStyle, articleListTonalElevation)) onDark darkPage
}

@Composable
fun StickyHeader(
    dateString: String,
    articleListTonalElevation: Int = 0,
    cardStyle: FlowArticleListCardStylePreference = LocalFlowArticleListCardStyle.current,
) {
    val bottomPadding = if (cardStyle == FlowArticleListCardStylePreference.Segmented) 12.dp else 4.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(articleListBackgroundColor(cardStyle, articleListTonalElevation)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.padding(start = ArticleListContentInset, top = 8.dp, bottom = bottomPadding),
            text = dateString,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
