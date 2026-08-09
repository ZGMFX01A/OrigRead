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
    fun `candidate with invalid links is rejected`() {
        val feed = SyndFeedImpl().apply {
            entries = List(10) { index ->
                SyndEntryImpl().apply {
                    title = "Article $index"
                    link = "javascript:void(0)"
                }
            }
        }

        val result = SourceCandidateScorer.score(feed, SourceCandidateKind.JSON)

        assertFalse(result.accepted)
    }

    @Test
    fun `high quality json can beat weak rsshub candidate`() {
        val json = SourceCandidateScorer.score(createFeed(30), SourceCandidateKind.JSON)
        val rssHub = SourceCandidateScorer.score(createFeed(2), SourceCandidateKind.RSSHUB)

        assertTrue(json.score > rssHub.score)
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
