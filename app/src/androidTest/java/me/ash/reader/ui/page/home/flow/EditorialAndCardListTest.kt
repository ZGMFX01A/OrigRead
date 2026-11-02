package me.ash.reader.ui.page.home.flow

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import android.util.Log
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.flowOf
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.preference.AmoledDarkThemePreference
import me.ash.reader.infrastructure.preference.BasicFontsPreference
import me.ash.reader.infrastructure.preference.DarkThemePreference
import me.ash.reader.infrastructure.preference.FlowArticleListCardStylePreference
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.FlowArticleListFeedIconPreference
import me.ash.reader.infrastructure.preference.FlowArticleListFeedNamePreference
import me.ash.reader.infrastructure.preference.FlowArticleListImagePreference
import me.ash.reader.infrastructure.preference.FlowArticleListTimePreference
import me.ash.reader.infrastructure.preference.LocalAmoledDarkTheme
import me.ash.reader.infrastructure.preference.LocalBasicFonts
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Production paging rows, production typography and locally replayed translations; no account or network. */
class EditorialAndCardListTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val style = mutableStateOf<FlowArticleListCardStylePreference>(FlowArticleListCardStylePreference.Divider)
    private val dark = mutableStateOf(false)
    private val singleFeed = mutableStateOf(false)
    private val includeDates = mutableStateOf(true)
    private val sticky = mutableStateOf(false)
    private val showTime = mutableStateOf(true)
    private val amoled = mutableStateOf(false)
    private val copyMode = mutableStateOf(CopyMode.Original)
    private enum class CopyMode { Original, Translated, Bilingual }
    private data class Palette(val page: Int, val card: Int, val line: Int, val flatPage: Int, val previousDarkCard: Int)
    private lateinit var palette: Palette
    private val list get() = compose.onNodeWithTag("film-list")
    private var clicked: String? = null

    @Test fun styleSwitchPreservesTheTitleAxisAndWrapAndKeepsMetadataAboveTheHeadline() {
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
                .fetchSemanticsNodes().minBy { it.boundsInRoot.top }.boundsInRoot
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
            assertTrue("Scope and time form the eyebrow above the headline", maxOf(source.bottom, time.bottom) <= title.top + 1f)
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

    @Test fun fullListUsesOneContinuousDaySurfaceAndKeepsOneHairlineInBothThemesAndLanguages() {
        singleFeed.value = true
        renderList()
        for (night in listOf(false, true)) for (card in listOf(false, true)) {
            compose.runOnIdle {
                dark.value = night
                style.value = if (card) FlowArticleListCardStylePreference.Segmented else FlowArticleListCardStylePreference.Divider
            }
            val themeName = if (night) "dark" else "light"
            val frameName = if (card) "card" else "editorial"
            for (translated in listOf(false, true)) {
                compose.runOnIdle { copyMode.value = if (translated) CopyMode.Translated else CopyMode.Original }
                val first = bounds(if (translated) zhTitles[0] else titles[0])
                val summary = bounds(if (translated) zhSummaries[0] else summaries[0])
                val nextEyebrow = bounds("05:00")
                val heading = bounds("Today")
                val eyebrow = bounds("06:00")
                assertTrue("The second eyebrow follows the first summary", summary.bottom < nextEyebrow.top)
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                // Save the whole activity. Sample relative to the actual list, not the screen origin.
                saveScreen("$themeName-$frameName-" + if (translated) "zh" else "en")
                val origin = list.fetchSemanticsNode().boundsInRoot
                val image = list.captureToImage().asAndroidBitmap()
                val edge = (first.left - origin.left).roundToInt()
                val gutter = (edge * 0.70f).roundToInt()
                val y = (first.center.y - origin.top).roundToInt()
                val fill = if (card) palette.card else palette.page
                val actualPage = image.getPixel(1, y)
                val actualFace = image.getPixel(gutter, y)
                assertTrue("Page outside the group", near(actualPage, palette.page))
                assertTrue("Actual row face", near(actualFace, fill))
                if (card) {
                    assertTrue("The card face must differ from the page", !near(actualFace, actualPage))
                    if (night && !translated) assertRaisedDarkCard("dark", actualFace, actualPage, 1.16)
                    val headingEnd = heading.bottom - origin.top
                    val firstCardPixel = (headingEnd.roundToInt()..(eyebrow.top - origin.top).roundToInt())
                        .first { near(image.getPixel(image.width / 2, it), fill) }
                    assertEquals("Space outside the card, from the date text to the group face",
                        12f * context.resources.displayMetrics.density, firstCardPixel - headingEnd, 2f)
                }
                assertTrue("No side strokes on either style",
                    (1 until edge).none { near(image.getPixel(it, y), palette.line) } &&
                        (image.width - edge until image.width - 1).none { near(image.getPixel(it, y), palette.line) })
                val gap = (summary.bottom - origin.top).roundToInt() until (nextEyebrow.top - origin.top).roundToInt()
                assertTrue("No page-colored slit between same-day rows",
                    gap.all { near(image.getPixel(gutter, it), fill) && near(image.getPixel(image.width - 1 - gutter, it), fill) })
                assertEquals("No upper frame stroke", 0,
                    linePixels(image, heading.bottom - origin.top, eyebrow.top - origin.top))
                assertEquals("Exactly one physical-pixel rule between same-day articles", 1,
                    linePixels(image, summary.bottom - origin.top, nextEyebrow.top - origin.top))
            }
        }
        compose.runOnIdle { dark.value = false; copyMode.value = CopyMode.Bilingual }
        val original = bounds(titles[0])
        val translation = bounds(zhTitles[0])
        assertTrue("Bilingual mode keeps both headline nodes", original.bottom <= translation.top + 1f)
        compose.mainClock.advanceTimeBy(500)
        saveScreen("light-card-bilingual")
    }

    @Test fun localMidnightBreaksGroupsWithOrWithoutDateRowsAndStickyHeadersOnAmoled() {
        style.value = FlowArticleListCardStylePreference.Segmented
        singleFeed.value = true
        dark.value = true
        amoled.value = true
        showTime.value = false
        renderList(splitThirdDay = true)
        for (withDates in listOf(true, false)) for (pinned in listOf(false, true)) {
            compose.runOnIdle { includeDates.value = withDates; sticky.value = pinned }
            list.performScrollToNode(hasText(titles[2]))
            compose.onNodeWithText("04:45", useUnmergedTree = true).assertDoesNotExist()
            if (!withDates) compose.onNodeWithText("Yesterday", useUnmergedTree = true).assertDoesNotExist()
            val second = bounds(summaries[1])
            val third = bounds(titles[2])
            val origin = list.fetchSemanticsNode().boundsInRoot
            val image = list.captureToImage().asAndroidBitmap()
            val x = ((third.left - origin.left) * 0.70f).roundToInt()
            val from = (second.bottom - origin.top).roundToInt().coerceIn(0, image.height)
            val to = (third.top - origin.top).roundToInt().coerceIn(0, image.height)
            assertEquals(Color.Black.toArgb(), palette.page)
            val y = (third.center.y - origin.top).roundToInt().coerceIn(0, image.height - 1)
            assertEquals("The outer canvas stays black", Color.Black.toArgb(), image.getPixel(1, y))
            if (withDates && !pinned) assertRaisedDarkCard("amoled", image.getPixel(x, y), image.getPixel(1, y), 1.12)
            assertTrue("A real cross-day gap exposes the AMOLED page",
                (from until to).count { near(image.getPixel(x, it), palette.page) } >= (x / 2).coerceAtLeast(2))
            assertEquals("No in-group rule where the day changes", 0, linePixels(image, from.toFloat(), to.toFloat()))
        }
    }

    private fun renderList(splitThirdDay: Boolean = false) {
        val feed = Feed(id = "film", name = "/Film", url = "https://example.test/feed", groupId = "film-group", accountId = 1)
        val times = listOf("06:00", "05:00", "04:45")
        val articles = titles.mapIndexed { index, title ->
            val number = index + 1
            val localPhoto = context.filesDir.resolve("issue4-fixtures/$number.jpg")
            val image = if (localPhoto.isFile) Uri.fromFile(localPhoto).toString()
                else "android.resource://" + context.packageName + "/" + R.drawable.animation
            val date = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.NOVEMBER, if (splitThirdDay && index == 2) 1 else 2,
                    times[index].substringBefore(':').toInt(), times[index].substringAfter(':').toInt())
            }.time
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "film-$number", date = date,
                title = title, rawDescription = summaries[index], shortDescription = summaries[index],
                img = image, link = "https://example.test/film/$number", feedId = feed.id, accountId = 1
            ).apply { dateString = times[index] }, feed))
        }
        val translated = articles.mapIndexed { i, row ->
            val article = row.articleWithFeed.article
            article.id to ListTranslationResult(
                ListTranslationInput.of(article.accountId, article.id, article.title, article.shortDescription, article.feedId),
                zhTitles[i], zhSummaries[i],
            )
        }.toMap()
        compose.setContent {
            CompositionLocalProvider(
                LocalDarkTheme provides if (dark.value) DarkThemePreference.ON else DarkThemePreference.OFF,
                LocalAmoledDarkTheme provides if (amoled.value) AmoledDarkThemePreference.ON else AmoledDarkThemePreference.OFF,
                LocalBasicFonts provides BasicFontsPreference.GoogleSans,
                LocalLayoutDirection provides LayoutDirection.Ltr,
                LocalFlowArticleListDesc provides FlowArticleListDescPreference.SHORT,
                LocalFlowArticleListFeedIcon provides FlowArticleListFeedIconPreference.ON,
                LocalFlowArticleListFeedName provides FlowArticleListFeedNamePreference.ON,
                LocalFlowArticleListImage provides FlowArticleListImagePreference.ON,
                LocalFlowArticleListTime provides if (showTime.value) FlowArticleListTimePreference.ON else FlowArticleListTimePreference.OFF,
            ) {
                AppTheme(useDarkTheme = dark.value) {
                    val data = remember(includeDates.value) {
                        val rows = buildList<ArticleFlowItem> {
                            for ((i, row) in articles.withIndex()) {
                                if (includeDates.value && i == 0) add(ArticleFlowItem.Date("Today", false))
                                if (includeDates.value && splitThirdDay && i == 2) add(ArticleFlowItem.Date("Yesterday", true))
                                add(row)
                            }
                        }
                        flowOf(PagingData.from(rows))
                    }.collectAsLazyPagingItems()
                    val page = articleListBackgroundColor(style.value, 0)
                    val colors = with(MaterialTheme.colorScheme) {
                        Palette(page.toArgb(), articleListCardColor().toArgb(), outlineVariant.toArgb(),
                            surface.toArgb(), surfaceContainerLow.toArgb())
                    }
                    SideEffect { palette = colors }
                    LazyColumn(Modifier.widthIn(max = 400.dp).fillMaxWidth().fillMaxHeight()
                        .background(page).testTag("film-list")) {
                        ArticleList(pagingItems = data, diffMap = emptyMap(), isShowStickyHeader = sticky.value,
                            articleListTonalElevation = 0, cardStyle = style.value, isSingleFeed = singleFeed.value,
                            translations = if (copyMode.value == CopyMode.Original) emptyMap() else translated,
                            translationDisplayMode = if (copyMode.value == CopyMode.Bilingual) TranslationDisplayMode.BILINGUAL
                                else TranslationDisplayMode.TRANSLATED,
                            translationRevealRevision = copyMode.value.ordinal,
                            isMenuEnabled = false, onClick = { article, _ -> clicked = article.article.id })
                    }
                }
            }
        }
        compose.onNodeWithText(titles.first()).assertIsDisplayed()
    }

    private fun saveScreen(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = context.filesDir.resolve("issue4-redesign").apply { mkdirs() }
        directory.resolve("$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun bounds(text: String, substring: Boolean = false): Rect =
        compose.onNodeWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun linePixels(image: Bitmap, from: Float, to: Float): Int {
        val rows = from.roundToInt().coerceIn(0, image.height) until to.roundToInt().coerceIn(0, image.height)
        return rows.count { y -> near(image.getPixel(image.width / 3, y), palette.line) &&
            near(image.getPixel(image.width * 2 / 3, y), palette.line) }
    }

    private fun assertRaisedDarkCard(label: String, face: Int, page: Int, minimumRatio: Double) {
        fun contrast(lighter: Int, darker: Int) =
            (android.graphics.Color.luminance(lighter) + 0.05) / (android.graphics.Color.luminance(darker) + 0.05)
        val actual = contrast(face, page)
        val previous = contrast(palette.previousDarkCard, palette.flatPage)
        Log.i("ArticleListTone", "$label face=" + Integer.toHexString(face) + " page=" + Integer.toHexString(page) +
            " contrast=$actual previous=$previous")
        assertTrue("$label group must be visibly brighter than the page: $actual (previous $previous)",
            actual >= minimumRatio && actual > previous + 0.02)
    }

    private fun near(pixel: Int, color: Int): Boolean = listOf(16, 8, 0).all { shift ->
        abs(((pixel ushr shift) and 255) - ((color ushr shift) and 255)) <= 2
    }

    private val titles = listOf(
        "Leonard Nimoy's 5 Best Cameo Appearances, Ranked",
        "Author Andy Weir Will Write A Project Hail Mary Sequel Under One Condition [Exclusive]",
        "5 Best Western Movies Where The Main Character Dies",
    )
    private val zhTitles = listOf(
        "伦纳德·尼莫伊的五次最佳客串演出，哪一部最精彩？",
        "《挽救计划》作者 Andy Weir：只有满足一个条件，我才会写续集",
        "五部主角最终殒命的经典西部片",
    )
    private val zhSummaries = listOf(
        "无论是出演反派，还是为动画中的自己配音，伦纳德·尼莫伊总能让短暂的客串令人难忘。",
        "Andy Weir 在采访中解释，什么样的故事才值得成为 Project Hail Mary 的续集。",
        "西部片的英雄不一定能骑马走进夕阳。来看看这五部作品出人意料的结局。",
    )
    private val summaries = listOf(
        "Whether Leonard Nimoy was playing a villain of the week or himself in animated form, his cameos could steal a scene.",
        "In a new interview, Project Hail Mary author Andy Weir explains what it would take for him to write a sequel.",
        "Some of the best Western movies ever made end with their leads dying. These five films put a different spin on riding into the sunset.",
    )
}
