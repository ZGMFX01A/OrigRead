package me.ash.reader.infrastructure.rsshub

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RssHubSettingsRepositoryTest {
    @Test
    fun `recording failures removes expired cooldowns without touching live cooldowns or settings`() {
        val values = mutableMapOf<String, Any>(
            "cooldown_until_expired" to 900L,
            "route_cooldown_until_expired" to 1_000L,
            "route_cooldown_until_live" to 2_000L,
            "route_last_success_saved" to "https://saved.example",
            "enabled" to true,
        )
        val preferences = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.commit()).thenReturn(true)
        whenever(preferences.all).thenAnswer { values.toMap() }
        whenever(editor.putLong(any(), any())).thenAnswer { invocation ->
            values[invocation.getArgument<String>(0)] = invocation.getArgument<Long>(1); editor
        }
        whenever(editor.remove(any())).thenAnswer { invocation ->
            values.remove(invocation.getArgument<String>(0)); editor
        }
        val context = mock<Context>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        val repository = RssHubSettingsRepository(context)
        repository.recordRouteFailure("https://new.example", "zhihu/hot", 1_000L)
        assertEquals(false, values.containsKey("cooldown_until_expired"))
        assertEquals(false, values.containsKey("route_cooldown_until_expired"))
        assertEquals(2_000L, values["route_cooldown_until_live"])
        assertEquals("https://saved.example", values["route_last_success_saved"])
        assertEquals(true, values["enabled"])
        values["cooldown_until_later"] = 900L
        repository.recordFailure("https://other.example", 1_000L)
        assertEquals(false, values.containsKey("cooldown_until_later"))
        assertEquals(2_000L, values["route_cooldown_until_live"])
    }
    @Test
    fun `historical successes cannot reenable removed or disabled instances`() {
        val enabled = "https://enabled.example.com"
        val removed = "https://removed.example.com"
        val disabled = "https://disabled.example.com"
        val preferences = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.commit()).thenReturn(true)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        whenever(preferences.getString(any(), anyOrNull())).thenAnswer { invocation ->
            when (invocation.getArgument<String>(0)) {
                "instances" -> """[{"id":"enabled","url":"$enabled","enabled":true},{"id":"disabled","url":"$disabled","enabled":false}]"""
                "last_success_instance" -> removed
                "route_last_success_${"zhihu/hot".hashCode()}" -> disabled
                else -> invocation.getArgument<String?>(1)
            }
        }
        val context = mock<Context>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        val repository = RssHubSettingsRepository(context)
        assertEquals(listOf(enabled), repository.candidateInstances())
        assertEquals(listOf(enabled), repository.candidateInstancesForRoute("zhihu/hot"))
        repository.setInstanceEnabled("enabled", false)
        assertEquals(emptyList<String>(), repository.candidateInstancesForRoute("zhihu/hot"))
        repository.deleteInstance("disabled")
        assertEquals(emptyList<String>(), repository.candidateInstances())
    }
}
