package me.ash.reader.ui.page.adaptive

import android.os.Parcelable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.LocalBackgroundTextMeasurementExecutor
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.ash.reader.ui.component.reader.ExpandedContentWidth
import me.ash.reader.ui.component.reader.LocalTextContentWidth
import me.ash.reader.ui.component.reader.MediumContentWidth
import me.ash.reader.ui.motion.origReadPopEnter
import me.ash.reader.ui.motion.origReadPopExit
import me.ash.reader.ui.motion.origReadPushEnter
import me.ash.reader.ui.motion.origReadPushExit
import me.ash.reader.ui.page.home.flow.FlowPage
import me.ash.reader.ui.page.home.reading.ReadingPage

@Parcelize data class ArticleData(val articleId: String, val listIndex: Int? = null) : Parcelable

internal fun readerWorkspaceNavigationAction(
    isTwoPane: Boolean,
    isListHiddenByUser: Boolean,
    isListTemporarilyHidden: Boolean = false,
): NavigationAction =
    when {
        !isTwoPane -> NavigationAction.Close
        isListHiddenByUser || isListTemporarilyHidden -> NavigationAction.ExpandList
        else -> NavigationAction.HideList
    }

internal fun readerWorkspaceUsesExpandedContent(
    isTwoPane: Boolean,
    isListHiddenByUser: Boolean,
    isListTemporarilyHidden: Boolean = false,
): Boolean = isTwoPane && (isListHiddenByUser || isListTemporarilyHidden)

@OptIn(
    ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalSharedTransitionApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun ArticleListReaderPage(
    modifier: Modifier = Modifier,
    scaffoldDirective: PaneScaffoldDirective,
    navigator: ThreePaneScaffoldNavigator<ArticleData>,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    viewModel: ArticleListReaderViewModel,
    onBack: () -> Unit,
    onNavigateToStylePage: () -> Unit,
    onAssistantPaneVisibilityChange: (Boolean) -> Unit = {},
) {

    val scope = rememberCoroutineScope()
    val motionScheme = MaterialTheme.motionScheme
    val adaptiveLayoutProfile = LocalOrigReadAdaptiveLayoutProfile.current
    val foldManagedListRegion = foldPrimaryInteractiveRegion(adaptiveLayoutProfile)

    val backBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange

    val isTwoPane =
        navigator.scaffoldValue.run {
            get(ListDetailPaneScaffoldRole.List) == PaneAdaptedValue.Expanded &&
                get(ListDetailPaneScaffoldRole.Detail) == PaneAdaptedValue.Expanded
        }

    // This is a user preference rather than the transient physical pane position. Keep it when
    // the window temporarily becomes single-pane so returning to a large window restores the
    // workspace the user chose instead of silently reopening the list.
    var isListHiddenByUser by rememberSaveable { mutableStateOf(false) }
    var isAssistantPaneVisible by remember { mutableStateOf(false) }
    val isListTemporarilyHiddenForAssistant =
        shouldTemporarilyHideListForAssistant(
            profile = adaptiveLayoutProfile,
            assistantPaneVisible = isAssistantPaneVisible,
        )

    val hiddenAnchor = remember(scaffoldDirective) { PaneExpansionAnchor.Offset.fromStart(0.dp) }

    val expandedAnchor =
        remember(scaffoldDirective) {
            PaneExpansionAnchor.Offset.fromStart(scaffoldDirective.defaultPanePreferredWidth)
        }

    val paneExpansionState =
        rememberPaneExpansionState(
            initialAnchoredIndex =
                if (
                    readerWorkspaceUsesExpandedContent(
                        isTwoPane = isTwoPane,
                        isListHiddenByUser = isListHiddenByUser,
                        isListTemporarilyHidden = isListTemporarilyHiddenForAssistant,
                    )
                ) 0
                else 1,
            anchors = listOf(hiddenAnchor, expandedAnchor),
            anchoringAnimationSpec =
                motionScheme.defaultSpatialSpec(),
        )

    val navigationAction =
        readerWorkspaceNavigationAction(
            isTwoPane = isTwoPane,
            isListHiddenByUser = isListHiddenByUser,
            isListTemporarilyHidden = isListTemporarilyHiddenForAssistant,
        )
    val useExpandedReaderContent =
        readerWorkspaceUsesExpandedContent(
            isTwoPane = isTwoPane,
            isListHiddenByUser = isListHiddenByUser,
            isListTemporarilyHidden = isListTemporarilyHiddenForAssistant,
        )

    LaunchedEffect(
        isTwoPane,
        isListHiddenByUser,
        isListTemporarilyHiddenForAssistant,
        hiddenAnchor,
        expandedAnchor,
    ) {
        paneExpansionState.animateTo(
            if (useExpandedReaderContent) hiddenAnchor else expandedAnchor
        )
    }

    val contentWidth = if (useExpandedReaderContent) ExpandedContentWidth else MediumContentWidth

    val animatedContentWidth by
        animateDpAsState(
            targetValue = contentWidth,
            animationSpec = motionScheme.defaultSpatialSpec(),
            label = "reader-content-width",
        )
    val animatedListAlpha by
        animateFloatAsState(
            targetValue = if (useExpandedReaderContent) 0f else 1f,
            animationSpec = motionScheme.fastEffectsSpec(),
            label = "reader-list-alpha",
        )

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = modifier,
        defaultBackBehavior = backBehavior,
        paneExpansionDragHandle = { Spacer(modifier = Modifier.width(2.dp)) },
        paneExpansionState = paneExpansionState,
        listPane = {
            if (useExpandedReaderContent && !isListTemporarilyHiddenForAssistant) {
                BackHandler { isListHiddenByUser = false }
            }
            AnimatedPane(
                enterTransition =
                    if (isTwoPane) motionDataProvider.calculateEnterTransition(paneRole)
                    else origReadPopEnter(motionScheme),
                exitTransition =
                    if (isTwoPane) motionDataProvider.calculateExitTransition(paneRole)
                    else origReadPushExit(motionScheme),
            ) {
                CompositionLocalProvider(
                    LocalBackgroundTextMeasurementExecutor provides
                        Executors.newSingleThreadExecutor()
                ) {
                    OrigReadWindowRegionBox(targetRegion = foldManagedListRegion) {
                        Box(modifier = Modifier.alpha(animatedListAlpha)) {
                            FlowPage(
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                viewModel = viewModel,
                                onNavigateUp = onBack,
                                isTwoPane = isTwoPane,
                                navigateToArticle = { id, index ->
                                    scope.launch {
                                        navigator.navigateTo(
                                            pane = ListDetailPaneScaffoldRole.Detail,
                                            contentKey = ArticleData(articleId = id, listIndex = index),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane(
                enterTransition =
                    if (isTwoPane) motionDataProvider.calculateEnterTransition(paneRole)
                    else origReadPushEnter(motionScheme),
                exitTransition =
                    if (isTwoPane) motionDataProvider.calculateExitTransition(paneRole)
                    else origReadPopExit(motionScheme),
            ) {
                val contentKey = navigator.currentDestination?.contentKey
                LaunchedEffect(contentKey) {
                    if (contentKey == null) {
                        delay(100L)
                        viewModel.clearReadingData()
                    } else {
                        viewModel.initData(
                            articleId = contentKey.articleId,
                            listIndex = contentKey.listIndex,
                        )
                    }
                }

                CompositionLocalProvider(LocalTextContentWidth provides animatedContentWidth) {
                    ReadingPage(
                        viewModel = viewModel,
                        navigationAction = navigationAction,
                        onLoadArticle = { id, index ->
                            scope.launch {
                                navigator.navigateTo(
                                    pane = ListDetailPaneScaffoldRole.Detail,
                                    contentKey = ArticleData(articleId = id, listIndex = index),
                                )
                            }
                        },
                        onNavAction = {
                            when (it) {
                                NavigationAction.Close -> {
                                    if (navigator.canNavigateBack(backBehavior)) {
                                        scope
                                            .launch { navigator.navigateBack(backBehavior) }
                                            .invokeOnCompletion { viewModel.clearReadingData() }
                                    } else {
                                        onBack()
                                    }
                                }
                                NavigationAction.HideList -> {
                                    isListHiddenByUser = true
                                }
                                NavigationAction.ExpandList -> {
                                    isListHiddenByUser = false
                                }
                            }
                        },
                        onNavigateToStylePage = onNavigateToStylePage,
                        onAssistantPaneVisibilityChange = {
                            isAssistantPaneVisible = it
                            onAssistantPaneVisibilityChange(it)
                        },
                    )
                }
            }
        },
    )
}
