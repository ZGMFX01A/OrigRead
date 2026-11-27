package me.ash.reader.ui.ext

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Writes and refreshes share a lock so a delayed collector cannot publish a pre-write snapshot. */
internal class PreferenceSnapshotCache(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    private val reportReadFailure: (IOException) -> Unit,
) {
    private val mutex = Mutex()
    private val snapshot = MutableStateFlow<Preferences?>(null)
    val values = snapshot.asStateFlow()

    init {
        scope.launch {
            var retryDelayMillis = 1_000L
            while (isActive) {
                try {
                    store.data.collect {
                        mutex.withLock {
                            // Re-read inside the lock: the notification may precede a completed write.
                            snapshot.value = store.data.first()
                        }
                        retryDelayMillis = 1_000L
                    }
                    return@launch
                } catch (error: IOException) {
                    reportReadFailure(error)
                    mutex.withLock {
                        // Keep the last good snapshot, including a write completed during the failure.
                        if (snapshot.value == null) snapshot.value = emptyPreferences()
                    }
                    delay(retryDelayMillis)
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    suspend fun write(update: suspend () -> Preferences) {
        mutex.withLock { snapshot.value = update() }
    }
}
