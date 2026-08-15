package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val AiSummaryAccentBlue = Color(0xFF65A9F3)

@Composable
internal fun AiSummaryAccentIcon(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    enabled: Boolean = true,
    size: Dp = 30.dp,
    iconSize: Dp = 18.dp,
) {
    val shape = RoundedCornerShape(9.dp)
    val gradient =
        Brush.linearGradient(
            listOf(
                Color(0xFF62A7FF),
                Color(0xFF72C6FF),
                Color(0xFF8A8CFF),
            ),
        )

    Box(
        modifier =
            modifier
                .size(size)
                .alpha(if (enabled) 1f else 0.42f)
                .clip(shape)
                .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (active) Icons.Rounded.AutoAwesome else Icons.Outlined.AutoAwesome,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}
