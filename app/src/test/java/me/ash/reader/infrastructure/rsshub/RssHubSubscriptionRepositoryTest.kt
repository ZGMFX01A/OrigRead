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
            RssHubSubscriptionDescriptor(originalInput = "rsshub://other/route"),
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

    private fun repository(editor: SharedPreferences.Editor = mock()): RssHubSubscriptionRepository {
        val values = mutableMapOf<String, String>()
        val preferences = mock<SharedPreferences>()
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
        return RssHubSubscriptionRepository(context)
    }
}
