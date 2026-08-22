package me.ash.reader.domain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffSyncStateTest {
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
