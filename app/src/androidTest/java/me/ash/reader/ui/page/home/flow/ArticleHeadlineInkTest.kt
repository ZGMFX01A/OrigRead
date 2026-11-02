package me.ash.reader.ui.page.home.flow

import android.graphics.Color as PixelColor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.ceil
import kotlin.math.floor
import me.ash.reader.infrastructure.preference.BasicFontsPreference
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference
import me.ash.reader.infrastructure.preference.LocalBasicFonts
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ArticleHeadlineInkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mixedScriptEmphasisSurvivesFontFallbackAndTranslation() {
        val sample = "Film 电影中文标题"
        val unread = mutableStateOf(false)
        val translated = mutableStateOf(false)
        val scheme = lightColorScheme()
        compose.setContent {
            CompositionLocalProvider(
                LocalBasicFonts provides BasicFontsPreference.GoogleSans,
                LocalFlowArticleListReadIndicator provides FlowArticleReadIndicatorPreference.AllRead,
            ) {
                MaterialTheme(colorScheme = scheme, typography = BasicFontsPreference.GoogleSans.asTypography(LocalContext.current)) {
                    Box(Modifier.background(Color.White)) {
                        ArticleItem(title = if (translated.value) "English source" else sample,
                            translatedTitle = sample.takeIf { translated.value }, isUnread = unread.value)
                    }
                }
            }
        }

        // Measure ink in the actual Text node rather than merely asserting a requested weight.
        // Normalize by the foreground color so that a darker text color alone cannot pass.
        fun measure(): Pair<Double, Double> {
            val node = compose.onNodeWithText(sample, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val foreground = PixelColor.green(layout.layoutInput.style.color.toArgb())
            val bitmap = node.captureToImage().asAndroidBitmap()
            fun ink(range: IntRange): Double {
                val left = floor(layout.getBoundingBox(range.first).left).toInt().coerceIn(0, bitmap.width)
                val right = ceil(layout.getBoundingBox(range.last).right).toInt().coerceIn(left, bitmap.width)
                var total = 0.0
                for (y in 0 until bitmap.height) for (x in left until right) {
                    total += (255 - PixelColor.green(bitmap.getPixel(x, y))).toDouble() / (255 - foreground)
                }
                return total
            }
            return ink(0..3) to ink(5..sample.lastIndex)
        }

        val normal = measure()
        compose.runOnIdle { unread.value = true }
        val emphasized = measure()
        compose.runOnIdle { translated.value = true }
        val replacement = measure()
        Log.i("OrigReadTitleInk", "Latin regular/emphasized=${normal.first}/${emphasized.first}, Han regular/emphasized=${normal.second}/${emphasized.second}")
        assertTrue("Latin strokes must gain ink independently of text color: $normal -> $emphasized", emphasized.first > normal.first * 1.035)
        assertTrue("Han strokes must gain ink independently of text color: $normal -> $emphasized", emphasized.second > normal.second * 1.035)
        assertEquals("Original/translated Latin", emphasized.first, replacement.first, emphasized.first * .01)
        assertEquals("Original/translated Han", emphasized.second, replacement.second, emphasized.second * .01)
    }
}
