package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.R
import com.rometools.rome.feed.synd.SyndFeedImpl
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.rsshub.RssHubFailureReason
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.infrastructure.rsshub.RssHubRouteDefinition
import me.ash.reader.infrastructure.rsshub.RssHubRouteMatch
import me.ash.reader.infrastructure.website.CandidateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RssHubRouteSectionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allFailuresShowDiagnosticsWithoutPromisingSelectableChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = "/zhihu/hot"
        val result = RssHubProbeResult(
            match = RssHubRouteMatch(
                RssHubRouteDefinition("direct:$path", "RSSHub", "rsshub", path, path),
                feedUrl = "https://rsshub.app$path",
            ),
            state = CandidateState.NETWORK_UNAVAILABLE,
            failureReason = RssHubFailureReason.BLOCKED,
        )
        composeRule.setContent {
            MaterialTheme {
                Column { RssHubRouteSection(listOf(result), emptyList(), emptySet(), {}, null) }
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.rsshub_probe_results)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.rsshub_probe_results_desc)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.rsshub_matched_channels_desc, 1)).assertDoesNotExist()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun remoteOrdinaryRssSuccessIsShownAsAvailableWithoutOfferingLocalMultiSelect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = "/zhihu/hot"
        val url = "https://mirror.example$path"
        val feed = SyndFeedImpl().apply { title = "Zhihu Hot"; entries = emptyList() }
        val result = RssHubProbeResult(
            match = RssHubRouteMatch(RssHubRouteDefinition("direct:$path", "RSSHub", "rsshub", path, path), feedUrl = url),
            state = CandidateState.AVAILABLE, feed = feed,
        )
        val candidates = SubscribeCandidateSelector.rank(listOf(
            SubscribeCandidateProbe(feed, url, SourceType.RSS, SourceCandidateKind.RSS_DIRECT),
        ))
        composeRule.setContent {
            MaterialTheme {
                Column { RssHubRouteSection(listOf(result), candidates, setOf(candidates.single().id), {}, null) }
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.rsshub_probe_rss_available_desc)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.rsshub_route_available, 0)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.rsshub_route_quality_rejected)).assertDoesNotExist()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
    }
}
