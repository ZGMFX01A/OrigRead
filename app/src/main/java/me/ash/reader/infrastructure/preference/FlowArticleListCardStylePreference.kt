package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.flowArticleListCardStyle
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalFlowArticleListCardStyle =
    compositionLocalOf<FlowArticleListCardStylePreference> { FlowArticleListCardStylePreference.default }

sealed class FlowArticleListCardStylePreference(val value: Int) : Preference() {
    /** 同一日期组内，使用与正文对齐的横线分隔相邻文章。 */
    data object Divider : FlowArticleListCardStylePreference(0)

    /** 同一天的文章共用卡面。沿用原卡片设置的存储值。 */
    data object Segmented : FlowArticleListCardStylePreference(1)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(
                flowArticleListCardStyle,
                value
            )
        }
    }

    val description: String
        @Composable get() = when (this) {
            Divider -> stringResource(id = R.string.article_list_style_divider)
            Segmented -> stringResource(id = R.string.article_list_style_segmented)
        }

    fun toDesc(context: Context): String = when (this) {
        Divider -> context.getString(R.string.article_list_style_divider)
        Segmented -> context.getString(R.string.article_list_style_segmented)
    }

    companion object {
        val default = Divider
        val values = listOf(Divider, Segmented)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[flowArticleListCardStyle]?.key as Preferences.Key<Int>]) {
                0 -> Divider
                1 -> Segmented
                else -> default
            }
    }
}
