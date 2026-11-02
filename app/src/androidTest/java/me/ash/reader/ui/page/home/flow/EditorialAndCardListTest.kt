package me.ash.reader.ui.page.home.flow

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.flowOf
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.preference.DarkThemePreference
import me.ash.reader.infrastructure.preference.FlowArticleListCardStylePreference
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.FlowArticleListFeedIconPreference
import me.ash.reader.infrastructure.preference.FlowArticleListFeedNamePreference
import me.ash.reader.infrastructure.preference.FlowArticleListImagePreference
import me.ash.reader.infrastructure.preference.FlowArticleListTimePreference
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real paging rows, local thumbnails and an English film feed. No account or network is needed. */
class EditorialAndCardListTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val style = mutableStateOf<FlowArticleListCardStylePreference>(FlowArticleListCardStylePreference.Divider)
    private val dark = mutableStateOf(false)
    private val singleFeed = mutableStateOf(false)
    private val list get() = compose.onNodeWithTag("film-list")
    private var clicked: String? = null

    @Test fun styleSwitchPreservesTheTitleAxisAndLineBreaksAndKeepsContextBelowTheSummary() {
        renderList()
        var originalTitle: Rect? = null
        var originalBreaks: List<Int>? = null
        for (mode in listOf(FlowArticleListCardStylePreference.Divider, FlowArticleListCardStylePreference.Segmented)) {
            compose.runOnIdle { style.value = mode; singleFeed.value = false; clicked = null }
            val heading = bounds("Today")
            val title = bounds(titles.first())
            val summary = bounds(summaries.first())
            val time = bounds("06:00", substring = true)
            val source = compose.onAllNodesWithText("/Film", useUnmergedTree = true)
                .fetchSemanticsNodes().minOf { it.boundsInRoot.top }
            val textLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(titles.first(), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
            val layout = textLayouts.single()
            val breaks = (0 until layout.lineCount).map { layout.getLineEnd(it, visibleEnd = true) }
            assertTrue("Fixture exercises wrapping on a phone pane", breaks.size in 2..3)
            assertTrue("The sample headline must fit before the summary", !layout.isLineEllipsized(layout.lineCount - 1))
            assertEquals(heading.left, title.left, 1f)
            assertEquals(title.left, summary.left, 1f)
            assertTrue("Title precedes summary", title.bottom <= summary.top + 1f)
            assertTrue("Source and time follow the summary", summary.bottom <= minOf(source, time.top) + 1f)
            originalTitle?.let { assertEquals(it.left, title.left, 1f) }
            originalBreaks?.let { assertEquals("Switching the frame must not reflow text", it, breaks) }
            originalTitle = title
            originalBreaks = breaks

            // The mixed view also happens to contain only /Film in this loaded page.
            // Suppress that source only when the caller identifies the selected feed.
            compose.runOnIdle { singleFeed.value = true }
            compose.onAllNodesWithText("/Film", useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText("06:00", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(titles.first()).performClick()
            compose.runOnIdle { assertEquals("film-1", clicked) }
        }
    }

    @Test fun lightAndDarkFramesHaveOneEditorialRuleOrSeparateOutlinedCards() {
        singleFeed.value = true
        renderList()
        for (night in listOf(false, true)) {
            val scheme = if (night) darkColorScheme() else lightColorScheme()
            for (card in listOf(false, true)) {
                compose.runOnIdle {
                    dark.value = night
                    style.value = if (card) FlowArticleListCardStylePreference.Segmented else FlowArticleListCardStylePreference.Divider
                }
                val origin = list.fetchSemanticsNode().boundsInRoot
                val first = bounds(titles[0])
                val next = bounds(titles[1])
                val time = bounds("06:00")
                val heading = bounds("Today")
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                val image = list.captureToImage().asAndroidBitmap()
                val outline = scheme.outlineVariant.toArgb()
                val y = (first.center.y - origin.top).roundToInt()
                val edge = (first.left - origin.left).roundToInt()
                // Inspect only the quiet side gutters, outside titles and thumbnails.
                val leftBorder = (0 until edge).any { near(image.getPixel(it, y), outline) }
                val rightBorder = (image.width - edge until image.width).any { near(image.getPixel(it, y), outline) }
                val above = horizontalBands(image, heading.bottom - origin.top, first.top - origin.top, outline)
                val between = horizontalBands(image, time.bottom - origin.top, next.top - origin.top, outline)
                assertEquals("Left frame edge", card, leftBorder)
                assertEquals("Right frame edge", card, rightBorder)
                assertEquals("Top edge of the first article", if (card) 1 else 0, above)
                assertEquals("Bottom then top of independent cards, or one editorial rule", if (card) 2 else 1, between)
                val themeName = if (night) "dark" else "light"
                val frameName = if (card) "card" else "editorial"
                val directory = context.filesDir.resolve("issue4-redesign").apply { mkdirs() }
                directory.resolve("$themeName-$frameName.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        }
    }

    private fun renderList() {
        val feed = Feed(id = "film", name = "/Film", url = "https://example.test/feed", groupId = "film-group", accountId = 1)
        val times = listOf("06:00", "05:00", "04:45")
        val rows = listOf(ArticleFlowItem.Date("Today", false)) + titles.mapIndexed { index, title ->
            val number = index + 1
            val localPhoto = context.filesDir.resolve("issue4-fixtures/$number.jpg")
            // The app's bundled settings-preview WebP keeps clean CI installs self-contained.
            val image = if (localPhoto.isFile) Uri.fromFile(localPhoto).toString()
                else "android.resource://" + context.packageName + "/" + R.drawable.animation
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "film-$number", date = Date(0),
                title = title, rawDescription = summaries[index], shortDescription = summaries[index],
                img = image, link = "https://example.test/film/$number", feedId = feed.id, accountId = 1
            ).apply { dateString = times[index] }, feed))
        }
        compose.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(
                    LocalDarkTheme provides if (dark.value) DarkThemePreference.ON else DarkThemePreference.OFF,
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                    LocalFlowArticleListDesc provides FlowArticleListDescPreference.SHORT,
                    LocalFlowArticleListFeedIcon provides FlowArticleListFeedIconPreference.ON,
                    LocalFlowArticleListFeedName provides FlowArticleListFeedNamePreference.ON,
                    LocalFlowArticleListImage provides FlowArticleListImagePreference.ON,
                    LocalFlowArticleListTime provides FlowArticleListTimePreference.ON,
                ) {
                    val data = remember { flowOf(PagingData.from(rows)) }.collectAsLazyPagingItems()
                    LazyColumn(Modifier.widthIn(max = 400.dp).fillMaxWidth().fillMaxHeight()
                        .background(articleListBackgroundColor(style.value, 0)).testTag("film-list")) {
                        ArticleList(pagingItems = data, diffMap = emptyMap(), isShowStickyHeader = false,
                            articleListTonalElevation = 0, cardStyle = style.value, isSingleFeed = singleFeed.value,
                            isMenuEnabled = false, onClick = { article, _ -> clicked = article.article.id })
                    }
                }
            }
        }
        compose.onNodeWithText(titles.first()).assertIsDisplayed()
    }

    private fun bounds(text: String, substring: Boolean = false): Rect =
        compose.onNodeWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun horizontalBands(image: Bitmap, from: Float, to: Float, color: Int): Int {
        var bands = 0
        var previous = false
        for (y in from.roundToInt().coerceIn(0, image.height) until to.roundToInt().coerceIn(0, image.height)) {
            val hit = near(image.getPixel(image.width / 3, y), color) && near(image.getPixel(image.width * 2 / 3, y), color)
            if (hit && !previous) bands++
            previous = hit
        }
        return bands
    }

    private fun near(pixel: Int, color: Int): Boolean = listOf(16, 8, 0).all { shift ->
        abs(((pixel ushr shift) and 255) - ((color ushr shift) and 255)) <= 2
    }

    private val titles = listOf(
        "Leonard Nimoy's 5 Best Cameo Appearances, Ranked",
        "Author Andy Weir Will Write A Project Hail Mary Sequel Under One Condition [Exclusive]",
        "5 Best Western Movies Where The Main Character Dies",
    )
    private val summaries = listOf(
        "Whether Leonard Nimoy was playing a villain of the week or himself in animated form, his cameos could steal a scene.",
        "In a new interview, Project Hail Mary author Andy Weir explains what it would take for him to write a sequel.",
        "Some of the best Western movies ever made end with their leads dying. These five films put a different spin on riding into the sunset.",
    )
}
