package me.ash.reader.infrastructure.rsshub

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RssHubSubscriptionRepositoryTest {
    @Test
    fun `received logical source supports recovery and duplicate lookup without a prior refresh`() {
        val repository = repository()
        repository.replaceSyncSource("remote-feed", "rsshub://zhihu/hot")
        assertEquals("/zhihu/hot", repository.descriptor("remote-feed")?.routePath)
        assertEquals(listOf("remote-feed"), repository.findFeedIdsByRoute("/zhihu/hot"))
    }

    @Test
    fun `received authenticated source keeps its original instance binding`() {
        val repository = repository()
        repository.replaceSyncSource("remote-feed", "https://rsshub.app/zhihu/hot?key=secret")
        assertEquals("/zhihu/hot?key=secret", repository.descriptor("remote-feed")?.routePath)
        assertEquals("https://rsshub.app", repository.descriptor("remote-feed")?.preferredInstance)
    }

    @Test
    fun `legacy source-only backup retains route identity on a disabled custom instance`() {
        val repository = repository(
            initialValues = mapOf("source_url_legacy-feed" to "https://custom.example/rss/zhihu/hot?code=secret"),
            settingsValue = RssHubSettings(instances = listOf(
                RssHubInstance("custom", "https://custom.example/rss", "", "", enabled = false, builtIn = false),
            )),
        )
        assertEquals("/zhihu/hot?code=secret", repository.descriptor("legacy-feed")?.routePath)
        assertEquals("https://custom.example/rss", repository.descriptor("legacy-feed")?.preferredInstance)
        assertEquals(listOf("legacy-feed"), repository.findFeedIdsByRoute("/zhihu/hot?code=secret"))
    }

    @Test
    fun `sync preserves descriptor for the same source and clears stale route for a changed source`() {
        val repository = repository()
        val descriptor = RssHubSubscriptionDescriptor(
            originalInput = "rsshub://github/issue/example/repo",
            routePath = "/github/issue/example/repo",
            preferredInstance = "https://hub.example",
            lastResolvedInstance = "https://hub.example",
            lastResolvedUrl = "https://hub.example/github/issue/example/repo",
        )
        repository.record("feed-1", descriptor)
        repository.replaceSyncSource("feed-1", " ${descriptor.originalInput} ")
        assertEquals(descriptor, repository.descriptor("feed-1"))
        repository.replaceSyncSource("feed-1", "rsshub://other/route")
        assertEquals(
            RssHubSubscriptionDescriptor(originalInput = "rsshub://other/route", routePath = "/other/route"),
            repository.descriptor("feed-1"),
        )
        repository.replaceSyncSource("feed-1", null)
        assertNull(repository.descriptor("feed-1"))
        assertNull(repository.sourceUrl("feed-1"))
    }

    @Test
    fun `descriptor write commits durably before returning`() {
        val editor = mock<SharedPreferences.Editor>()
        val repository = repository(editor)
        repository.record("feed-1", RssHubSubscriptionDescriptor(originalInput = "/github/issue/example/repo"))
        verify(editor).commit()
    }

    private fun repository(
        editor: SharedPreferences.Editor = mock(),
        initialValues: Map<String, String> = emptyMap(),
        settingsValue: RssHubSettings = RssHubSettings(),
    ): RssHubSubscriptionRepository {
        val values = initialValues.toMutableMap()
        val preferences = mock<SharedPreferences>()
        whenever(preferences.all).thenAnswer { values.toMap() }
        whenever(preferences.edit()).thenReturn(editor)
        whenever(preferences.getString(any(), anyOrNull())).thenAnswer { invocation ->
            values[invocation.getArgument<String>(0)] ?: invocation.getArgument<String?>(1)
        }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer { invocation ->
            invocation.getArgument<String?>(1)?.let { values[invocation.getArgument<String>(0)] = it }
            editor
        }
        whenever(editor.remove(any())).thenAnswer { invocation ->
            values.remove(invocation.getArgument<String>(0))
            editor
        }
        whenever(editor.commit()).thenReturn(true)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        val settings = mock<RssHubSettingsRepository>()
        whenever(settings.current()).thenReturn(settingsValue)
        return RssHubSubscriptionRepository(context, settings)
    }
}
