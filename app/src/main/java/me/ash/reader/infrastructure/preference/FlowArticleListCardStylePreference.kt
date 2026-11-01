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
    /** 方案 1：极简内缩发丝线（仅同日相邻文章绘制 0.5dp 分割线，对齐正文 24dp 视觉线） */
    data object Divider : FlowArticleListCardStylePreference(0)

    /** 方案 2：Material 3 Expressive 分段卡片容器（根据同日位置动态圆角，以 2dp 呼吸缝代替画线） */
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
