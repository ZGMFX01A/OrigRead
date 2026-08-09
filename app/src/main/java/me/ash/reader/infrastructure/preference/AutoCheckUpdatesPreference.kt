package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.autoCheckUpdates
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalAutoCheckUpdates =
    compositionLocalOf<AutoCheckUpdatesPreference> { AutoCheckUpdatesPreference.default }

/** 控制 GitHub 渠道是否在应用启动时静默检查最新 Release。 */
sealed class AutoCheckUpdatesPreference(val value: Boolean) : Preference() {
    data object ON : AutoCheckUpdatesPreference(true)

    data object OFF : AutoCheckUpdatesPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(autoCheckUpdates, value)
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(autoCheckUpdates, !value)
        }
    }

    companion object {
        val default = ON

        @Suppress("UNCHECKED_CAST")
        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[autoCheckUpdates]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}
