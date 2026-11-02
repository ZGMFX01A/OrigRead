package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.data.Diff
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.infrastructure.preference.FlowArticleListCardStylePreference
import me.ash.reader.infrastructure.translation.TranslationDisplayMode

@Suppress("FunctionName")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
fun LazyListScope.ArticleList(
    pagingItems: LazyPagingItems<ArticleFlowItem>,
    diffMap: Map<String, Diff>,
    // Kept for named callers of the previous layout; feed icons no longer move date headings.
    @Suppress("UNUSED_PARAMETER") isShowFeedIcon: Boolean = false,
    isShowStickyHeader: Boolean,
    articleListTonalElevation: Int,
    cardStyle: FlowArticleListCardStylePreference = FlowArticleListCardStylePreference.default,
    translations: Map<String, ListTranslationResult> = emptyMap(),
    translationDisplayMode: TranslationDisplayMode = TranslationDisplayMode.TRANSLATED,
    translationRevealRevision: Int = 0,
    isSwipeEnabled: () -> Boolean = { false },
    isMenuEnabled: Boolean = true,
    onClick: (ArticleWithFeed, Int) -> Unit = { _, _ -> },
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
    isSingleFeed: Boolean = false,
) {
    val zone = TimeZone.getDefault()

    @Composable
    fun LazyItemScope.ArticleEntry(index: Int, item: ArticleFlowItem.Article) {
        val article = item.articleWithFeed.article
        val day = item.localDay(zone)
        val previous = if (index > 0) pagingItems.peek(index - 1) as? ArticleFlowItem.Article else null
        val next = if (index + 1 < pagingItems.itemCount) pagingItems.peek(index + 1) as? ArticleFlowItem.Article else null
        val hasPrevious = previous?.localDay(zone) == day
        val hasNext = next?.localDay(zone) == day
        // Paging normally emits a Date item that owns this gap. A headerless
        // presentation still ends the previous day's group without adding an index.
        val unlabeledDayStart = previous != null && !hasPrevious
        SwipeableArticleItem(
            modifier =
                Modifier.animateItem(
                    fadeInSpec = null,
                    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                ).then(if (unlabeledDayStart) Modifier.padding(top = DateGroupSpacing) else Modifier),
            articleWithFeed = item.articleWithFeed,
            translation = translations[article.id],
            translationDisplayMode = translationDisplayMode,
            translationRevealRevision = translationRevealRevision,
            isUnread = diffMap[article.id]?.isUnread ?: article.isUnread,
            articleListTonalElevation = articleListTonalElevation,
            cardStyle = cardStyle,
            isSingleFeed = isSingleFeed,
            hasPreviousArticleInGroup = hasPrevious,
            hasNextArticleInGroup = hasNext,
            onClick = { onClick(it, index) },
            isSwipeEnabled = isSwipeEnabled,
            isMenuEnabled = isMenuEnabled,
            onToggleStarred = onToggleStarred,
            onToggleRead = onToggleRead,
            onMarkAboveAsRead =
                if (index == 0 || (index == 1 && previous == null)) null
                else onMarkAboveAsRead,
            onMarkBelowAsRead =
                if (index == pagingItems.itemCount - 1) null else onMarkBelowAsRead,
            onShare = onShare,
        )
    }

    // https://issuetracker.google.com/issues/193785330
    // FIXME: Using sticky header with paging-compose need to iterate through the entire list
    //  to figure out where to add sticky headers, which significantly impacts the performance
    if (!isShowStickyHeader) {
        items(
            count = pagingItems.itemCount,
            key = pagingItems.itemKey(::key),
            contentType = pagingItems.itemContentType(::contentType),
        ) { index ->
            when (val item = pagingItems[index]) {
                is ArticleFlowItem.Article -> {
                    ArticleEntry(index, item)
                }

                is ArticleFlowItem.Date -> {
                    if (item.showSpacer) {
                        Spacer(modifier = Modifier.height(DateGroupSpacing))
                    }
                    StickyHeader(item.date, articleListTonalElevation, cardStyle)
                }

                else -> {}
            }
        }
    } else {
        for (index in 0 until pagingItems.itemCount) {
            when (val item = pagingItems.peek(index)) {
                is ArticleFlowItem.Article -> {
                    item(key = key(item), contentType = contentType(item)) {
                        ArticleEntry(index, item)
                    }
                }

                is ArticleFlowItem.Date -> {
                    if (item.showSpacer) {
                        item { Spacer(modifier = Modifier.height(DateGroupSpacing)) }
                    }
                    stickyHeader(key = key(item), contentType = contentType(item)) {
                        StickyHeader(item.date, articleListTonalElevation, cardStyle)
                    }
                }

                else -> {}
            }
        }
    }
}

private val DateGroupSpacing = 28.dp

// Compare calendar days in the same local time zone used for the date headings.
private fun ArticleFlowItem.Article.localDay(zone: TimeZone): Long {
    val millis = articleWithFeed.article.date.time
    return Math.floorDiv(millis + zone.getOffset(millis), TimeUnit.DAYS.toMillis(1))
}

private fun key(item: ArticleFlowItem): String {
    return when (item) {
        is ArticleFlowItem.Article -> item.articleWithFeed.article.id
        is ArticleFlowItem.Date -> item.date
    }
}

private fun contentType(item: ArticleFlowItem): Int {
    return when (item) {
        is ArticleFlowItem.Article -> CONTENT_TYPE_ARTICLE
        is ArticleFlowItem.Date -> CONTENT_TYPE_DATE_HEADER
    }
}

const val CONTENT_TYPE_ARTICLE = 1
const val CONTENT_TYPE_DATE_HEADER = 2
