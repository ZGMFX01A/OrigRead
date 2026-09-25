package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMissingRangesTest {
    @Test
    fun `operation budget bounds huge actor ranges across lanes`() {
        val ranges = missingRanges(
            linkedMapOf("LIBRARY" to mapOf("a" to 1000L), "READING" to mapOf("b" to Long.MAX_VALUE)),
            mapOf("LIBRARY" to mapOf("a" to 990L)),
            500,
        )
        assertEquals(2, ranges.size)
        assertEquals(991L, ranges[0].fromSequence)
        assertEquals(1000L, ranges[0].toSequence)
        assertEquals(490L, ranges[1].toSequence)
        assertEquals(500L, ranges.sumOf { it.toSequence - it.fromSequence + 1 })
    }

    @Test
    fun `received prefix advances the next batch`() {
        val remote = mapOf("LIBRARY" to mapOf("a" to 1200L))
        val second = missingRanges(remote, mapOf("LIBRARY" to mapOf("a" to 500L)), 500)
        assertEquals(501L, second.single().fromSequence)
        assertEquals(1000L, second.single().toSequence)
        assertTrue(missingRanges(remote, remote, 500).isEmpty())
    }
}
