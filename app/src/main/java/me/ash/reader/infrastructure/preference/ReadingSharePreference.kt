package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore

enum class ReadingShareTarget {
    SYSTEM,
    OBSIDIAN,
    NOTION,
}

/** 阅读页专用分享配置；列表页继续使用 [SharedContentPreference]。 */
@Stable
data class ReadingSharePreference(
    val isConfigured: Boolean = false,
    val includeTitle: Boolean = true,
    val includeBody: Boolean = false,
    val includeTranslation: Boolean = false,
    val includeSummary: Boolean = false,
    /** 默认保留 Android 系统分享面板；应用专用目标必须由用户主动选择。 */
    val target: ReadingShareTarget = ReadingShareTarget.SYSTEM,
) {
    fun save(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.edit { preferences ->
                preferences[configuredKey] = true
                preferences[titleKey] = includeTitle
                preferences[bodyKey] = includeBody
                preferences[translationKey] = includeTranslation
                preferences[summaryKey] = includeSummary
                preferences[targetKey] = target.name
                preferences[targetExplicitKey] = true
            }
        }
    }

    companion object {
        val default = ReadingSharePreference()

        private val configuredKey =
            DataStoreKey.keys[DataStoreKey.readingShareConfigured]?.key as Preferences.Key<Boolean>
        private val titleKey =
            DataStoreKey.keys[DataStoreKey.readingShareTitle]?.key as Preferences.Key<Boolean>
        private val bodyKey =
            DataStoreKey.keys[DataStoreKey.readingShareBody]?.key as Preferences.Key<Boolean>
        private val translationKey =
            DataStoreKey.keys[DataStoreKey.readingShareTranslation]?.key as Preferences.Key<Boolean>
        private val summaryKey =
            DataStoreKey.keys[DataStoreKey.readingShareSummary]?.key as Preferences.Key<Boolean>
        private val targetKey =
            DataStoreKey.keys[DataStoreKey.readingShareTarget]?.key as Preferences.Key<String>
        private val targetExplicitKey =
            DataStoreKey.keys[DataStoreKey.readingShareTargetExplicit]?.key as Preferences.Key<Boolean>

        fun fromPreferences(preferences: Preferences): ReadingSharePreference =
            ReadingSharePreference(
                isConfigured = preferences[configuredKey] ?: false,
                includeTitle = preferences[titleKey] ?: default.includeTitle,
                includeBody = preferences[bodyKey] ?: default.includeBody,
                includeTranslation = preferences[translationKey] ?: default.includeTranslation,
                includeSummary = preferences[summaryKey] ?: default.includeSummary,
                target =
                    if (preferences[targetExplicitKey] == true) {
                        preferences[targetKey]
                            ?.let { value -> runCatching { ReadingShareTarget.valueOf(value) }.getOrNull() }
                            ?: default.target
                    } else {
                        // 旧版本曾把 Obsidian 作为隐式默认值，升级后必须回到系统分享面板。
                        default.target
                    },
            )
    }
}
