package me.ash.reader.domain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffSyncStateTest {
    @Test fun `remote retries stop after bounded exponential backoff`() {
        assertEquals(listOf(5000L, 10000L, 20000L, 40000L, 80000L), (0..4).map(::remoteDiffRetryDelay))
        org.junit.Assert.assertNull(remoteDiffRetryDelay(5))
        org.junit.Assert.assertNull(remoteDiffRetryDelay(Int.MAX_VALUE))
    }


    @Test fun `cache restores missing entries without overwriting live or completed changes`() {
        val state = DiffSyncState()
        val live = Diff(true, "live", "feed")
        val completed = Diff(false, "completed", "feed")
        state.append(live)
        state.append(completed)
        state.complete(state.begin(mapOf(completed.articleId to completed)), setOf(completed.articleId))
        val cached = Diff(false, "cached", "feed")
        state.restore(mapOf("live" to live.copy(isUnread = false), "completed" to completed.copy(isUnread = true), "cached" to cached))
        assertEquals(mapOf("live" to live, "cached" to cached), state.pending.toMap())
    }

    @Test
    fun `keeps compensation when user changes target during in-flight sync`() {
        val state = DiffSyncState()
        val read = Diff(isUnread = true, articleId = "article", feedId = "feed")
        val unread = read.copy(isUnread = false)

        state.append(read)
        state.complete(state.begin(state.pending.toMap()), setOf(read.articleId))
        state.append(unread)
        val inFlight = state.begin(state.pending.toMap())

        state.append(read)
        state.complete(inFlight, setOf(read.articleId))

        assertEquals(read, state.pending[read.articleId])
        assertEquals(read, state.begin(state.pending.toMap())[read.articleId])
    }

    @Test
    fun `keeps pending target when in-flight sync fails`() {
        val state = DiffSyncState()
        val unread = Diff(isUnread = false, articleId = "article", feedId = "feed")

        state.append(unread)
        val inFlight = state.begin(state.pending.toMap())
        state.complete(inFlight, emptySet())

        assertTrue(state.pending.containsKey(unread.articleId))
        assertEquals(unread, state.begin(state.pending.toMap())[unread.articleId])
    }
}
