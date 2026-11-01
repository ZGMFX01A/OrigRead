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
import androidx.compose.ui.unit.dp
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.theme.palette.onDark

import androidx.compose.ui.text.font.FontWeight
import me.ash.reader.infrastructure.preference.FlowArticleListCardStylePreference
import me.ash.reader.infrastructure.preference.LocalFlowArticleListCardStyle

@Composable
fun StickyHeader(
    dateString: String,
    isShowFeedIcon: Boolean = false,
    articleListTonalElevation: Int = 0,
    cardStyle: FlowArticleListCardStylePreference = LocalFlowArticleListCardStyle.current,
) {
    val isSegmented = cardStyle == FlowArticleListCardStylePreference.Segmented
    val startPadding = if (isSegmented) 28.dp else if (isShowFeedIcon) 54.dp else 24.dp
    val bottomPadding = if (isSegmented) 8.dp else 4.dp
    val bgColor = if (isSegmented) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        (MaterialTheme.colorScheme.surfaceColorAtElevation(articleListTonalElevation.dp)
            onDark MaterialTheme.colorScheme.surface)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier
                .padding(start = startPadding)
                .padding(top = 8.dp, bottom = bottomPadding),
            text = dateString,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}