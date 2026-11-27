package me.ash.reader.infrastructure.storage

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.mockito.kotlin.*

/** In-memory preferences with atomic Editor batches for persisted-state reload assertions. */
class SettingsTestEnvironment(initial: Map<String, Any> = emptyMap()) {
    val values = java.util.concurrent.ConcurrentHashMap(initial)
    val preferences: SharedPreferences = mock()
    val context: Context = mock()
    init {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        whenever(preferences.getString(any(), anyOrNull())).thenAnswer { values[it.getArgument<String>(0)] as String? ?: it.getArgument<String?>(1) }
        whenever(preferences.getBoolean(any(), any())).thenAnswer { values[it.getArgument<String>(0)] as Boolean? ?: it.getArgument<Boolean>(1) }
        whenever(preferences.getInt(any(), any())).thenAnswer { values[it.getArgument<String>(0)] as Int? ?: it.getArgument<Int>(1) }
        whenever(preferences.getLong(any(), any())).thenAnswer { values[it.getArgument<String>(0)] as Long? ?: it.getArgument<Long>(1) }
        whenever(preferences.contains(any())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
        whenever(preferences.all).thenAnswer { values.toMap() }
        whenever(preferences.edit()).thenAnswer { editor() }
    }
    private fun editor(): SharedPreferences.Editor {
        val editor = mock<SharedPreferences.Editor>()
        val pending = mutableMapOf<String, Any?>()
        var clear = false
        whenever(editor.putString(any(), anyOrNull())).thenAnswer { pending[it.getArgument(0)] = it.getArgument<String?>(1); editor }
        whenever(editor.putBoolean(any(), any())).thenAnswer { pending[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }
        whenever(editor.putInt(any(), any())).thenAnswer { pending[it.getArgument(0)] = it.getArgument<Int>(1); editor }
        whenever(editor.putLong(any(), any())).thenAnswer { pending[it.getArgument(0)] = it.getArgument<Long>(1); editor }
        whenever(editor.remove(any())).thenAnswer { pending[it.getArgument(0)] = null; editor }
        whenever(editor.clear()).thenAnswer { clear = true; editor }
        doAnswer {
            synchronized(values) {
                if (clear) values.clear()
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
            null
        }.whenever(editor).apply()
        return editor
    }
}

fun concurrently(count: Int, action: (Int) -> Unit) {
    val pool = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    try {
        val jobs = (0 until count).map { index -> pool.submit { check(start.await(5, TimeUnit.SECONDS)); action(index) } }
        start.countDown()
        jobs.forEach { it.get(30, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
        check(pool.awaitTermination(10, TimeUnit.SECONDS))
    }
}
