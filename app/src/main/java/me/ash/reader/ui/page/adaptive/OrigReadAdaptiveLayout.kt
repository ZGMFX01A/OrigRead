package me.ash.reader.ui.page.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class OrigReadWindowWidthClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge,
}

enum class OrigReadWindowHeightClass {
    Compact,
    Medium,
    Expanded,
}

/** OrigRead 对当前 app window 的统一产品级能力描述。 */
data class OrigReadAdaptiveLayoutProfile(
    val widthClass: OrigReadWindowWidthClass,
    val heightClass: OrigReadWindowHeightClass,
) {
    val usesPhoneLikeFlow: Boolean
        get() =
            heightClass == OrigReadWindowHeightClass.Compact ||
                widthClass == OrigReadWindowWidthClass.Compact ||
                widthClass == OrigReadWindowWidthClass.Medium

    val supportsListDetailWorkspace: Boolean
        get() = !usesPhoneLikeFlow && widthClass >= OrigReadWindowWidthClass.Expanded

    val supportsThreePaneWorkspace: Boolean
        get() =
            heightClass != OrigReadWindowHeightClass.Compact &&
                (widthClass == OrigReadWindowWidthClass.Large ||
                    widthClass == OrigReadWindowWidthClass.ExtraLarge)
}

/**
 * 使用 Android 官方 Window Size Class 断点做集中分类。
 *
 * 输入必须是当前 app window 的 dp 尺寸，而不是设备物理屏幕尺寸；Foldable posture / hinge 会在 R09.5
 * 叠加到同一 Profile 之上。
 */
internal fun origReadAdaptiveLayoutProfile(
    widthDp: Int,
    heightDp: Int,
): OrigReadAdaptiveLayoutProfile =
    OrigReadAdaptiveLayoutProfile(
        widthClass =
            when {
                widthDp >= 1600 -> OrigReadWindowWidthClass.ExtraLarge
                widthDp >= 1200 -> OrigReadWindowWidthClass.Large
                widthDp >= 840 -> OrigReadWindowWidthClass.Expanded
                widthDp >= 600 -> OrigReadWindowWidthClass.Medium
                else -> OrigReadWindowWidthClass.Compact
            },
        heightClass =
            when {
                heightDp >= 900 -> OrigReadWindowHeightClass.Expanded
                heightDp >= 480 -> OrigReadWindowHeightClass.Medium
                else -> OrigReadWindowHeightClass.Compact
            },
    )

val LocalOrigReadAdaptiveLayoutProfile =
    staticCompositionLocalOf {
        OrigReadAdaptiveLayoutProfile(
            widthClass = OrigReadWindowWidthClass.Compact,
            heightClass = OrigReadWindowHeightClass.Medium,
        )
    }

/** 大屏页面的语义化内容宽度；Reader 正文继续使用 LocalTextContentWidth。 */
enum class OrigReadContentWidth(val maxWidth: Dp) {
    Compact(720.dp),
    Comfortable(880.dp),
    Editor(1120.dp),
}

/**
 * 手机窗口小于语义上限时保持 fillMaxWidth；更宽窗口中居中限宽，避免设置项、输入框和列表无限拉伸。
 */
@Composable
fun OrigReadAdaptiveContent(
    width: OrigReadContentWidth,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier.widthIn(max = width.maxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(),
            content = content,
        )
    }
}
