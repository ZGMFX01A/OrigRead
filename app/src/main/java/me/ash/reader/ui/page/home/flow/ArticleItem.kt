package me.ash.reader.ui.page.home.flow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import me.ash.reader.infrastructure.preference.FlowArticleListCardStylePreference
import me.ash.reader.infrastructure.preference.LocalFlowArticleListCardStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
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
import me.ash.reader.ui.theme.Shape20
import me.ash.reader.ui.theme.applyTextDirection
import me.ash.reader.ui.theme.palette.onDark

fun getSegmentedShape(isFirstInDay: Boolean, isLastInDay: Boolean): RoundedCornerShape {
    val outerCorner = 24.dp
    val innerCorner = 6.dp
    return RoundedCornerShape(
        topStart = if (isFirstInDay) outerCorner else innerCorner,
        topEnd = if (isFirstInDay) outerCorner else innerCorner,
        bottomStart = if (isLastInDay) outerCorner else innerCorner,
        bottomEnd = if (isLastInDay) outerCorner else innerCorner,
    )
}

private const val TAG = "ArticleItem"

@Composable
fun ArticleItem(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    cardStyle: FlowArticleListCardStylePreference = LocalFlowArticleListCardStyle.current,
    cardShape: Shape = Shape20,
    translation: ListTranslationResult? = null,
    translationDisplayMode: TranslationDisplayMode = TranslationDisplayMode.TRANSLATED,
    translationRevealRevision: Int = 0,
    onClick: (ArticleWithFeed) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val feed = articleWithFeed.feed
    val article = articleWithFeed.article

    // 来源内容或摘要显示设置变化后，旧译文不得套到新文字上；点击/分享始终传原 Article。
    val includeDescription = LocalFlowArticleListDesc.current != FlowArticleListDescPreference.NONE
    val visibleTranslation = translation?.takeIf {
        it.showTranslation && it.isFresh() && it.source == ListTranslationInput.of(article.accountId, article.id, article.title,
            if (includeDescription) article.shortDescription else "", article.feedId)
    }

    ArticleItem(
        modifier = modifier,
        feedName = feed.name,
        feedIconUrl = feed.icon,
        title = article.title,
        shortDescription = article.shortDescription,
        translatedTitle = visibleTranslation?.title?.takeIf(String::isNotBlank),
        translatedDescription = visibleTranslation?.description?.takeIf(String::isNotBlank),
        bilingual = translationDisplayMode == TranslationDisplayMode.BILINGUAL,
        translationRevealRevision = translationRevealRevision,
        timeString = article.dateString,
        imgData = article.img,
        isStarred = article.isStarred,
        isUnread = isUnread,
        cardStyle = cardStyle,
        cardShape = cardShape,
        onClick = { onClick(articleWithFeed) },
        onLongClick = onLongClick,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
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
    cardShape: Shape = Shape20,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val articleListFeedIcon = LocalFlowArticleListFeedIcon.current
    val articleListFeedName = LocalFlowArticleListFeedName.current
    val articleListImage = LocalFlowArticleListImage.current
    val articleListDesc = LocalFlowArticleListDesc.current
    val articleListDate = LocalFlowArticleListTime.current
    val articleListReadIndicator = LocalFlowArticleListReadIndicator.current
    var imageLoadFailed by remember(imgData) { mutableStateOf(false) }
    val titleTypography = MaterialTheme.typography.titleMedium
    val displayTitle = if (bilingual) title else translatedTitle ?: title
    val displayDescription = if (bilingual) shortDescription else translatedDescription ?: shortDescription
    val translationModeAlpha = me.ash.reader.ui.motion.rememberOrigReadContentAlpha(bilingual to translationRevealRevision)
    val titleStyle = remember(displayTitle, titleTypography) {
        titleTypography
            .copy(fontWeight = FontWeight.SemiBold)
            .applyTextDirection(displayTitle.requiresBidi())
    }
    val readAlpha = animateFloatAsState(
        targetValue = when (articleListReadIndicator) {
            FlowArticleReadIndicatorPreference.None -> 1f
            FlowArticleReadIndicatorPreference.AllRead -> if (isUnread) 1f else 0.5f
            FlowArticleReadIndicatorPreference.ExcludingStarred -> if (isUnread || isStarred) 1f else 0.5f
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "article_read_alpha",
    )

    val isSegmented = cardStyle == FlowArticleListCardStylePreference.Segmented
    val rootModifier = if (isSegmented) {
        modifier
            .fillMaxWidth()
            .clip(cardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .graphicsLayer { alpha = readAlpha.value }
    } else {
        modifier
            .padding(horizontal = 12.dp)
            .clip(Shape20)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .graphicsLayer { alpha = readAlpha.value }
    }

    Column(
        modifier = rootModifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            val showFeedIcon = articleListFeedIcon.value
            val showFeedName = articleListFeedName.value && feedName.isNotBlank()
            val showDate = articleListDate.value && !timeString.isNullOrBlank()
            val hasMetaRow = showFeedName || showDate

            // 中间正文区域
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = translationModeAlpha.value }
            ) {
                if (isSegmented) {
                    // 【方案2：分段卡片排版】
                    // 卡片内部直接以 14dp 内边距（屏幕 28dp 黄金左轴）为统一基准线，无额外 30dp 留白
                    if (hasMetaRow) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (showFeedIcon) {
                                FeedIcon(
                                    feedName = feedName,
                                    iconUrl = feedIconUrl,
                                    size = 16.dp,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            if (showFeedName) {
                                Text(
                                    text = feedName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            if (showDate) {
                                val separator = if (showFeedName) " · " else ""
                                Text(
                                    text = "$separator$timeString",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (isStarred) {
                                Spacer(modifier = Modifier.width(4.dp))
                                StarredIndicator(isStarred)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // 方案2的大标题：直接从卡片内边距（28dp 左轴）开始，文字左边缘与日期、大标题严格垂直对齐
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = displayTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = titleStyle,
                            maxLines =
                                if (articleListDesc != FlowArticleListDescPreference.NONE) 2 else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!hasMetaRow && isStarred) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StarredIndicator(isStarred)
                        }
                    }

                    // 对照标题独占行数，不与原文共用一个 Text 的省略范围
                    if (bilingual && !translatedTitle.isNullOrBlank() && translatedTitle != title) {
                        Text(
                            text = translatedTitle,
                            modifier = Modifier.padding(top = 4.dp),
                            style = titleStyle,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = if (articleListDesc != FlowArticleListDescPreference.NONE) 2 else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // 3. 文章摘要
                    if (
                        articleListDesc != FlowArticleListDescPreference.NONE &&
                        displayDescription.isNotBlank()
                    ) {
                        val bodySmallTypography = MaterialTheme.typography.bodyMedium
                        val descriptionStyle = remember(
                            displayDescription,
                            bodySmallTypography,
                        ) {
                            bodySmallTypography
                                .applyTextDirection(displayDescription.requiresBidi())
                                .merge(lineHeight = 19.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = displayDescription,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = descriptionStyle,
                            maxLines =
                                when (articleListDesc) {
                                    FlowArticleListDescPreference.LONG -> 4
                                    FlowArticleListDescPreference.SHORT -> 2
                                    else -> throw IllegalStateException()
                                },
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (bilingual && !translatedDescription.isNullOrBlank() && translatedDescription != shortDescription) {
                            Text(
                                text = translatedDescription,
                                modifier = Modifier.padding(top = 4.dp),
                                style = descriptionStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (articleListDesc == FlowArticleListDescPreference.LONG) 4 else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    // 【方案1：极简内缩线排版（改法 A）】
                    // 1. 第一行：[纯净呼吸留白 30dp] [来源名] · [时间] [★]
                    // 当开启图标时，左侧留出 30.dp 纯净呼吸留白带，Favicon 悬挂在下方大标题旁，绝不塞进第一行
                    if (hasMetaRow) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (showFeedIcon) {
                                Spacer(modifier = Modifier.width(30.dp))
                            }
                            if (showFeedName) {
                                Text(
                                    text = feedName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            if (showDate) {
                                val separator = if (showFeedName) " · " else ""
                                Text(
                                    text = "$separator$timeString",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (isStarred) {
                                Spacer(modifier = Modifier.width(4.dp))
                                StarredIndicator(isStarred)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // 2. 标题行与摘要：Favicon 悬挂在 0~30.dp 留白带中与大标题对齐，文字锁定在 54.dp 黄金左轴
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (showFeedIcon) {
                            FeedIcon(
                                feedName = feedName,
                                iconUrl = feedIconUrl,
                                size = 20.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = displayTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = titleStyle,
                                    maxLines =
                                        if (articleListDesc != FlowArticleListDescPreference.NONE) 2 else 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!hasMetaRow && isStarred) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    StarredIndicator(isStarred)
                                }
                            }

                            // 对照标题独占行数，不与原文共用一个 Text 的省略范围
                            if (bilingual && !translatedTitle.isNullOrBlank() && translatedTitle != title) {
                                Text(
                                    text = translatedTitle,
                                    modifier = Modifier.padding(top = 4.dp),
                                    style = titleStyle,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = if (articleListDesc != FlowArticleListDescPreference.NONE) 2 else 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // 3. 文章摘要
                            if (
                                articleListDesc != FlowArticleListDescPreference.NONE &&
                                displayDescription.isNotBlank()
                            ) {
                                val bodySmallTypography = MaterialTheme.typography.bodyMedium
                                val descriptionStyle = remember(
                                    displayDescription,
                                    bodySmallTypography,
                                ) {
                                    bodySmallTypography
                                        .applyTextDirection(displayDescription.requiresBidi())
                                        .merge(lineHeight = 19.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = displayDescription,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = descriptionStyle,
                                    maxLines =
                                        when (articleListDesc) {
                                            FlowArticleListDescPreference.LONG -> 4
                                            FlowArticleListDescPreference.SHORT -> 2
                                            else -> throw IllegalStateException()
                                        },
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (bilingual && !translatedDescription.isNullOrBlank() && translatedDescription != shortDescription) {
                                    Text(
                                        text = translatedDescription,
                                        modifier = Modifier.padding(top = 4.dp),
                                        style = descriptionStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (articleListDesc == FlowArticleListDescPreference.LONG) 4 else 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 右侧：缩略图（上移与第一行顶部平齐，方正稳固，彻底消除 1:3 偏心空洞）
            if (hasUsableArticleImage(imgData) && articleListImage.value && !imageLoadFailed) {
                Spacer(modifier = Modifier.width(16.dp))
                val imageShape = if (isSegmented) {
                    RoundedCornerShape(10.dp)
                } else {
                    Shape20
                }
                OrigReadAsyncImage(
                    modifier = Modifier.size(80.dp).clip(imageShape),
                    data = imgData,
                    scale = Scale.FILL,
                    precision = Precision.INEXACT,
                    size = SIZE_1000,
                    contentScale = ContentScale.Crop,
                    onError = { imageLoadFailed = true },
                )
            }
        }
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

private const val PositionalThresholdFraction = 0.4f
private const val SwipeActionDelay = 300L

@Composable
fun SwipeableArticleItem(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    articleListTonalElevation: Int = 0,
    showDivider: Boolean = false,
    cardStyle: FlowArticleListCardStylePreference = LocalFlowArticleListCardStyle.current,
    isFirstInDay: Boolean = false,
    isLastInDay: Boolean = false,
    translation: ListTranslationResult? = null,
    translationDisplayMode: TranslationDisplayMode = TranslationDisplayMode.TRANSLATED,
    translationRevealRevision: Int = 0,
    onClick: (ArticleWithFeed) -> Unit = {},
    isSwipeEnabled: () -> Boolean = { false },
    isMenuEnabled: Boolean = true,
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
) {

    var isMenuExpanded by remember { mutableStateOf(false) }

    val onLongClick =
        if (isMenuEnabled) {
            { isMenuExpanded = true }
        } else {
            null
        }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }

    val segmentedShape = remember(isFirstInDay, isLastInDay) {
        getSegmentedShape(isFirstInDay, isLastInDay)
    }

    Column(modifier = modifier) {
        if (cardStyle == FlowArticleListCardStylePreference.Segmented) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                shape = segmentedShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest onDark MaterialTheme.colorScheme.surfaceContainer,
            ) {
                SwipeActionBox(
                    modifier = Modifier,
                    articleWithFeed = articleWithFeed,
                    isRead = !isUnread,
                    isStarred = articleWithFeed.article.isStarred,
                    onToggleStarred = onToggleStarred,
                    onToggleRead = onToggleRead,
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .pointerInput(isMenuExpanded) {
                                    awaitEachGesture {
                                        while (true) {
                                            awaitFirstDown(requireUnconsumed = false).let {
                                                menuOffset = it.position.round()
                                            }
                                        }
                                    }
                                }
                                .wrapContentSize()
                    ) {
                        ArticleItem(
                            articleWithFeed = articleWithFeed,
                            isUnread = isUnread,
                            cardStyle = cardStyle,
                            cardShape = segmentedShape,
                            translation = translation,
                            translationDisplayMode = translationDisplayMode,
                            translationRevealRevision = translationRevealRevision,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                        with(articleWithFeed.article) {
                            if (isMenuEnabled) {
                                AnimatedDropdownMenu(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    expanded = isMenuExpanded,
                                    onDismissRequest = { isMenuExpanded = false },
                                    offset = menuOffset,
                                ) {
                                    ArticleItemMenuContent(
                                        articleWithFeed = articleWithFeed,
                                        isStarred = isStarred,
                                        isRead = !isUnread,
                                        onToggleStarred = onToggleStarred,
                                        onToggleRead = onToggleRead,
                                        onMarkAboveAsRead = onMarkAboveAsRead,
                                        onMarkBelowAsRead = onMarkBelowAsRead,
                                        onShare = onShare,
                                    ) {
                                        isMenuExpanded = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!isLastInDay) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        } else {
            SwipeActionBox(
                modifier = Modifier,
                articleWithFeed = articleWithFeed,
                isRead = !isUnread,
                isStarred = articleWithFeed.article.isStarred,
                onToggleStarred = onToggleStarred,
                onToggleRead = onToggleRead,
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .pointerInput(isMenuExpanded) {
                                awaitEachGesture {
                                    while (true) {
                                        awaitFirstDown(requireUnconsumed = false).let {
                                            menuOffset = it.position.round()
                                        }
                                    }
                                }
                            }
                            .background(
                                MaterialTheme.colorScheme.surfaceColorAtElevation(
                                    articleListTonalElevation.dp
                                ) onDark MaterialTheme.colorScheme.surface
                            )
                            .wrapContentSize()
                ) {
                    ArticleItem(
                        articleWithFeed = articleWithFeed,
                        isUnread = isUnread,
                        cardStyle = cardStyle,
                        cardShape = Shape20,
                        translation = translation,
                        translationDisplayMode = translationDisplayMode,
                        translationRevealRevision = translationRevealRevision,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    with(articleWithFeed.article) {
                        if (isMenuEnabled) {
                            AnimatedDropdownMenu(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                expanded = isMenuExpanded,
                                onDismissRequest = { isMenuExpanded = false },
                                offset = menuOffset,
                            ) {
                                ArticleItemMenuContent(
                                    articleWithFeed = articleWithFeed,
                                    isStarred = isStarred,
                                    isRead = !isUnread,
                                    onToggleStarred = onToggleStarred,
                                    onToggleRead = onToggleRead,
                                    onMarkAboveAsRead = onMarkAboveAsRead,
                                    onMarkBelowAsRead = onMarkBelowAsRead,
                                    onShare = onShare,
                                ) {
                                    isMenuExpanded = false
                                }
                            }
                        }
                    }
                }
            }
            if (showDivider) {
                val isShowFeedIcon = LocalFlowArticleListFeedIcon.current.value
                val dividerStart = if (isShowFeedIcon) 54.dp else 24.dp
                HorizontalDivider(
                    modifier = Modifier.padding(start = dividerStart, end = 24.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
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
