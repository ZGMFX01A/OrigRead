package me.ash.reader.ui.component.base

import android.graphics.drawable.PictureDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import coil.compose.rememberAsyncImagePainter
import com.caverock.androidsvg.SVG
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.ui.svg.parseDynamicColor
import me.ash.reader.ui.theme.palette.LocalTonalPalettes

@Composable
fun DynamicSVGImage(
    modifier: Modifier = Modifier,
    svgImageString: String,
    contentDescription: String,
) {
    val useDarkTheme = LocalDarkTheme.current.isDarkTheme()
    val tonalPalettes = LocalTonalPalettes.current
    var size by remember { mutableStateOf(IntSize.Zero) }

    Row(
        modifier =
            modifier.aspectRatio(1.38f).onGloballyPositioned {
                if (it.size.width > 0 && it.size.height > 0) {
                    size = it.size
                }
            }
    ) {
        if (size.width > 0 && size.height > 0) {
            val pic =
                remember(useDarkTheme, tonalPalettes, size, svgImageString) {
                    PictureDrawable(
                        SVG.getFromString(svgImageString.parseDynamicColor(tonalPalettes, useDarkTheme))
                            .renderToPicture(size.width, size.height)
                    )
                }
            Crossfade(targetState = pic) {
                Image(contentDescription = contentDescription, painter = rememberAsyncImagePainter(it))
            }
        }
    }
}
