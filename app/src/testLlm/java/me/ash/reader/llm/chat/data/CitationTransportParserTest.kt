package me.ash.reader.llm.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationTransportParserTest {
    @Test
    fun `compact multi evidence token becomes one structured occurrence`() {
        val parsed =
            CitationTransportParser.parse(
                transportText = "结论 [[E21][E53]].",
                allowedProtocolIds = setOf("E21", "E53"),
                final = true,
            )

        assertEquals("结论.", parsed.canonicalText)
        assertEquals(1, parsed.annotations.size)
        assertEquals(listOf("E21", "E53"), parsed.annotations.single().protocolIds)
        assertEquals(2, parsed.annotations.single().canonicalInsertionOffset)
        assertFalse(parsed.hasIncompleteTransport)
        assertTrue(parsed.invalidProtocolIds.isEmpty())
    }

    @Test
    fun `adjacent standard tokens remain distinct occurrences at the same insertion point`() {
        val parsed =
            CitationTransportParser.parse(
                transportText = "结论 [[E1]][[E2]].",
                allowedProtocolIds = setOf("E1", "E2"),
                final = true,
            )

        assertEquals("结论.", parsed.canonicalText)
        assertEquals(listOf(0, 1), parsed.annotations.map { it.occurrenceOrdinal })
        assertEquals(listOf(listOf("E1"), listOf("E2")), parsed.annotations.map { it.protocolIds })
        assertEquals(listOf(2, 2), parsed.annotations.map { it.canonicalInsertionOffset })
    }

    @Test
    fun `invalid member is rejected without discarding allowed evidence in the same token`() {
        val parsed =
            CitationTransportParser.parse(
                transportText = "结论 [[E21][E999]].",
                allowedProtocolIds = setOf("E21"),
                final = true,
            )

        assertEquals("结论.", parsed.canonicalText)
        assertEquals(listOf("E21"), parsed.annotations.single().protocolIds)
        assertEquals(listOf("E999"), parsed.invalidProtocolIds)
    }

    @Test
    fun `streaming incomplete token stays hidden and terminal parse removes it`() {
        val streaming =
            CitationTransportParser.parse(
                transportText = "结论 [[E21",
                allowedProtocolIds = setOf("E21"),
                final = false,
            )
        val terminal =
            CitationTransportParser.parse(
                transportText = "结论 [[E21",
                allowedProtocolIds = setOf("E21"),
                final = true,
            )

        assertEquals("结论", streaming.canonicalText)
        assertTrue(streaming.hasIncompleteTransport)
        assertEquals("结论", terminal.canonicalText)
        assertFalse(terminal.hasIncompleteTransport)
        assertEquals(1, terminal.invalidFragmentCount)
    }

    @Test
    fun `citation shaped text inside inline and fenced code remains literal`() {
        val parsed =
            CitationTransportParser.parse(
                transportText =
                    "Inline `[[E1]]`.\n\n```text\n[[E1]]\n```\n\nReal [[E1]].",
                allowedProtocolIds = setOf("E1"),
                final = true,
            )

        assertEquals("Inline `[[E1]]`.\n\n```text\n[[E1]]\n```\n\nReal.", parsed.canonicalText)
        assertEquals(1, parsed.annotations.size)
        assertEquals(listOf("E1"), parsed.annotations.single().protocolIds)
    }

    @Test
    fun `ordinary double bracket text is never owned by citation protocol`() {
        val source = "Wiki [[Page Name]] and [[Evil]] and matrix [[a, b], [c, d]] stay literal. Real [[E1]]."

        val parsed =
            CitationTransportParser.parse(
                transportText = source,
                allowedProtocolIds = setOf("E1"),
                final = true,
            )

        assertEquals("Wiki [[Page Name]] and [[Evil]] and matrix [[a, b], [c, d]] stay literal. Real.", parsed.canonicalText)
        assertEquals(listOf("E1"), parsed.annotations.single().protocolIds)
        assertEquals(0, parsed.invalidFragmentCount)
    }

    @Test
    fun `commonmark tilde fence and longer opening fence protect citation shaped text`() {
        val parsed =
            CitationTransportParser.parse(
                transportText =
                    "~~~~text\n[[E1]]\n~~~\nstill [[E1]]\n~~~~\n\n~~~\n[[E1]]\n~~~\n\nReal [[E1]].",
                allowedProtocolIds = setOf("E1"),
                final = true,
            )

        assertEquals(
            "~~~~text\n[[E1]]\n~~~\nstill [[E1]]\n~~~~\n\n~~~\n[[E1]]\n~~~\n\nReal.",
            parsed.canonicalText,
        )
        assertEquals(1, parsed.annotations.size)
    }

    @Test
    fun `streaming only hides a possible citation protocol tail`() {
        val ordinary =
            CitationTransportParser.parse(
                transportText = "Wiki [[Page",
                allowedProtocolIds = emptySet(),
                final = false,
            )
        val possibleProtocol =
            CitationTransportParser.parse(
                transportText = "Claim [[",
                allowedProtocolIds = setOf("E1"),
                final = false,
            )

        assertEquals("Wiki [[Page", ordinary.canonicalText)
        assertFalse(ordinary.hasIncompleteTransport)
        assertEquals("Claim", possibleProtocol.canonicalText)
        assertTrue(possibleProtocol.hasIncompleteTransport)
    }

    @Test
    fun `incremental accumulator matches one shot parser across token boundaries`() {
        val accumulator = CitationTransportAccumulator(setOf("E1", "E2"))

        accumulator.append("结论 [[E")
        assertEquals("结论", accumulator.snapshot().canonicalText)
        assertTrue(accumulator.snapshot().hasIncompleteTransport)

        accumulator.append("1][E2]]。")
        val incremental = accumulator.finish()
        val oneShot =
            CitationTransportParser.parse(
                transportText = "结论 [[E1][E2]]。",
                allowedProtocolIds = setOf("E1", "E2"),
                final = true,
            )

        assertEquals(oneShot, incremental)
    }

    @Test
    fun `incremental accumulator matches one shot parser at every single split point`() {
        val fixtures =
            listOf(
                "Claim [[E1]][[E2]].",
                "Claim [[E1][E2]]， next.",
                "Claim [[E1x]] then [[E2]].",
                "Inline `[[E1]]` then real [[E2]].",
                "```text\n[[E1]]\n```\nReal [[E2]].",
                "~~~~text\n[[E1]]\n~~~~\nReal [[E2]].",
                "Wiki [[Page Name]] then [[E1]].",
                "Before\n  ```lang\n[[E1]]\n  ```\nAfter [[E2]].",
            )
        val allowed = setOf("E1", "E2")

        fixtures.forEach { source ->
            val expected = CitationTransportParser.parse(source, allowed, final = true)
            for (split in 0..source.length) {
                val accumulator = CitationTransportAccumulator(allowed)
                accumulator.append(source.substring(0, split))
                accumulator.snapshot()
                accumulator.append(source.substring(split))
                assertEquals("split=$split source=$source", expected, accumulator.finish())
            }
        }
    }

    @Test
    fun `character by character streaming never leaks an unfinished citation prefix`() {
        val source = "Claim [[E1][E2]]."
        val accumulator = CitationTransportAccumulator(setOf("E1", "E2"))

        source.forEachIndexed { index, character ->
            accumulator.append(character.toString())
            val snapshot = accumulator.snapshot()
            assertFalse(
                "unfinished transport leaked after char $index: ${snapshot.canonicalText}",
                snapshot.canonicalText.contains("[[E"),
            )
        }

        assertEquals(
            CitationTransportParser.parse(source, setOf("E1", "E2"), final = true),
            accumulator.finish(),
        )
    }
}
