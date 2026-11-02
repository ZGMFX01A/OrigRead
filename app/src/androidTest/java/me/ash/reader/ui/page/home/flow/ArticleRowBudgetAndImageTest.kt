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
import me.ash.reader.infrastructure.preference.BasicFontsPreference
import me.ash.reader.infrastructure.preference.DarkThemePreference
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

    @Test fun thumbnailStartsBesideTheEyebrowAndAShortOrMissingImageDoesNotPadTheRow() {
        width.value = 340.dp
        photo.value = true
        render()
        val longPhoto = paintedPhoto()
        assertEyebrowTop(longPhoto)
        val summaryBounds = bounds(SUMMARY)
        val summaryLayout = textLayout(SUMMARY)
        val last = summaryLayout.lineCount - 1
        val halfLine = (summaryLayout.getLineBottom(last) - summaryLayout.getLineTop(last)) / 2
        val rowTop = frame.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The compact photo must not leave an empty summary line under it",
            summaryBounds.bottom - (rowTop + longPhoto.bottom) <= halfLine + 1f)

        compose.runOnIdle { brief.value = true }
        val shortPhoto = paintedPhoto()
        assertEyebrowTop(shortPhoto)
        val row = frame.fetchSemanticsNode().boundsInRoot
        val withPhotoWidth = textLayout(BRIEF_TITLE).layoutInput.constraints.maxWidth
        val title = bounds(BRIEF_TITLE)
        assertTrue("The short item doesn't stretch its thumbnail below the text",
            shortPhoto.bottom + row.top <= title.bottom + 2f)
        assertTrue("Short copy uses a smaller picture", shortPhoto.height() < longPhoto.height())

        compose.runOnIdle { photo.value = false }
        assertEquals("Removing a decorative image must not change the height of this one-line item",
            row.height, frame.fetchSemanticsNode().boundsInRoot.height, 1f)
        assertTrue("An image-free row returns the horizontal space to its text",
            textLayout(BRIEF_TITLE).layoutInput.constraints.maxWidth > withPhotoWidth)
    }

    private fun render() {
        val marker = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(MARKER) }
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
                        imgData = if (photo.value) marker else null,
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

    // A local solid-color bitmap locates the painted photo without adding test semantics to decorative images.
    private fun paintedPhoto(): PixelRect {
        var found: PixelRect? = null
        compose.waitUntil(5_000) {
            val image = frame.captureToImage().asAndroidBitmap()
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val result = PixelRect(image.width, image.height, 0, 0)
            for (i in pixels.indices) if (pixels[i] == MARKER) {
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
        const val HEADLINE = "The camera that changed the way directors film a chase"
        const val BRIEF_TITLE = "Still"
        const val CHINESE_TITLE = "镜头"
        const val SUMMARY = "An effects crew explains how changes in camera position, lighting and timing helped create a chase that was possible to follow even in the middle of the action."
        const val CHINESE_SUMMARY = "摄影组分享了拍摄追逐戏的过程，灯光、机位和节奏共同决定了银幕上的运动方向，也让观众始终能看清动作的细节。"
    }
}
