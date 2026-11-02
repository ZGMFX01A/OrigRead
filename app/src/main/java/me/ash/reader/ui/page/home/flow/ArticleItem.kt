package me.ash.reader.ui.page.home.flow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.infrastructure.preference.SwipeEndActionPreference
import me.ash.reader.infrastructure.preference.SwipeStartActionPreference
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.base.OrigReadAsyncImage
import me.ash.reader.ui.component.base.SIZE_1000
import me.ash.reader.ui.component.menu.AnimatedDropdownMenu
import me.ash.reader.ui.component.swipe.SwipeAction
import me.ash.reader.ui.component.swipe.SwipeableActionsBox
import me.ash.reader.ui.ext.requiresBidi
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.page.settings.color.flow.generateArticleWithFeedPreview
import me.ash.reader.ui.theme.applyTextDirection
import me.ash.reader.ui.theme.palette.onDark

private val ArticleCardShape = RoundedCornerShape(16.dp)
private val ThumbnailShape = RoundedCornerShape(8.dp)
private val CardInset = 12.dp
private val ThumbnailSize = 76.dp

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
    ArticleItemFrame(modifier, cardStyle, cardShape) {
        ArticleRowContent(
            data = articleWithFeed.rowData(isUnread, translation),
            cardStyle = cardStyle,
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
    ArticleItemFrame(modifier, cardStyle, cardShape) {
        ArticleRowContent(
            data = ArticleRowData(
                feedName, feedIconUrl, title, shortDescription,
                translatedTitle, translatedDescription, timeString, imgData, isStarred, isUnread,
            ),
            cardStyle = cardStyle,
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
    content: @Composable () -> Unit,
) {
    if (cardStyle == FlowArticleListCardStylePreference.Segmented) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = CardInset),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = content,
        )
    } else {
        Box(modifier.fillMaxWidth()) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleRowContent(
    data: ArticleRowData,
    cardStyle: FlowArticleListCardStylePreference,
    isSingleFeed: Boolean,
    bilingual: Boolean,
    translationRevealRevision: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
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
        // The theme's 16/24 sp title becomes 18/24 sp; retain its font family and scaled metrics.
        fontSize = themeTitle.fontSize * (18f / 16f),
        fontWeight = if (muted) FontWeight.Normal else FontWeight.SemiBold,
    )
    var imageFailed by remember(data.image) { mutableStateOf(false) }
    val showImage = LocalFlowArticleListImage.current.value && hasUsableArticleImage(data.image) && !imageFailed
    val contentAlpha = me.ash.reader.ui.motion.rememberOrigReadContentAlpha(bilingual to translationRevealRevision)
    val isCard = cardStyle == FlowArticleListCardStylePreference.Segmented
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                horizontal = if (isCard) CardInset else ArticleListContentInset,
                vertical = if (isCard) 14.dp else 16.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).graphicsLayer { alpha = contentAlpha.value }) {
            ArticleRowText(title, titleStyle, if (muted) colors.onSurfaceVariant else colors.onSurface, titleLines)
            if (secondTitle != null) {
                ArticleRowText(secondTitle, titleStyle, if (muted) colors.onSurfaceVariant else colors.primary,
                    titleLines, Modifier.padding(top = 4.dp))
            }
            if (hasDescription) {
                if (description.isNotBlank()) {
                    ArticleRowText(description, MaterialTheme.typography.bodyMedium, colors.onSurfaceVariant,
                        descriptionLines, Modifier.padding(top = 4.dp))
                }
                if (secondDescription != null) {
                    ArticleRowText(secondDescription, MaterialTheme.typography.bodyMedium, colors.onSurfaceVariant,
                        descriptionLines, Modifier.padding(top = 4.dp))
                }
            }
            ArticleMetadata(data, isSingleFeed)
        }

        if (showImage) {
            Spacer(Modifier.width(12.dp))
            OrigReadAsyncImage(
                modifier = Modifier.layout { measurable, constraints ->
                    // Respect the actual list pane width in split screen, without subcomposing every row.
                    val side = minOf(ThumbnailSize.roundToPx(), constraints.maxWidth / 4, constraints.maxHeight)
                    val placeable = measurable.measure(Constraints.fixed(side, side))
                    layout(side, side) { placeable.placeRelative(0, 0) }
                }.clip(ThumbnailShape),
                data = data.image,
                scale = Scale.FILL,
                precision = Precision.INEXACT,
                size = SIZE_1000,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
            )
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
private fun ArticleMetadata(data: ArticleRowData, isSingleFeed: Boolean) {
    val showIcon = !isSingleFeed && LocalFlowArticleListFeedIcon.current.value &&
        (data.feedName.isNotBlank() || !data.feedIconUrl.isNullOrBlank())
    val showName = !isSingleFeed && LocalFlowArticleListFeedName.current.value && data.feedName.isNotBlank()
    val showTime = LocalFlowArticleListTime.current.value
    val time = data.time?.takeIf { showTime && it.isNotBlank() }
    if (!showIcon && !showName && time == null && !data.starred) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) FeedIcon(feedName = data.feedName, iconUrl = data.feedIconUrl, size = 14.dp)
        if (showName) {
            ArticleRowText(data.feedName, MaterialTheme.typography.labelMedium,
                MaterialTheme.colorScheme.onSurfaceVariant, 1, Modifier.weight(1f, fill = false))
        }
        if (time != null) {
            ArticleRowText(if (showName) "· $time" else time, MaterialTheme.typography.labelMedium,
                MaterialTheme.colorScheme.onSurfaceVariant, 1)
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
    val background = if (isCard) MaterialTheme.colorScheme.surfaceContainerLow else
        MaterialTheme.colorScheme.surfaceColorAtElevation(articleListTonalElevation.dp) onDark MaterialTheme.colorScheme.surface

    Column(modifier = modifier.fillMaxWidth()) {
        ArticleItemFrame(Modifier, cardStyle, ArticleCardShape) {
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
                        isSingleFeed = isSingleFeed,
                        bilingual = translationDisplayMode == TranslationDisplayMode.BILINGUAL,
                        translationRevealRevision = translationRevealRevision,
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
        }
        if (hasNextArticleInGroup) {
            if (isCard) Spacer(Modifier.height(8.dp))
            else HorizontalDivider(
                modifier = Modifier.padding(horizontal = ArticleListContentInset),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
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
