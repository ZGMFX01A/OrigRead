package me.ash.reader.ui.page.home.feeds.subscribe

import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.source.SourceCandidateKind
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
    fun `invalid candidate is omitted`() {
        val invalid = SyndFeedImpl().apply { entries = emptyList() }

        val ranked =
            SubscribeCandidateSelector.rank(
                listOf(
                    SubscribeCandidateProbe(
                        feed = invalid,
                        feedLink = "https://example.com/empty",
                        sourceType = SourceType.JSON,
                        kind = SourceCandidateKind.JSON,
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
