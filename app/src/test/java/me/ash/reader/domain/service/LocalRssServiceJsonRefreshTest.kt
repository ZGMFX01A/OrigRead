package me.ash.reader.domain.service

import java.util.Date
import me.ash.reader.domain.model.article.Article
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRssServiceJsonRefreshTest {
    @Test
    fun `json refresh replaces remote content but preserves local reading state`() {
        val originalUpdateAt = Date(1_700_000_000_000)
        val existing =
            Article(
                id = "account-old-id",
                date = Date(1_690_000_000_000),
                title = "Old title",
                author = "Old author",
                rawDescription = "<p>Old excerpt</p>",
                shortDescription = "Old excerpt",
                fullContent = "cached full content",
                img = "https://example.com/old.jpg",
                link = "https://example.com/post",
                feedId = "feed-1",
                accountId = 1,
                isUnread = false,
                isStarred = true,
                isReadLater = true,
                updateAt = originalUpdateAt,
            )
        val fetched =
            Article(
                id = "new-random-id",
                date = Date(1_710_000_000_000),
                title = "Updated title",
                author = "Updated author",
                rawDescription = "<p>Full WordPress content</p>",
                shortDescription = "Full WordPress content",
                img = null,
                link = "https://example.com/post",
                feedId = "temporary-feed",
                accountId = 1,
                isUnread = true,
                isStarred = false,
                isReadLater = false,
                updateAt = Date(1_720_000_000_000),
            )

        val merged = mergeJsonArticleRefresh(existing, fetched)

        assertEquals(existing.id, merged.id)
        assertEquals("Updated title", merged.title)
        assertEquals("Updated author", merged.author)
        assertEquals("<p>Full WordPress content</p>", merged.rawDescription)
        assertEquals("Full WordPress content", merged.shortDescription)
        assertEquals(existing.img, merged.img)
        assertEquals(existing.feedId, merged.feedId)
        assertEquals(existing.accountId, merged.accountId)
        assertEquals(originalUpdateAt, merged.updateAt)
        assertEquals("cached full content", merged.fullContent)
        assertFalse(merged.isUnread)
        assertTrue(merged.isStarred)
        assertTrue(merged.isReadLater)
    }
}
