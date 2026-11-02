package me.ash.reader.ui.page.home.flow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.Shape
import me.ash.reader.infrastructure.preference.FlowArticleListCardStylePreference
import me.ash.reader.infrastructure.preference.LocalFlowArticleListCardStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import coil.size.Precision
import coil.size.Scale
import me.ash.reader.R
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference
import me.ash.reader.infrastructure.preference.LocalArticleListSwipeEndAction
import me.ash.reader.infrastructure.preference.LocalArticleListSwipeStartAction
import me.ash.reader.infrastructure.preference.LocalBasicFonts
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTonalElevation
import me.ash.reader.infrastructure.preference.SwipeEndActionPreference
import me.ash.reader.infrastructure.preference.SwipeStartActionPreference
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.base.OrigReadAsyncImage
import me.ash.reader.ui.component.base.SIZE_1000
import me.ash.reader.ui.component.menu.AnimatedDropdownMenu
import me.ash.reader.ui.component.swipe.SwipeAction
import me.ash.reader.ui.component.swipe.SwipeableActionsBox
import me.ash.reader.ui.ext.requiresBidi
import me.ash.reader.ui.page.settings.color.flow.generateArticleWithFeedPreview
import me.ash.reader.ui.theme.applyTextDirection

private val ArticleCardShape = RoundedCornerShape(16.dp)
private val ThumbnailShape = RoundedCornerShape(8.dp)
private val CardInset = 12.dp
private val ThumbnailSize = 76.dp

private val ArticleGroupStart = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val ArticleGroupEnd = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

private fun articleGroupShape(hasPrevious: Boolean, hasNext: Boolean): Shape = when {
    !hasPrevious && !hasNext -> ArticleCardShape
    !hasPrevious -> ArticleGroupStart
    !hasNext -> ArticleGroupEnd
    else -> RectangleShape
}

private data class ArticleRowData(
    val feedName: String,
    val feedIconUrl: String?,
    val title: String,
    val shortDescription: String,
    val translatedTitle: String?,
    val translatedDescription: String?,
    val time: String?,
    val image: Any?,
    val starred: Boolean,
    val unread: Boolean,
)

@Composable
private fun ArticleWithFeed.rowData(
    isUnread: Boolean,
    translation: ListTranslationResult?,
): ArticleRowData {
    val includeDescription = LocalFlowArticleListDesc.current != FlowArticleListDescPreference.NONE
    // A cached translation belongs to the exact source text currently in the list.
    val visible = translation?.takeIf {
        it.showTranslation && it.isFresh() && it.source == ListTranslationInput.of(
            article.accountId, article.id, article.title,
            if (includeDescription) article.shortDescription else "", article.feedId,
        )
    }
    return ArticleRowData(
        feedName = feed.name,
        feedIconUrl = feed.icon,
        title = article.title,
        shortDescription = article.shortDescription,
        translatedTitle = visible?.title?.takeIf(String::isNotBlank),
        translatedDescription = visible?.description?.takeIf(String::isNotBlank),
        time = article.dateString,
        image = article.img,
        starred = article.isStarred,
        unread = isUnread,
    )
}

@Composable
fun ArticleItem(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    cardStyle: FlowArticleListCardStylePreference = LocalFlowArticleListCardStyle.current,
    cardShape: Shape = ArticleCardShape,
    translation: ListTranslationResult? = null,
    translationDisplayMode: TranslationDisplayMode = TranslationDisplayMode.TRANSLATED,
    translationRevealRevision: Int = 0,
    isSingleFeed: Boolean = false,
    onClick: (ArticleWithFeed) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    ArticleItemFrame(modifier, cardStyle, cardShape) { background ->
        ArticleRowContent(
            data = articleWithFeed.rowData(isUnread, translation),
            cardStyle = cardStyle,
            background = background,
            isSingleFeed = isSingleFeed,
            bilingual = translationDisplayMode == TranslationDisplayMode.BILINGUAL,
            translationRevealRevision = translationRevealRevision,
            onClick = { onClick(articleWithFeed) },
            onLongClick = onLongClick,
        )
    }
}

@Composable
fun ArticleItem(
    modifier: Modifier = Modifier,
    feedName: String = "",
    feedIconUrl: String? = null,
    title: String = "",
    shortDescription: String = "",
    translatedTitle: String? = null,
    translatedDescription: String? = null,
    bilingual: Boolean = false,
    translationRevealRevision: Int = 0,
    timeString: String? = null,
    imgData: Any? = null,
    isStarred: Boolean = false,
    isUnread: Boolean = false,
    cardStyle: FlowArticleListCardStylePreference = FlowArticleListCardStylePreference.Divider,
    cardShape: Shape = ArticleCardShape,
    isSingleFeed: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    ArticleItemFrame(modifier, cardStyle, cardShape) { background ->
        ArticleRowContent(
            data = ArticleRowData(
                feedName, feedIconUrl, title, shortDescription,
                translatedTitle, translatedDescription, timeString, imgData, isStarred, isUnread,
            ),
            cardStyle = cardStyle,
            background = background,
            isSingleFeed = isSingleFeed,
            bilingual = bilingual,
            translationRevealRevision = translationRevealRevision,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun ArticleItemFrame(
    modifier: Modifier,
    cardStyle: FlowArticleListCardStylePreference,
    cardShape: Shape,
    articleListTonalElevation: Int = LocalFlowArticleListTonalElevation.current.value,
    content: @Composable (Color) -> Unit,
) {
    val fill = if (cardStyle == FlowArticleListCardStylePreference.Segmented) articleListCardColor()
        else articleListBackgroundColor(cardStyle, articleListTonalElevation)
    val background = fill.compositeOver(MaterialTheme.colorScheme.surface.copy(alpha = 1f))
    if (cardStyle == FlowArticleListCardStylePreference.Segmented) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = CardInset),
            shape = cardShape,
            color = background,
        ) { content(background) }
    } else {
        Box(modifier.fillMaxWidth().background(background)) { content(background) }
    }
}

internal data class ArticleSupportingColors(val summary: Color, val metadata: Color)

/** Use the actual row fill, including its tonal elevation. Keep high-contrast palettes strong
 * by stepping back a fixed amount of contrast rather than capping every theme at the minimum. */
internal fun articleSupportingColors(background: Color, onSurfaceVariant: Color, onSurface: Color): ArticleSupportingColors {
    val bg = background.toArgb()
    val variant = ColorUtils.compositeColors(onSurfaceVariant.toArgb(), bg)
    val strong = ColorUtils.compositeColors(onSurface.toArgb(), bg)
    val baseContrast = ColorUtils.calculateContrast(variant, bg).toFloat()
    fun tone(floor: Float, reduction: Float): Color {
        val target = maxOf(floor, baseContrast - reduction)
        val ink = when {
            baseContrast >= target -> variant
            ColorUtils.calculateContrast(strong, bg) >= target -> strong
            ColorUtils.calculateContrast(Color.Black.toArgb(), bg) >
                ColorUtils.calculateContrast(Color.White.toArgb(), bg) -> Color.Black.toArgb()
            else -> Color.White.toArgb()
        }
        val alpha = ColorUtils.calculateMinimumAlpha(ink, bg, target)
        return if (alpha < 0) Color(ink)
        else Color(ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ink, alpha), bg))
    }
    return ArticleSupportingColors(summary = tone(4.9f, 1.2f), metadata = tone(4.5f, 2.8f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleRowContent(
    data: ArticleRowData,
    cardStyle: FlowArticleListCardStylePreference,
    background: Color,
    isSingleFeed: Boolean,
    bilingual: Boolean,
    translationRevealRevision: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    hasPreviousArticleInGroup: Boolean = false,
    hasNextArticleInGroup: Boolean = false,
) {
    val descriptionLines = when (LocalFlowArticleListDesc.current) {
        FlowArticleListDescPreference.NONE -> 0
        FlowArticleListDescPreference.SHORT -> 2
        FlowArticleListDescPreference.LONG -> 4
    }
    val title = if (bilingual) data.title else data.translatedTitle ?: data.title
    val description = if (bilingual) data.shortDescription else data.translatedDescription ?: data.shortDescription
    val secondTitle = data.translatedTitle?.takeIf { bilingual && it.isNotBlank() && it != title }
    val secondDescription = data.translatedDescription?.takeIf { bilingual && it.isNotBlank() && it != description }
    val hasDescription = descriptionLines > 0 && (description.isNotBlank() || secondDescription != null)
    val titleLines = if (hasDescription) 3 else 4
    val muted = !data.unread && when (LocalFlowArticleListReadIndicator.current) {
        FlowArticleReadIndicatorPreference.None -> false
        FlowArticleReadIndicatorPreference.AllRead -> true
        FlowArticleReadIndicatorPreference.ExcludingStarred -> !data.starred
    }
    val colors = MaterialTheme.colorScheme
    val themeTitle = MaterialTheme.typography.titleMedium
    val titleStyle = themeTitle.copy(
        fontSize = themeTitle.fontSize * (18f / 16f),
        lineHeight = themeTitle.lineHeight * (23f / 24f),
        fontWeight = if (muted) FontWeight.Normal else FontWeight.SemiBold,
    )
    val titleColor = if (muted) colors.onSurfaceVariant else colors.onSurface
    val supporting = remember(background, colors.onSurfaceVariant, colors.onSurface) {
        articleSupportingColors(background, colors.onSurfaceVariant, colors.onSurface)
    }
    var imageFailed by remember(data.image) { mutableStateOf(false) }
    val showImage = LocalFlowArticleListImage.current.value && hasUsableArticleImage(data.image) && !imageFailed
    val contentAlpha = me.ash.reader.ui.motion.rememberOrigReadContentAlpha(bilingual to translationRevealRevision)
    val isCard = cardStyle == FlowArticleListCardStylePreference.Segmented
    val inset = if (isCard) CardInset else ArticleListContentInset
    // A bilingual preference alone doesn't add a second copy (for example before a translation arrives).
    val dynamicShort = descriptionLines == 2 && hasDescription && secondTitle == null && secondDescription == null
    Column(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                start = inset, end = inset,
                top = when {
                    !isCard -> 16.dp
                    !hasPreviousArticleInGroup -> CardInset
                    else -> 14.dp
                },
                bottom = if (isCard && !hasNextArticleInGroup) CardInset else 14.dp,
            ),
    ) {
        ArticleTextWithThumbnail(
            image = if (showImage) data.image else null,
            onError = { imageFailed = true },
        ) {
            Column(Modifier.fillMaxWidth()) {
                ArticleMetadata(data, isSingleFeed, supporting.metadata)
                Column(Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha.value }) {
                    if (dynamicShort) {
                        BudgetedArticleCopy(
                            title = { measured ->
                                ArticleTitle(title, titleStyle, titleColor, titleLines, muted, onTextLayout = measured)
                            },
                            summary = { lines ->
                                ArticleRowText(description, MaterialTheme.typography.bodyMedium, supporting.summary,
                                    lines, Modifier.padding(top = 6.dp))
                            },
                        )
                    } else {
                        ArticleTitle(title, titleStyle, titleColor, titleLines, muted)
                        if (secondTitle != null) {
                            ArticleTitle(secondTitle, titleStyle, titleColor, titleLines, muted, Modifier.padding(top = 4.dp))
                        }
                        if (hasDescription && description.isNotBlank()) {
                            ArticleRowText(description, MaterialTheme.typography.bodyMedium, supporting.summary,
                                descriptionLines, Modifier.padding(top = 6.dp))
                        }
                        if (hasDescription && secondDescription != null) {
                            ArticleRowText(secondDescription, MaterialTheme.typography.bodyMedium, supporting.summary,
                                descriptionLines, Modifier.padding(top = if (description.isBlank()) 6.dp else 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleTitle(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val basicFonts = LocalBasicFonts.current
    Text(
        text = remember(text, basicFonts, muted) { articleTitleText(text, basicFonts, muted) },
        modifier = modifier,
        style = remember(text, style) { style.applyTextDirection(text.requiresBidi()) },
        color = color,
        maxLines = maxLines,
        onTextLayout = onTextLayout,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ArticleTextWithThumbnail(
    image: Any?,
    onError: () -> Unit,
    text: @Composable () -> Unit,
) {
    if (image == null) {
        text()
        return
    }
    // Reserve a bounded width once. The thumbnail follows the measured copy height, never the reverse.
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            text()
            OrigReadAsyncImage(
                data = image,
                scale = Scale.FIT,
                precision = Precision.INEXACT,
                size = SIZE_1000,
                fittedShape = ThumbnailShape,
                onError = onError,
            )
        },
    ) { measurables, constraints ->
        val maxEdge = minOf(ThumbnailSize.roundToPx(), constraints.maxWidth / 4, constraints.maxHeight)
        val gap = minOf(12.dp.roundToPx(), constraints.maxWidth - maxEdge)
        val reserved = maxEdge + gap
        val copy = measurables[0].measure(constraints.copy(
            minWidth = (constraints.minWidth - reserved).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth - reserved else Constraints.Infinity,
            minHeight = 0,
        ))
        // Long copy must never turn the thumbnail into a portrait crop; short copy can shrink the slot.
        val side = minOf(maxEdge, copy.height)
        val picture = measurables[1].measure(Constraints.fixed(side, side))
        val width = copy.width + reserved
        val height = maxOf(copy.height, constraints.minHeight)
        layout(width, height) {
            copy.placeRelative(0, (height - copy.height) / 2)
            picture.placeRelative(width - picture.width, (height - picture.height) / 2)
        }
    }
}

@Composable
private fun ArticleRowText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = remember(text, style) { style.applyTextDirection(text.requiresBidi()) },
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ArticleMetadata(data: ArticleRowData, isSingleFeed: Boolean, color: Color) {
    val showIcon = !isSingleFeed && LocalFlowArticleListFeedIcon.current.value &&
        (data.feedName.isNotBlank() || !data.feedIconUrl.isNullOrBlank())
    val showName = !isSingleFeed && LocalFlowArticleListFeedName.current.value && data.feedName.isNotBlank()
    val showTime = LocalFlowArticleListTime.current.value
    val time = data.time?.takeIf { showTime && it.isNotBlank() }
    if (!showIcon && !showName && time == null && !data.starred) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) FeedIcon(feedName = data.feedName, iconUrl = data.feedIconUrl, size = 14.dp)
        if (showName) {
            ArticleRowText(data.feedName, MaterialTheme.typography.labelMedium,
                color, 1, Modifier.weight(1f, fill = false))
        }
        if (time != null) {
            ArticleRowText(if (showName) "· $time" else time,
                MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color, 1)
        }
        if (data.starred) StarredIndicator(true)
    }
}

internal fun hasUsableArticleImage(imgData: Any?): Boolean =
    when (imgData) {
        is String -> imgData.isNotBlank()
        else -> imgData != null
    }

@Composable
fun StarredIcon(modifier: Modifier = Modifier) {
    val fontSize = LocalTextStyle.current.fontSize
    val iconSize = with(LocalDensity.current) { fontSize.toDp() }

    Icon(
        modifier = modifier
            .size(iconSize)
            .padding(end = 2.dp),
        imageVector = Icons.Rounded.Star,
        contentDescription = stringResource(R.string.starred),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StarredIndicator(visible: Boolean) {
    val iconSize = with(LocalDensity.current) { LocalTextStyle.current.fontSize.toDp() }
    Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) { StarredIcon() }
    }
}

@Composable
fun SwipeableArticleItem(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    articleListTonalElevation: Int = 0,
    hasNextArticleInGroup: Boolean = false,
    hasPreviousArticleInGroup: Boolean = false,
    cardStyle: FlowArticleListCardStylePreference = LocalFlowArticleListCardStyle.current,
    translation: ListTranslationResult? = null,
    translationDisplayMode: TranslationDisplayMode = TranslationDisplayMode.TRANSLATED,
    translationRevealRevision: Int = 0,
    isSingleFeed: Boolean = false,
    onClick: (ArticleWithFeed) -> Unit = {},
    isSwipeEnabled: () -> Boolean = { false },
    isMenuEnabled: Boolean = true,
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
) {
    val article = articleWithFeed.article
    var isMenuExpanded by remember(article.id, isMenuEnabled) { mutableStateOf(false) }
    var menuOffset by remember(article.id) { mutableStateOf(IntOffset.Zero) }
    val isCard = cardStyle == FlowArticleListCardStylePreference.Segmented
    val shape = articleGroupShape(hasPreviousArticleInGroup, hasNextArticleInGroup)

    ArticleItemFrame(modifier, cardStyle, shape, articleListTonalElevation) { background ->
        Column(Modifier.fillMaxWidth()) {
            SwipeActionBox(
                articleWithFeed = articleWithFeed,
                isRead = !isUnread,
                isStarred = article.isStarred,
                onToggleStarred = onToggleStarred,
                onToggleRead = onToggleRead,
            ) {
                val menuAnchor = if (isMenuEnabled) {
                    Modifier.pointerInput(article.id) {
                        awaitEachGesture {
                            menuOffset = awaitFirstDown(requireUnconsumed = false).position.round()
                        }
                    }
                } else Modifier
                Box(Modifier.fillMaxWidth().background(background).then(menuAnchor)) {
                    ArticleRowContent(
                        data = articleWithFeed.rowData(isUnread, translation),
                        cardStyle = cardStyle,
                        background = background,
                        isSingleFeed = isSingleFeed,
                        bilingual = translationDisplayMode == TranslationDisplayMode.BILINGUAL,
                        translationRevealRevision = translationRevealRevision,
                        hasPreviousArticleInGroup = hasPreviousArticleInGroup,
                        hasNextArticleInGroup = hasNextArticleInGroup,
                        onClick = { onClick(articleWithFeed) },
                        onLongClick = if (isMenuEnabled) ({ isMenuExpanded = true }) else null,
                    )
                    if (isMenuEnabled) {
                        AnimatedDropdownMenu(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            expanded = isMenuExpanded,
                            onDismissRequest = { isMenuExpanded = false },
                            offset = menuOffset,
                        ) {
                            ArticleItemMenuContent(
                                articleWithFeed = articleWithFeed,
                                isStarred = article.isStarred,
                                isRead = !isUnread,
                                onToggleStarred = onToggleStarred,
                                onToggleRead = onToggleRead,
                                onMarkAboveAsRead = onMarkAboveAsRead,
                                onMarkBelowAsRead = onMarkBelowAsRead,
                                onShare = onShare,
                            ) { isMenuExpanded = false }
                        }
                    }
                }
            }
            if (hasNextArticleInGroup) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = if (isCard) CardInset else ArticleListContentInset),
                    thickness = with(LocalDensity.current) { 1f.toDp() },
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

private enum class SwipeDirection {
    StartToEnd,
    EndToStart,
}

@Composable
private fun SwipeActionBox(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isStarred: Boolean,
    isRead: Boolean,
    onToggleStarred: (ArticleWithFeed) -> Unit,
    onToggleRead: (ArticleWithFeed) -> Unit,
    content: @Composable () -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.tertiaryContainer

    val swipeToStartAction = LocalArticleListSwipeStartAction.current
    val swipeToEndAction = LocalArticleListSwipeEndAction.current

    val onSwipeEndToStart =
        when (swipeToStartAction) {
            SwipeStartActionPreference.None -> null
            SwipeStartActionPreference.ToggleRead -> onToggleRead
            SwipeStartActionPreference.ToggleStarred -> onToggleStarred
        }

    val onSwipeStartToEnd =
        when (swipeToEndAction) {
            SwipeEndActionPreference.None -> null
            SwipeEndActionPreference.ToggleRead -> onToggleRead
            SwipeEndActionPreference.ToggleStarred -> onToggleStarred
        }

    if (onSwipeStartToEnd == null && onSwipeEndToStart == null) {
        content()
        return
    }

    val startAction =
        onSwipeStartToEnd?.let {
            SwipeAction(
                icon = {
                    swipeActionIcon(
                            direction = SwipeDirection.StartToEnd,
                            isStarred = isStarred,
                            isRead = isRead,
                        )
                        ?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                },
                background = containerColor,
                isUndo = false,
                onSwipe = { onSwipeStartToEnd.invoke(articleWithFeed) },
            )
        }

    val endAction =
        onSwipeEndToStart?.let {
            SwipeAction(
                icon = {
                    swipeActionIcon(
                            direction = SwipeDirection.EndToStart,
                            isStarred = isStarred,
                            isRead = isRead,
                        )
                        ?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                },
                background = containerColor,
                isUndo = false,
                onSwipe = { onSwipeEndToStart.invoke(articleWithFeed) },
            )
        }

    SwipeableActionsBox(
        modifier = modifier,
        startActions = listOfNotNull(startAction),
        endActions = listOfNotNull(endAction),
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surface,
    ) {
        content.invoke()
    }
}

@Composable
private fun swipeActionIcon(
    direction: SwipeDirection,
    isStarred: Boolean,
    isRead: Boolean,
): ImageVector? {
    val swipeToStartAction = LocalArticleListSwipeStartAction.current
    val swipeToEndAction = LocalArticleListSwipeEndAction.current

    val starImageVector =
        remember(isStarred) { if (isStarred) Icons.Outlined.StarOutline else Icons.Rounded.Star }

    val readImageVector =
        remember(isRead) { if (isRead) Icons.Outlined.Circle else Icons.Rounded.CheckCircleOutline }

    return remember(direction) {
        when (direction) {
            SwipeDirection.StartToEnd -> {

                when (swipeToEndAction) {
                    SwipeEndActionPreference.None -> null
                    SwipeEndActionPreference.ToggleRead -> readImageVector
                    SwipeEndActionPreference.ToggleStarred -> starImageVector
                }
            }

            SwipeDirection.EndToStart -> {
                when (swipeToStartAction) {
                    SwipeStartActionPreference.None -> null
                    SwipeStartActionPreference.ToggleRead -> readImageVector
                    SwipeStartActionPreference.ToggleStarred -> starImageVector
                }
            }
        }
    }
}

@Composable
private fun swipeActionText(
    direction: SwipeDirection,
    isStarred: Boolean,
    isRead: Boolean,
): String {
    val swipeToStartAction = LocalArticleListSwipeStartAction.current
    val swipeToEndAction = LocalArticleListSwipeEndAction.current

    val starText =
        stringResource(if (isStarred) R.string.mark_as_unstar else R.string.mark_as_starred)

    val readText = stringResource(if (isRead) R.string.mark_as_unread else R.string.mark_as_read)

    return remember(direction) {
        when (direction) {
            SwipeDirection.StartToEnd -> {
                when (swipeToEndAction) {
                    SwipeEndActionPreference.None -> "null"
                    SwipeEndActionPreference.ToggleRead -> readText
                    SwipeEndActionPreference.ToggleStarred -> starText
                }
            }

            SwipeDirection.EndToStart -> {
                when (swipeToStartAction) {
                    SwipeStartActionPreference.None -> "null"
                    SwipeStartActionPreference.ToggleRead -> readText
                    SwipeStartActionPreference.ToggleStarred -> starText
                }
            }
        }
    }
}

@Composable
fun ArticleItemMenuContent(
    articleWithFeed: ArticleWithFeed,
    iconSize: DpSize = DpSize(width = 20.dp, height = 20.dp),
    isStarred: Boolean = false,
    isRead: Boolean = false,
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
    onItemClick: (() -> Unit)? = null,
) {
    val starImageVector =
        remember(isStarred) { if (isStarred) Icons.Outlined.StarOutline else Icons.Rounded.Star }

    val readImageVector =
        remember(isRead) {
            if (isRead) Icons.Outlined.FiberManualRecord else Icons.Rounded.FiberManualRecord
        }

    val starText =
        stringResource(if (isStarred) R.string.mark_as_unstar else R.string.mark_as_starred)

    val readText = stringResource(if (isRead) R.string.mark_as_unread else R.string.mark_as_read)

    DropdownMenuItem(
        text = { Text(text = readText) },
        onClick = {
            onToggleRead(articleWithFeed)
            onItemClick?.invoke()
        },
        leadingIcon = {
            Icon(
                imageVector = readImageVector,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        },
    )
    DropdownMenuItem(
        text = { Text(text = starText) },
        onClick = {
            onToggleStarred(articleWithFeed)
            onItemClick?.invoke()
        },
        leadingIcon = {
            Icon(
                imageVector = starImageVector,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        },
    )

    if (onMarkAboveAsRead != null || onMarkBelowAsRead != null) {
        HorizontalDivider()
    }
    onMarkAboveAsRead?.let {
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.mark_above_as_read)) },
            onClick = {
                onMarkAboveAsRead(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
    onMarkBelowAsRead?.let {
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.mark_below_as_read)) },
            onClick = {
                onMarkBelowAsRead(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
    onShare?.let {
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.share)) },
            onClick = {
                onShare(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
}

@Preview
@Composable
fun MenuContentPreview() {
    MaterialTheme {
        Surface() {
            Column(modifier = Modifier.padding()) {
                ArticleItemMenuContent(
                    articleWithFeed = generateArticleWithFeedPreview(),
                    onMarkBelowAsRead = {},
                    onMarkAboveAsRead = {},
                    onShare = {},
                )
            }
        }
    }
}
