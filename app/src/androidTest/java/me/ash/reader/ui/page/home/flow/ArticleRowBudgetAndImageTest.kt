package me.ash.reader.ui.page.home.flow

import android.graphics.Bitmap
import android.graphics.Rect as PixelRect
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import me.ash.reader.infrastructure.preference.BasicFontsPreference
import me.ash.reader.infrastructure.preference.DarkThemePreference
import me.ash.reader.infrastructure.preference.FlowArticleListCardStylePreference
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.FlowArticleListImagePreference
import me.ash.reader.infrastructure.preference.FlowArticleListTimePreference
import me.ash.reader.infrastructure.preference.LocalBasicFonts
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Measure the real row's text and painted image; the neighboring screenshot suite replays the full feed. */
class ArticleRowBudgetAndImageTest {
    @get:Rule val compose = createComposeRule()

    private val width = mutableStateOf(190.dp)
    private val fontScale = mutableStateOf(1f)
    private val description = mutableStateOf<FlowArticleListDescPreference>(FlowArticleListDescPreference.SHORT)
    private val translated = mutableStateOf(false)
    private val bilingual = mutableStateOf(false)
    private val brief = mutableStateOf(false)
    private val photo = mutableStateOf(false)
    private val widePhoto = mutableStateOf(false)
    private val style = mutableStateOf<FlowArticleListCardStylePreference>(FlowArticleListCardStylePreference.Divider)
    private val frame get() = compose.onNodeWithTag("article-probe", useUnmergedTree = true)

    @Test fun shortSummaryFollowsActualTitleLinesAfterWidthTranslationAndTextSizeChanges() {
        render()
        assertEquals(3, textLayout(HEADLINE).lineCount)
        assertEquals(1, textLayout(SUMMARY).lineCount)
        compose.runOnIdle { width.value = 360.dp }
        assertTrue(textLayout(HEADLINE).lineCount in 1..2)
        assertEquals(2, textLayout(SUMMARY).lineCount)

        // Same composition slot and narrow width, but the displayed Chinese title fits on one line.
        compose.runOnIdle { width.value = 190.dp; translated.value = true }
        assertEquals(1, textLayout(CHINESE_TITLE).lineCount)
        assertEquals(2, textLayout(CHINESE_SUMMARY).lineCount)

        compose.runOnIdle { width.value = 270.dp; translated.value = false; fontScale.value = 1.35f }
        val enlargedTitleLines = textLayout(HEADLINE).lineCount
        assertTrue(enlargedTitleLines in 1..3)
        assertEquals(if (enlargedTitleLines == 3) 1 else 2, textLayout(SUMMARY).lineCount)

        compose.runOnIdle { width.value = 190.dp; fontScale.value = 1f; description.value = FlowArticleListDescPreference.LONG }
        assertEquals(3, textLayout(HEADLINE).lineCount)
        assertEquals("The reader explicitly requested a long summary", 4, textLayout(SUMMARY).lineCount)
        compose.runOnIdle {
            description.value = FlowArticleListDescPreference.SHORT
            bilingual.value = true
            translated.value = false
        }
        assertEquals("An untranslated row is still a single copy", 1, textLayout(SUMMARY).lineCount)
        compose.runOnIdle { translated.value = true }
        assertEquals(3, textLayout(HEADLINE).lineCount)
        assertEquals(1, textLayout(CHINESE_TITLE).lineCount)
        assertEquals("Both bilingual summaries keep the configured budget", 2, textLayout(SUMMARY).lineCount)
        assertEquals(2, textLayout(CHINESE_SUMMARY).lineCount)
    }

    @Test fun bothFramesUseBoundedSquareThumbnailsAndPreserveTheEndsOfWidePhotos() {
        width.value = 340.dp
        render()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        for (mode in listOf(FlowArticleListCardStylePreference.Divider, FlowArticleListCardStylePreference.Segmented)) {
            compose.runOnIdle { style.value = mode; brief.value = false; photo.value = false; widePhoto.value = false }
            val available = textLayout(HEADLINE).layoutInput.constraints.maxWidth
            val sideBudget = minOf((76 * density).roundToInt(), available / 4)
            compose.runOnIdle { photo.value = true }
            val square = paintedPhoto(MARKER)
            assertEyebrowTop(square)
            // The painted bounds exclude antialiased edge pixels. Compare within one physical pixel.
            assertEquals("Long copy cannot stretch a square in $mode",
                square.width().toFloat(), square.height().toFloat(), 1f)
            assertTrue("Thumbnail respects both width caps in $mode", square.width() <= sideBudget + 1)
            val textWidth = textLayout(HEADLINE).layoutInput.constraints.maxWidth
            assertTrue("Image and gap must not consume additional text width in $mode",
                available - textWidth in square.width()..(sideBudget + (12 * density).roundToInt() + 1))

            // Same row and text, but a 4:1 photo with identifying stripes on its outer edges.
            // Cropping to the center would remove both stripes. Fit must keep them visible.
            compose.runOnIdle { widePhoto.value = true }
            val left = paintedPhoto(LEFT_EDGE)
            val right = paintedPhoto(RIGHT_EDGE)
            assertTrue("The two edge markers remain distinct and substantial in $mode",
                left.right < right.left && left.width() >= square.width() / 10 && right.width() >= square.width() / 10)
            assertEquals("Left photo edge in $mode", square.left.toFloat(), left.left.toFloat(), 2f)
            assertEquals("Right photo edge in $mode", square.right.toFloat(), right.right.toFloat(), 2f)
            assertEquals("Preserve the wide picture's aspect ratio in $mode", square.width() / 4f, left.height().toFloat(), 2f)
            assertEquals("The full banner stays at the top of the same slot in $mode",
                square.top.toFloat(), left.top.toFloat(), 2f)
            val fitted = frame.captureToImage().asAndroidBitmap()
            val sampleY = square.bottom - 2
            val gutter = (square.right + (3 * density).roundToInt()).coerceAtMost(fitted.width - 1)
            assertEquals("Unused space below a wide photo must match the article, not an image backplate",
                fitted.getPixel(gutter, sampleY), fitted.getPixel(square.centerX(), sampleY))
            assertEquals("Changing the source aspect ratio cannot narrow the text", textWidth,
                textLayout(HEADLINE).layoutInput.constraints.maxWidth)

            compose.runOnIdle { brief.value = true; widePhoto.value = false }
            val short = paintedPhoto(MARKER)
            assertEyebrowTop(short)
            val row = frame.fetchSemanticsNode().boundsInRoot
            assertEquals("Short copy also gets a square in $mode", short.width().toFloat(), short.height().toFloat(), 1f)
            assertTrue("Short copy can shrink its picture", short.width() < square.width())
            val shortTextWidth = textLayout(BRIEF_TITLE).layoutInput.constraints.maxWidth
            assertTrue("The picture must not stretch below a short item", short.bottom + row.top <= bounds(BRIEF_TITLE).bottom + 2f)
            compose.runOnIdle { photo.value = false }
            assertEquals("Removing the picture must not change this one-line row's height",
                row.height, frame.fetchSemanticsNode().boundsInRoot.height, 1f)
            val restored = textLayout(BRIEF_TITLE).layoutInput.constraints.maxWidth
            assertEquals("The full content width returns in $mode", available, restored)
            assertTrue("No empty image slot", restored > shortTextWidth)
        }
    }

    private fun render() {
        val marker = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(MARKER) }
        val stripes = IntArray(160 * 40) { i -> when (i % 160) {
            in 0..23 -> LEFT_EDGE
            in 136..159 -> RIGHT_EDGE
            else -> BANNER_CENTER
        } }
        val banner = Bitmap.createBitmap(stripes, 160, 40, Bitmap.Config.ARGB_8888)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale.value),
                LocalDarkTheme provides DarkThemePreference.OFF,
                LocalBasicFonts provides BasicFontsPreference.GoogleSans,
                LocalFlowArticleListDesc provides description.value,
                LocalFlowArticleListImage provides FlowArticleListImagePreference.ON,
                LocalFlowArticleListTime provides FlowArticleListTimePreference.ON,
            ) {
                AppTheme(useDarkTheme = false) {
                    ArticleItem(
                        modifier = Modifier.width(width.value).testTag("article-probe"),
                        title = if (brief.value) BRIEF_TITLE else HEADLINE,
                        shortDescription = if (brief.value) "" else SUMMARY,
                        translatedTitle = if (translated.value) CHINESE_TITLE else null,
                        translatedDescription = if (translated.value) CHINESE_SUMMARY else null,
                        bilingual = bilingual.value,
                        timeString = "06:00",
                        cardStyle = style.value,
                        imgData = if (!photo.value) null else if (widePhoto.value) banner else marker,
                        isUnread = true,
                        isSingleFeed = true,
                    )
                }
            }
        }
    }

    private fun bounds(text: String): Rect =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun textLayout(text: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    private fun assertEyebrowTop(photo: PixelRect) {
        val origin = frame.fetchSemanticsNode().boundsInRoot
        assertEquals("The image and the time eyebrow share a top edge", bounds("06:00").top,
            origin.top + photo.top, 2f)
        assertTrue("The image is in the trailing column", photo.centerX() > origin.width * 0.65f)
    }

    // Locate actual image pixels without adding accessibility semantics to a decorative image.
    private fun paintedPhoto(color: Int): PixelRect {
        var found: PixelRect? = null
        compose.waitUntil(5_000) {
            val image = frame.captureToImage().asAndroidBitmap()
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val result = PixelRect(image.width, image.height, 0, 0)
            for (i in pixels.indices) if (pixels[i] == color) {
                val x = i % image.width
                val y = i / image.width
                result.left = minOf(result.left, x)
                result.top = minOf(result.top, y)
                result.right = maxOf(result.right, x + 1)
                result.bottom = maxOf(result.bottom, y + 1)
            }
            found = result.takeUnless { it.isEmpty }
            found != null
        }
        return checkNotNull(found)
    }

    private companion object {
        const val MARKER: Int = -12861257 // opaque green, deliberately distinct from the app palette
        const val LEFT_EDGE = 0xFFEF2949.toInt()
        const val RIGHT_EDGE = 0xFF246BEE.toInt()
        const val BANNER_CENTER = 0xFFEFB637.toInt()
        const val HEADLINE = "The camera that changed the way directors film a chase"
        const val BRIEF_TITLE = "Still"
        const val CHINESE_TITLE = "镜头"
        const val SUMMARY = "An effects crew explains how changes in camera position, lighting and timing helped create a chase that was possible to follow even in the middle of the action."
        const val CHINESE_SUMMARY = "摄影组分享了拍摄追逐戏的过程，灯光、机位和节奏共同决定了银幕上的运动方向，也让观众始终能看清动作的细节。"
    }
}
