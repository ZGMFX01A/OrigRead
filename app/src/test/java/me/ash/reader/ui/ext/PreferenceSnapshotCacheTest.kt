package me.ash.reader.ui.ext

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreferenceSnapshotCacheTest {
    @Test
    fun `temporary read failure does not permanently stop observing recovered data`() = runTest {
        val key = stringPreferencesKey("language")
        val latest = MutableStateFlow(preferencesOf(key to "initial"))
        var failFirstRead = true
        var failures = 0
        val store = object : DataStore<Preferences> {
            override val data = kotlinx.coroutines.flow.flow {
                if (failFirstRead) {
                    failFirstRead = false
                    throw java.io.IOException("temporary read failure")
                }
                emitAll(latest)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(latest.value).also { latest.value = it }
        }
        val cache = PreferenceSnapshotCache(store, backgroundScope) { failures++ }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("initial", cache.values.value?.get(key))
        latest.value = preferencesOf(key to "recovered-update")
        runCurrent()
        assertEquals("recovered-update", cache.values.value?.get(key))
        assertEquals(1, failures)
    }

    @Test
    fun `cache starts asynchronously and write publishes before delayed collector`() = runTest {
        val key = stringPreferencesKey("skipVersionNumber")
        val store = MemoryStore(preferencesOf(key to "old"))
        val cache = PreferenceSnapshotCache(store, backgroundScope) { throw AssertionError(it) }
        assertNull(cache.values.value)
        runCurrent()
        assertEquals("old", cache.values.value?.get(key))

        cache.write { store.edit { it[key] = "" } }
        // The collector is still queued on the test scheduler, as during an immediate update check.
        assertEquals("", cache.values.value?.get(key))
        cache.write { store.edit { it[key] = "new" } }
        assertEquals("new", cache.values.value?.get(key))
        runCurrent()
        assertEquals("new", cache.values.value?.get(key))
    }

    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
