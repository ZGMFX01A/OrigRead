package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MenuOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalReadingPageTonalElevation
import me.ash.reader.infrastructure.preference.ReadingPageTonalElevationPreference
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.page.adaptive.NavigationAction

private val sizeSpec = spring<IntSize>(stiffness = 700f)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TopBar(
    isShow: Boolean,
    isScrolled: Boolean = false,
    title: String? = "",
    link: String? = "",
    navigationAction: NavigationAction,
    onClick: (() -> Unit)? = null,
    onNavButtonClick: (NavigationAction) -> Unit = {},
    onNavigateToStylePage: () -> Unit,
    showReadAloudButton: Boolean = true,
    showReadOriginalButton: Boolean = true,
    showFullContentButton: Boolean = false,
    isFullContent: Boolean = false,
    onReadOriginal: () -> Unit = {},
    onFullContent: (Boolean) -> Unit = {},
    onShare: () -> Unit = {},
    onShareLongClick: () -> Unit = {},
    shareEnabled: Boolean = true,
    ttsButton: @Composable () -> Unit = {},
) {
    val isOutlined =
        LocalReadingPageTonalElevation.current == ReadingPageTonalElevationPreference.Outlined

    val containerColor by
        animateColorAsState(
            with(MaterialTheme.colorScheme) {
                if (isOutlined || !isScrolled) surface else surfaceContainer
            },
            label = "",
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        )

    Box(modifier = Modifier.fillMaxSize().zIndex(1f), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.drawBehind { drawRect(containerColor) }) {
            Spacer(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            )
            AnimatedVisibility(
                visible = isShow,
                enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = sizeSpec),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = sizeSpec),
            ) {
                TopAppBar(
                    title = {},
                    modifier =
                        if (onClick == null) Modifier
                        else
                            Modifier.clickable(
                                onClick = onClick,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ),
                    windowInsets = WindowInsets(0.dp),
                    navigationIcon = {
                        val imageVector =
                            when (navigationAction) {
                                NavigationAction.Close -> Icons.Rounded.Close
                                NavigationAction.HideList -> Icons.AutoMirrored.Rounded.MenuOpen
                                NavigationAction.ExpandList -> Icons.Rounded.Menu
                            }
                        val contentDescription =
                            when (navigationAction) {
                                NavigationAction.Close -> stringResource(R.string.close)
                                NavigationAction.HideList -> "Hide list"
                                NavigationAction.ExpandList -> "Expand list"
                            }
                        FeedbackIconButton(
                            imageVector = imageVector,
                            contentDescription = contentDescription,
                            tint = MaterialTheme.colorScheme.onSurface,
                        ) {
                            onNavButtonClick(navigationAction)
                        }
                    },
                    actions = {
                        if (showReadAloudButton) {
                            ttsButton()
                        }
                        if (showReadOriginalButton) {
                            FeedbackIconButton(
                                modifier = Modifier.size(22.dp),
                                imageVector = Icons.Outlined.OpenInBrowser,
                                contentDescription = stringResource(R.string.read_original),
                                tint = MaterialTheme.colorScheme.onSurface,
                            ) {
                                onReadOriginal()
                            }
                        }
                        if (showFullContentButton) {
                            FeedbackIconButton(
                                modifier = Modifier.size(22.dp),
                                imageVector =
                                    if (isFullContent) Icons.AutoMirrored.Rounded.Article
                                    else Icons.AutoMirrored.Outlined.Article,
                                contentDescription = stringResource(R.string.parse_full_content),
                                tint =
                                    if (isFullContent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            ) {
                                onFullContent(!isFullContent)
                            }
                        }
                        FeedbackIconButton(
                            modifier = Modifier.size(22.dp),
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = stringResource(R.string.style),
                            tint = MaterialTheme.colorScheme.onSurface,
                        ) {
                            onNavigateToStylePage()
                        }
                        ReadingShareIconButton(
                            modifier = Modifier.size(20.dp),
                            contentDescription = stringResource(R.string.share),
                            tint =
                                if (shareEnabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            enabled = shareEnabled,
                            onClick = onShare,
                            onLongClick = onShareLongClick,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
            if (isOutlined && isScrolled) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    thickness = 0.5f.dp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadingShareIconButton(
    modifier: Modifier,
    contentDescription: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier =
            Modifier.size(48.dp).combinedClickable(
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    onClick()
                },
                onLongClick = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onLongClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = modifier,
            imageVector = Icons.Outlined.Share,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}
