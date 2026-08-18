package me.ash.reader.ui.page.home.feeds.subscribe

import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.infrastructure.rsshub.RssHubRouteDefinition
import me.ash.reader.infrastructure.rsshub.RssHubRouteMatch
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.website.CandidateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscribeCandidateSelectorTest {
    @Test
    fun `valid candidates are sorted by score`() {
        val candidates =
            listOf(
                probe(SourceCandidateKind.WEBSITE, SourceType.WEBSITE, "https://example.com", 20),
                probe(SourceCandidateKind.JSON, SourceType.JSON, "https://example.com/api/news", 20),
                probe(SourceCandidateKind.RSS_DIRECT, SourceType.RSS, "https://example.com/feed.xml", 20),
            )

        val ranked = SubscribeCandidateSelector.rank(candidates)

        assertEquals(SourceCandidateKind.RSS_DIRECT, ranked[0].kind)
        assertEquals(SourceCandidateKind.JSON, ranked[1].kind)
        assertEquals(SourceCandidateKind.WEBSITE, ranked[2].kind)
    }

    @Test
    fun `invalid website candidate is omitted`() {
        val invalid = SyndFeedImpl().apply { entries = emptyList() }

        val ranked =
            SubscribeCandidateSelector.rank(
                listOf(
                    SubscribeCandidateProbe(
                        feed = invalid,
                        feedLink = "https://example.com/empty",
                        sourceType = SourceType.WEBSITE,
                        kind = SourceCandidateKind.WEBSITE,
                    ),
                    probe(SourceCandidateKind.RSS_DIRECT, SourceType.RSS, "https://example.com/feed.xml", 10),
                )
            )

        assertEquals(1, ranked.size)
        assertTrue(ranked.single().diagnostics.accepted)
    }

    @Test
    fun `same rss address is shown only once`() {
        val url = "https://example.com/feed.xml"
        val ranked =
            SubscribeCandidateSelector.rank(
                listOf(
                    probe(SourceCandidateKind.RSS_DISCOVERED, SourceType.RSS, url, 10),
                    probe(SourceCandidateKind.RSS_DIRECT, SourceType.RSS, url, 10),
                )
            )

        assertEquals(1, ranked.size)
        assertEquals(SourceCandidateKind.RSS_DIRECT, ranked.single().kind)
    }

    @Test
    fun `website candidate defaults to in app reading`() {
        val ranked =
            SubscribeCandidateSelector.rank(
                listOf(
                    probe(
                        SourceCandidateKind.WEBSITE,
                        SourceType.WEBSITE,
                        "https://example.com",
                        10,
                    )
                )
            )

        assertEquals(false, ranked.single().browser)
    }

    @Test
    fun `dynamic website fallback with no articles remains available as explicit low confidence choice`() {
        val ranked =
            SubscribeCandidateSelector.rank(
                listOf(
                    probe(
                        SourceCandidateKind.WEBSITE_DYNAMIC,
                        SourceType.WEBSITE,
                        "https://example.com/dynamic",
                        0,
                    )
                )
            )

        assertEquals(1, ranked.size)
        assertEquals(SourceCandidateKind.WEBSITE_DYNAMIC, ranked.single().kind)
        assertEquals(false, ranked.single().diagnostics.accepted)
    }

    @Test
    fun `rsshub local route is preserved when network probe returns empty`() {
        val local = rssHubResult(CandidateState.NETWORK_UNAVAILABLE)

        val merged = mergeRssHubProbeResults(local = listOf(local), probed = emptyList())

        assertEquals(1, merged.size)
        assertEquals("/cls/hot", merged.single().match.route.target)
        assertEquals(CandidateState.NETWORK_UNAVAILABLE, merged.single().state)
    }

    @Test
    fun `rsshub network result replaces local diagnostic without removing route`() {
        val local = rssHubResult(CandidateState.NETWORK_UNAVAILABLE)
        val probed = rssHubResult(CandidateState.AVAILABLE, SyndFeedImpl())

        val merged = mergeRssHubProbeResults(local = listOf(local), probed = listOf(probed))

        assertEquals(1, merged.size)
        assertEquals(CandidateState.AVAILABLE, merged.single().state)
        assertTrue(merged.single().available)
    }

    private fun probe(
        kind: SourceCandidateKind,
        sourceType: SourceType,
        url: String,
        articleCount: Int,
    ) =
        SubscribeCandidateProbe(
            feed = createFeed(articleCount),
            feedLink = url,
            sourceType = sourceType,
            kind = kind,
        )

    private fun rssHubResult(
        state: CandidateState,
        feed: SyndFeedImpl? = null,
    ) =
        RssHubProbeResult(
            match =
                RssHubRouteMatch(
                    route =
                        RssHubRouteDefinition(
                            id = "cls:/hot:cls.cn:/",
                            name = "热门文章排行榜",
                            host = "cls.cn",
                            pathPrefix = "/",
                            target = "/cls/hot",
                        ),
                    feedUrl = "https://rsshub.app/cls/hot",
                ),
            state = state,
            feed = feed,
        )

    private fun createFeed(count: Int) =
        SyndFeedImpl().apply {
            entries =
                List(count) { index ->
                    SyndEntryImpl().apply {
                        title = "Article $index"
                        link = "https://example.com/articles/$index"
                        publishedDate = Date(1_700_000_000_000L - index * 60_000L)
                    }
                }
        }
}
