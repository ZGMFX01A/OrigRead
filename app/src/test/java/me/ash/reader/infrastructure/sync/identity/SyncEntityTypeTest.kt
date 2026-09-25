package me.ash.reader.infrastructure.sync.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncEntityTypeTest {
    @Test
    fun wireNamesAreUniqueAndRoundTrip() {
        val values = SyncEntityType.entries
        assertEquals(
            listOf(
                "group",
                "feed",
                "article",
                "filter_rule",
                "website_rule",
                "json_rule",
                "rsshub_settings",
                "website_parse_preference",
                "rsshub_subscription_source",
                "conversation",
                "conversation_article",
                "message",
                "tool_call",
                "context_ref",
                "evidence_block",
                "citation_ref",
                "citation_annotation",
                "citation_annotation_ref",
                "alias_edge",
            ),
            values.map { it.wireName },
        )
        assertEquals(values.size, values.map { it.wireName }.toSet().size)
        values.forEach { type -> assertEquals(type, SyncEntityType.fromWireName(type.wireName)) }
        assertNull(SyncEntityType.fromWireName("feeds"))
    }
}
