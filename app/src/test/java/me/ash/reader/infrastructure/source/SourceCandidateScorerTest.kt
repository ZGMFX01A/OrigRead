package me.ash.reader.infrastructure.source

import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCandidateScorerTest {
    @Test
    fun `direct rss wins when content quality is equal`() {
        val feed = createFeed(20)

        val direct = SourceCandidateScorer.score(feed, SourceCandidateKind.RSS_DIRECT)
        val website = SourceCandidateScorer.score(feed, SourceCandidateKind.WEBSITE)

        assertTrue(direct.accepted)
        assertTrue(direct.score > website.score)
    }

    @Test
    fun `static website wins over dynamic rendering when content quality is equal`() {
        val feed = createFeed(20)

        val staticWebsite = SourceCandidateScorer.score(feed, SourceCandidateKind.WEBSITE)
        val dynamicWebsite = SourceCandidateScorer.score(feed, SourceCandidateKind.WEBSITE_DYNAMIC)

        assertTrue(staticWebsite.accepted)
        assertTrue(dynamicWebsite.accepted)
        assertTrue(staticWebsite.score > dynamicWebsite.score)
    }

    @Test
    fun `structured json is not rejected by website link quality rules`() {
        val feed = SyndFeedImpl().apply {
            entries = List(10) { index ->
                SyndEntryImpl().apply {
                    title = "Article $index"
                    link = "javascript:void(0)"
                }
            }
        }

        val result = SourceCandidateScorer.score(feed, SourceCandidateKind.JSON)

        assertTrue(result.accepted)
        assertTrue(result.validLinkRate == 0.0)
    }

    @Test
    fun `parsed rss stays selectable when entries have no http link`() {
        val feed = SyndFeedImpl().apply {
            entries = List(3) { index ->
                SyndEntryImpl().apply {
                    title = "Episode $index"
                    link = ""
                }
            }
        }

        assertTrue(SourceCandidateScorer.score(feed, SourceCandidateKind.RSS_DIRECT).accepted)
        assertTrue(SourceCandidateScorer.score(feed, SourceCandidateKind.RSS_DISCOVERED).accepted)
        assertTrue(SourceCandidateScorer.score(feed, SourceCandidateKind.RSSHUB).accepted)
        assertTrue(SourceCandidateScorer.score(feed, SourceCandidateKind.JSON).accepted)
        assertFalse(SourceCandidateScorer.score(feed, SourceCandidateKind.WEBSITE).accepted)
    }

    @Test
    fun `empty structured source stays selectable while empty website is rejected`() {
        val empty = SyndFeedImpl().apply { entries = emptyList() }

        assertTrue(SourceCandidateScorer.score(empty, SourceCandidateKind.RSS_DIRECT).accepted)
        assertTrue(SourceCandidateScorer.score(empty, SourceCandidateKind.RSS_DISCOVERED).accepted)
        assertTrue(SourceCandidateScorer.score(empty, SourceCandidateKind.RSSHUB).accepted)
        assertTrue(SourceCandidateScorer.score(empty, SourceCandidateKind.JSON).accepted)
        assertFalse(SourceCandidateScorer.score(empty, SourceCandidateKind.WEBSITE).accepted)
        assertFalse(SourceCandidateScorer.score(empty, SourceCandidateKind.WEBSITE_DYNAMIC).accepted)
    }

    @Test
    fun `high quality json can beat weak rsshub candidate`() {
        val json = SourceCandidateScorer.score(createFeed(30), SourceCandidateKind.JSON)
        val rssHub = SourceCandidateScorer.score(createFeed(2), SourceCandidateKind.RSSHUB)

        assertTrue(json.score > rssHub.score)
    }

    @Test
    fun `dynamic website fallback accepts safe links that static website rejects for title quality`() {
        val feed =
            SyndFeedImpl().apply {
                entries =
                    listOf("A", "B", "Long article title 1", "Long article title 2").mapIndexed { index, title ->
                        SyndEntryImpl().apply {
                            this.title = title
                            link = "https://example.com/articles/$index"
                        }
                    }
            }

        val staticWebsite = SourceCandidateScorer.score(feed, SourceCandidateKind.WEBSITE)
        val dynamicWebsite = SourceCandidateScorer.score(feed, SourceCandidateKind.WEBSITE_DYNAMIC)

        assertFalse(staticWebsite.accepted)
        assertTrue(dynamicWebsite.accepted)
    }

    private fun createFeed(count: Int) =
        SyndFeedImpl().apply {
            entries = List(count) { index ->
                SyndEntryImpl().apply {
                    title = "Article $index"
                    link = "https://example.com/articles/$index"
                    publishedDate = Date(1_700_000_000_000L - index * 60_000L)
                }
            }
        }
}
