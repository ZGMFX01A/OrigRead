package me.ash.reader.ui.page.nav3

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay
import me.ash.reader.ui.motion.OrigReadMotionDirection
import me.ash.reader.ui.motion.origReadNavigationTransform
import kotlin.math.roundToInt
import me.ash.reader.ui.page.adaptive.ArticleData
import me.ash.reader.ui.page.adaptive.ArticleListReaderPage
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.LocalOrigReadAdaptiveLayoutProfile
import me.ash.reader.ui.page.adaptive.OrigReadFoldFeatureInfo
import me.ash.reader.ui.page.adaptive.OrigReadFoldLayoutInfo
import me.ash.reader.ui.page.adaptive.OrigReadReaderScaffoldMode
import me.ash.reader.ui.page.adaptive.origReadAdaptiveLayoutProfile
import me.ash.reader.ui.page.adaptive.readerMaxHorizontalPartitions
import me.ash.reader.ui.page.adaptive.readerScaffoldMode
import me.ash.reader.ui.page.home.feeds.FeedsPage
import me.ash.reader.ui.page.home.feeds.discovery.SourceDiscoveryPage
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeViewModel
import me.ash.reader.ui.page.nav3.key.Route
import me.ash.reader.ui.page.settings.SettingsPage
import me.ash.reader.ui.page.settings.accounts.AccountDetailsPage
import me.ash.reader.ui.page.settings.accounts.AccountViewModel
import me.ash.reader.ui.page.settings.accounts.AccountsPage
import me.ash.reader.ui.page.settings.accounts.AddAccountsPage
import me.ash.reader.ui.page.settings.color.ColorAndStylePage
import me.ash.reader.ui.page.settings.color.DarkThemePage
import me.ash.reader.ui.page.settings.color.feeds.FeedsPageStylePage
import me.ash.reader.ui.page.settings.color.flow.FlowPageStylePage
import me.ash.reader.ui.page.settings.color.reading.BoldCharactersPage
import me.ash.reader.ui.page.settings.color.reading.ReadingImagePage
import me.ash.reader.ui.page.settings.color.reading.ReadingStylePage
import me.ash.reader.ui.page.settings.color.reading.ReadingTextPage
import me.ash.reader.ui.page.settings.color.reading.ReadingTitlePage
import me.ash.reader.ui.page.settings.color.reading.ReadingVideoPage
import me.ash.reader.ui.page.settings.interaction.InteractionPage
import me.ash.reader.ui.page.settings.languages.LanguagesPage
import me.ash.reader.ui.page.settings.tips.LicenseListPage
import me.ash.reader.ui.page.settings.tips.TipsAndSupportPage
import me.ash.reader.ui.page.settings.troubleshooting.TroubleshootingPage
import me.ash.reader.ui.page.settings.website.WebsiteRulesPage
import me.ash.reader.ui.page.settings.json.JsonRulesPage
import me.ash.reader.ui.page.settings.rsshub.RssHubSettingsPage
import me.ash.reader.ui.page.settings.filter.ArticleFilterSettingsPage
import me.ash.reader.ui.page.settings.translation.TranslationSettingsPage
import me.ash.reader.ui.page.settings.backup.ConfigurationBackupPage
import me.ash.reader.ui.page.settings.update.UpdateSettingsPage
import me.ash.reader.ui.page.startup.StartupPage

@OptIn(
    ExperimentalSharedTransitionApi::class,
    ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun AppEntry(backStack: NavBackStack<NavKey>) {
    val subscribeViewModel = hiltViewModel<SubscribeViewModel>()
    val motionScheme = MaterialTheme.motionScheme

    val onBack: () -> Unit = {
        if (backStack.size == 1) backStack[0] = Route.Feeds else backStack.removeLastOrNull()
    }

    val windowAdaptiveInfo = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val windowContainerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowWidthDp = with(density) { windowContainerSize.width.toDp().value.roundToInt() }
    val windowHeightDp = with(density) { windowContainerSize.height.toDp().value.roundToInt() }
    val foldLayoutInfo =
        remember(windowAdaptiveInfo.windowPosture, density) {
            OrigReadFoldLayoutInfo(
                isTabletop = windowAdaptiveInfo.windowPosture.isTabletop,
                hinges =
                    windowAdaptiveInfo.windowPosture.hingeList.map { hinge ->
                        with(density) {
                            OrigReadFoldFeatureInfo(
                                leftDp = hinge.bounds.left.toDp().value,
                                topDp = hinge.bounds.top.toDp().value,
                                rightDp = hinge.bounds.right.toDp().value,
                                bottomDp = hinge.bounds.bottom.toDp().value,
                                isFlat = hinge.isFlat,
                                isVertical = hinge.isVertical,
                                isSeparating = hinge.isSeparating,
                                isOccluding = hinge.isOccluding,
                            )
                        }
                    },
            )
        }
    val adaptiveLayoutProfile =
        remember(windowWidthDp, windowHeightDp, foldLayoutInfo) {
            origReadAdaptiveLayoutProfile(
                widthDp = windowWidthDp,
                heightDp = windowHeightDp,
                foldLayoutInfo = foldLayoutInfo,
            )
        }
    // The child ReadingPage persists assistant visibility across Activity/window recreation.
    // Keep the parent scaffold input on the same lifecycle so the first restored frame does not
    // briefly derive a different pane directive and then jump after the child republishes state.
    var readingAssistantPaneVisible by rememberSaveable { mutableStateOf(false) }
    val baseScaffoldDirective =
        when (readerScaffoldMode(adaptiveLayoutProfile, readingAssistantPaneVisible)) {
            OrigReadReaderScaffoldMode.Standard ->
                calculatePaneScaffoldDirective(windowAdaptiveInfo)
            OrigReadReaderScaffoldMode.TwoPaneOnMediumVerticalHinge ->
                calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(windowAdaptiveInfo)
            OrigReadReaderScaffoldMode.FoldManagedSinglePane ->
                calculatePaneScaffoldDirective(windowAdaptiveInfo).copy(
                    maxHorizontalPartitions = 1,
                    horizontalPartitionSpacerSize = 0.dp,
                    maxVerticalPartitions = 1,
                    verticalPartitionSpacerSize = 0.dp,
                    excludedBounds = emptyList(),
                )
        }
    // Material Adaptive derives horizontal partitions from width. OrigRead intentionally treats
    // Compact Height as phone-like, so prevent a wide-but-short window from becoming list/detail
    // multi-pane while keeping the rest of the recommended directive unchanged.
    val maxHorizontalPartitions =
        readerMaxHorizontalPartitions(
            profile = adaptiveLayoutProfile,
            recommendedPartitions = baseScaffoldDirective.maxHorizontalPartitions,
        )
    val scaffoldDirective =
        if (maxHorizontalPartitions != baseScaffoldDirective.maxHorizontalPartitions) {
            baseScaffoldDirective.copy(
                maxHorizontalPartitions = maxHorizontalPartitions,
                horizontalPartitionSpacerSize = 0.dp,
            )
        } else {
            baseScaffoldDirective
        }

    val navigator =
        rememberListDetailPaneScaffoldNavigator<ArticleData>(
            scaffoldDirective = scaffoldDirective,
            isDestinationHistoryAware = false,
        )

    CompositionLocalProvider(LocalOrigReadAdaptiveLayoutProfile provides adaptiveLayoutProfile) {
        SharedTransitionLayout {
            NavDisplay(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            backStack = backStack,
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            transitionSpec = {
                origReadNavigationTransform(OrigReadMotionDirection.Forward, motionScheme)
            },
            popTransitionSpec = {
                origReadNavigationTransform(OrigReadMotionDirection.Backward, motionScheme)
            },
            predictivePopTransitionSpec = {
                origReadNavigationTransform(OrigReadMotionDirection.Backward, motionScheme)
            },
            onBack = { backStack.removeLastOrNull() },
            entryProvider = { key ->
                when (key) {
                    Route.Feeds -> {
                        NavEntry(key) {
                            FeedsPage(
                                subscribeViewModel = subscribeViewModel,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                navigateToSettings = { backStack.add(Route.Settings) },
                                navigateToSourceDiscovery = {
                                    backStack.add(Route.SourceDiscovery)
                                },
                                navigationToFlow = { backStack.add(Route.Reading(null)) },
                                navigateToAccountList = { backStack.add(Route.Accounts) },
                                navigateToAccountDetail = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                            )
                        }
                    }
                    Route.SourceDiscovery -> {
                        NavEntry(key) {
                            SourceDiscoveryPage(
                                onBack = onBack,
                                onSubscribe = { feed ->
                                    backStack.removeLastOrNull()
                                    subscribeViewModel.openFeedFromCatalog(feed.feedUrl)
                                },
                            )
                        }
                    }
                    is Route.Reading -> {
                        NavEntry(key) {
                            val key = rememberSaveable(saver = Route.Reading.Saver) { key }
                            val viewModel = hiltViewModel<ArticleListReaderViewModel>()

                            LaunchedEffect(key) {
                                if (key.articleId != null) {
                                    delay(50L)
                                    navigator.navigateTo(
                                        ListDetailPaneScaffoldRole.Detail,
                                        ArticleData(key.articleId),
                                    )
                                } else {
                                    // Reading(null) means "show this collection's article list",
                                    // not "reuse whichever detail article the adaptive navigator
                                    // happened to retain from the previous collection".
                                    viewModel.clearReadingData()
                                    navigator.navigateTo(ListDetailPaneScaffoldRole.List)
                                }
                            }

                            ArticleListReaderPage(
                                scaffoldDirective = scaffoldDirective,
                                navigator = navigator,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                viewModel = viewModel,
                                onBack = onBack,
                                onNavigateToStylePage = { backStack.add(Route.ReadingPageStyle) },
                                onNavigateToSettings = { backStack.add(Route.Settings) },
                                onNavigateToSourceDiscovery = {
                                    backStack.add(Route.SourceDiscovery)
                                },
                                onNavigateToAccountList = { backStack.add(Route.Accounts) },
                                onNavigateToAccountDetail = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                                assistantPaneVisible = readingAssistantPaneVisible,
                                onAssistantPaneVisibilityChange = { readingAssistantPaneVisible = it },
                            )
                        }
                    }
                    //                    is Route.Reading -> {
                    //                        NavEntry(key) {
                    //                            val articleId = key.articleId
                    //
                    //                            val readingViewModel: ReadingViewModel =
                    //                                hiltViewModel<
                    //                                    ReadingViewModel,
                    //                                    ReadingViewModel.ReadingViewModelFactory,
                    //                                > { factory ->
                    //                                    factory.create(articleId.toString(), null)
                    //                                }
                    //
                    //                            ReadingPage(
                    //                                readingViewModel = readingViewModel,
                    //                                onBack = onBack,
                    //                                onNavigateToStylePage = {
                    // backStack.add(Route.ReadingPageStyle) },
                    //                            )
                    //                        }
                    //                    }
                    Route.Startup -> {
                        NavEntry(key) {
                            StartupPage(onNavigateToFeeds = { backStack.add(Route.Feeds) })
                        }
                    }
                    Route.Settings ->
                        NavEntry(key) {
                            SettingsPage(
                                onBack = onBack,
                                navigateToAccounts = { backStack.add(Route.Accounts) },
                                navigateToColorAndStyle = { backStack.add(Route.ColorAndStyle) },
                                navigateToInteraction = { backStack.add(Route.Interaction) },
                                navigateToLanguages = { backStack.add(Route.Languages) },
                                navigateToWebsiteRules = { backStack.add(Route.WebsiteRules) },
                                navigateToJsonRules = { backStack.add(Route.JsonRules) },
                                navigateToRssHubSettings = { backStack.add(Route.RssHubSettings) },
                                navigateToArticleFilters = { backStack.add(Route.ArticleFilters) },
                                navigateToTranslationSettings = {
                                    backStack.add(Route.TranslationSettings)
                                },
                                navigateToAiSettings = { backStack.add(Route.AiSettings) },
                                navigateToConfigurationBackup = {
                                    backStack.add(Route.ConfigurationBackup)
                                },
                                navigateToUpdateSettings = { backStack.add(Route.SoftwareUpdate) },
                                navigateToTipsAndSupport = { backStack.add(Route.TipsAndSupport) },
                            )
                        }
                    Route.Accounts ->
                        NavEntry(key) {
                            AccountsPage(
                                onBack = onBack,
                                navigateToAddAccount = { backStack.add(Route.AddAccounts) },
                                navigateToAccountDetails = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                            )
                        }
                    is Route.AccountDetails ->
                        NavEntry(key) {
                            AccountDetailsPage(
                                viewModel =
                                    hiltViewModel<AccountViewModel>().also {
                                        it.initData(key.accountId)
                                    },
                                onBack = onBack,
                                navigateToFeeds = { backStack.add(Route.Feeds) },
                            )
                        }
                    Route.AddAccounts ->
                        NavEntry(key) {
                            AddAccountsPage(
                                onBack = onBack,
                                navigateToAccountDetails = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                            )
                        }
                    Route.ColorAndStyle ->
                        NavEntry(key) {
                            ColorAndStylePage(
                                onBack = onBack,
                                navigateToDarkTheme = { backStack.add(Route.DarkTheme) },
                                navigateToFeedsPageStyle = { backStack.add(Route.FeedsPageStyle) },
                                navigateToFlowPageStyle = { backStack.add(Route.FlowPageStyle) },
                                navigateToReadingPageStyle = {
                                    backStack.add(Route.ReadingPageStyle)
                                },
                            )
                        }
                    Route.DarkTheme -> NavEntry(key) { DarkThemePage(onBack = onBack) }
                    Route.FeedsPageStyle -> NavEntry(key) { FeedsPageStylePage(onBack = onBack) }
                    Route.FlowPageStyle -> NavEntry(key) { FlowPageStylePage(onBack = onBack) }
                    Route.ReadingPageStyle ->
                        NavEntry(key) {
                            ReadingStylePage(
                                onBack = onBack,
                                navigateToReadingBoldCharacters = {
                                    backStack.add(Route.ReadingBoldCharacters)
                                },
                                navigateToReadingPageTitle = {
                                    backStack.add(Route.ReadingPageTitle)
                                },
                                navigateToReadingPageText = {
                                    backStack.add(Route.ReadingPageText)
                                },
                                navigateToReadingPageImage = {
                                    backStack.add(Route.ReadingPageImage)
                                },
                                navigateToReadingPageVideo = {
                                    backStack.add(Route.ReadingPageVideo)
                                },
                            )
                        }
                    Route.ReadingBoldCharacters ->
                        NavEntry(key) { BoldCharactersPage(onBack = onBack) }
                    Route.ReadingPageTitle -> NavEntry(key) { ReadingTitlePage(onBack = onBack) }
                    Route.ReadingPageText -> NavEntry(key) { ReadingTextPage(onBack = onBack) }
                    Route.ReadingPageImage -> NavEntry(key) { ReadingImagePage(onBack = onBack) }
                    Route.ReadingPageVideo -> NavEntry(key) { ReadingVideoPage(onBack = onBack) }
                    Route.Interaction -> NavEntry(key) { InteractionPage(onBack = onBack) }
                    Route.Languages -> NavEntry(key) { LanguagesPage(onBack = onBack) }
                    Route.Troubleshooting -> NavEntry(key) { TroubleshootingPage(onBack = onBack) }
                    Route.WebsiteRules -> NavEntry(key) { WebsiteRulesPage(onBack = onBack) }
                    Route.JsonRules -> NavEntry(key) { JsonRulesPage(onBack = onBack) }
                    Route.RssHubSettings -> NavEntry(key) { RssHubSettingsPage(onBack = onBack) }
                    Route.ArticleFilters -> NavEntry(key) { ArticleFilterSettingsPage(onBack = onBack) }
                    Route.TranslationSettings ->
                        NavEntry(key) { TranslationSettingsPage(onBack = onBack) }
                    Route.AiSettings -> NavEntry(key) { EditionAiSettingsPage(onBack = onBack) }
                    Route.ConfigurationBackup ->
                        NavEntry(key) { ConfigurationBackupPage(onBack = onBack) }
                    Route.SoftwareUpdate -> NavEntry(key) { UpdateSettingsPage(onBack = onBack) }
                    Route.TipsAndSupport ->
                        NavEntry(key) {
                            TipsAndSupportPage(
                                onBack = onBack,
                                navigateToLicenseList = { backStack.add(Route.LicenseList) },
                                navigateToTroubleshooting = { backStack.add(Route.Troubleshooting) },
                            )
                        }
                    Route.LicenseList -> NavEntry(key) { LicenseListPage(onBack = onBack) }
                    else -> NavEntry(key) { throw Exception("Unknown destination") }
                }
            },
            )
        }
    }
}
