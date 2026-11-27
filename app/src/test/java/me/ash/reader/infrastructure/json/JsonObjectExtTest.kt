package me.ash.reader.infrastructure.json

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonObjectExtTest {
    @Test
    fun `optNullableString returns null for missing blank and json null values`() {
        val json =
            JSONObject(
                """{"nullable":null,"blank":"   ","text":" hello "}"""
            )
        assertNull(json.optNullableString("missing"))
        assertNull(json.optNullableString("nullable"))
        assertNull(json.optNullableString("blank"))
        assertEquals("hello", json.optNullableString("text"))
    }

    @Test
    fun `optCleanString returns default for missing and json null without literal null`() {
        val json = JSONObject("""{"nullable":null,"value":"ok","empty":""}""")
        assertEquals("", json.optCleanString("missing"))
        assertEquals("fallback", json.optCleanString("nullable", "fallback"))
        assertEquals("", json.optCleanString("nullable"))
        assertEquals("ok", json.optCleanString("value"))
        assertEquals("", json.optCleanString("empty", "fallback"))
    }

    @Test
    fun `json array optNullableString skips json null and blank entries`() {
        val array = JSONArray("""[null, " ", "valid"]""")
        assertNull(array.optNullableString(0))
        assertNull(array.optNullableString(1))
        assertEquals("valid", array.optNullableString(2))
    }
}
