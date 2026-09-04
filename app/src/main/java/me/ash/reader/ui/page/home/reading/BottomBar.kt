package me.ash.reader.ui.page.home.reading

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalReadingPageTonalElevation
import me.ash.reader.infrastructure.preference.ReadingPageTonalElevationPreference
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.ui.component.base.CanBeDisabledIconButton

private val sizeSpec = spring<IntSize>(stiffness = 700f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomBar(
    isShow: Boolean,
    isUnread: Boolean,
    isStarred: Boolean,
    isNextArticleAvailable: Boolean,
    showTranslationButton: Boolean = true,
    isTranslationLoading: Boolean = false,
    isTranslated: Boolean = false,
    translationTargets: List<TranslationTarget> = emptyList(),
    defaultTranslationTarget: TranslationTarget? = null,
    activeTranslationTarget: TranslationTarget? = null,
    showAiSummaryButton: Boolean = false,
    isAiSummaryEnabled: Boolean = true,
    isAiSummaryLoading: Boolean = false,
    hasAiSummary: Boolean = false,
    aiActionContentDescription: String? = null,
    onUnread: (isUnread: Boolean) -> Unit = {},
    onStarred: (isStarred: Boolean) -> Unit = {},
    onNextArticle: () -> Unit = {},
    onAiSummary: () -> Unit = {},
    onAiSummaryLongClick: () -> Unit = {},
    onStopAiSummary: () -> Unit = {},
    onTranslate: () -> Unit = {},
    onTranslateWithTarget: (TranslationTarget) -> Unit = {},
    onSetDefaultTranslationTarget: (TranslationTarget) -> Unit = {},
) {
    val tonalElevation = LocalReadingPageTonalElevation.current
    val isOutlined = tonalElevation == ReadingPageTonalElevationPreference.Outlined
    var showTranslationTargets by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = isShow,
            enter = expandVertically(expandFrom = Alignment.Top, animationSpec = sizeSpec),
            exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = sizeSpec)
        ) {
            val view = LocalView.current
            Column {
                if (isOutlined) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        thickness = 0.5f.dp
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.run { if (isOutlined) surface else surfaceContainer }
                ) {
                    // AI 摘要固定占据屏幕几何中心，左右功能数量变化时都不会偏移。
                    Box(
                        modifier =
                            Modifier.navigationBarsPadding()
                                .fillMaxWidth()
                                .height(60.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CanBeDisabledIconButton(
                                    modifier = Modifier.size(40.dp),
                                    disabled = false,
                                    imageVector =
                                        if (isUnread) Icons.Filled.FiberManualRecord
                                        else Icons.Outlined.FiberManualRecord,
                                    contentDescription =
                                        stringResource(
                                            if (isUnread) R.string.mark_as_read
                                            else R.string.mark_as_unread
                                        ),
                                    tint =
                                        if (isUnread) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.outline,
                                ) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onUnread(!isUnread)
                                }
                                CanBeDisabledIconButton(
                                    modifier = Modifier.size(40.dp),
                                    disabled = false,
                                    imageVector =
                                        if (isStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                    contentDescription =
                                        stringResource(
                                            if (isStarred) R.string.mark_as_unstar
                                            else R.string.mark_as_starred
                                        ),
                                    tint =
                                        if (isStarred) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.outline,
                                ) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onStarred(!isStarred)
                                }
                            }

                            // 为固定 C 位预留独立触控区，左右 Row 的动态按钮不会挤动 AI。
                            Spacer(modifier = Modifier.width(56.dp))

                            Row(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CanBeDisabledIconButton(
                                    disabled = !isNextArticleAvailable,
                                    modifier = Modifier.size(40.dp),
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = "Next Article",
                                    tint = MaterialTheme.colorScheme.outline,
                                ) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onNextArticle()
                                }

                                if (showTranslationButton) {
                                    if (isTranslationLoading) {
                                        Box(
                                            modifier =
                                                Modifier.size(40.dp)
                                                    .clip(CircleShape)
                                                    .combinedClickable(
                                                        onClick = {
                                                            view.performHapticFeedback(
                                                                HapticFeedbackConstants.KEYBOARD_TAP
                                                            )
                                                            onTranslate()
                                                        },
                                                        onLongClick = {},
                                                    ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            androidx.compose.material3.Icon(
                                                imageVector = Icons.Rounded.Stop,
                                                contentDescription = stringResource(R.string.cancel),
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier =
                                                Modifier.size(40.dp)
                                                    .clip(CircleShape)
                                                    .combinedClickable(
                                                        onClick = {
                                                            view.performHapticFeedback(
                                                                HapticFeedbackConstants.KEYBOARD_TAP
                                                            )
                                                            onTranslate()
                                                        },
                                                        onLongClick = {
                                                            if (translationTargets.isNotEmpty()) {
                                                                view.performHapticFeedback(
                                                                    HapticFeedbackConstants.LONG_PRESS
                                                                )
                                                                showTranslationTargets = true
                                                            }
                                                        },
                                                    ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            androidx.compose.material3.Icon(
                                                imageVector =
                                                    if (isTranslated) Icons.Rounded.Translate
                                                    else Icons.Outlined.Translate,
                                                contentDescription =
                                                    stringResource(
                                                        if (isTranslated) R.string.show_original
                                                        else R.string.translate_article
                                                    ),
                                                tint =
                                                    if (isTranslated) {
                                                        MaterialTheme.colorScheme.onSecondaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.outline
                                                    },
                                            )
                                        }
                                    }
                                }

                            }
                        }

                        if (showAiSummaryButton) {
                            if (isAiSummaryLoading) {
                                Box(
                                    modifier =
                                        Modifier.align(Alignment.Center)
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .combinedClickable(
                                                onClick = {
                                                    view.performHapticFeedback(
                                                        HapticFeedbackConstants.KEYBOARD_TAP
                                                    )
                                                    onStopAiSummary()
                                                },
                                                onLongClick = {},
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.Rounded.Stop,
                                        contentDescription = stringResource(R.string.ai_summary_stop),
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Box(
                                    modifier =
                                        Modifier.align(Alignment.Center)
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .combinedClickable(
                                                enabled = isAiSummaryEnabled,
                                                onClick = {
                                                    view.performHapticFeedback(
                                                        HapticFeedbackConstants.KEYBOARD_TAP
                                                    )
                                                    onAiSummary()
                                                },
                                                onLongClick = {
                                                    view.performHapticFeedback(
                                                        HapticFeedbackConstants.LONG_PRESS
                                                    )
                                                    onAiSummaryLongClick()
                                                },
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AiSummaryAccentIcon(
                                        contentDescription =
                                            aiActionContentDescription
                                                ?: stringResource(R.string.ai_summary_generate),
                                        active = hasAiSummary,
                                        enabled = isAiSummaryEnabled,
                                        size = 30.dp,
                                        iconSize = 17.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTranslationTargets) {
        TranslationTargetSheet(
            targets = translationTargets,
            defaultTarget = defaultTranslationTarget,
            activeTarget = activeTranslationTarget,
            onDismiss = { showTranslationTargets = false },
            onTargetSelected = onTranslateWithTarget,
            onSetDefaultTarget = onSetDefaultTranslationTarget,
        )
    }
}
