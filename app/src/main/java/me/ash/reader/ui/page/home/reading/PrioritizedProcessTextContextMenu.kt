package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * 仅在 OrigRead 正文选区内调整 Compose 文本工具栏顺序。
 *
 * Android 对普通第三方 `PROCESS_TEXT` Activity 的系统排序没有“保证第一页”的公开控制能力；
 * Compose 1.10 已把最终文本菜单数据交给可替换 Provider，因此这里复用原始 Process Text 菜单项本身，
 * 只把 OrigRead 的目标项移到最前，既保留 Compose 已生成的选区内容与点击行为，也不依赖 Compose 1.12
 * 才提供的 SelectionState.selectedTexts。
 */
@Composable
internal fun PrioritizedProcessTextContextMenu(
    enabled: Boolean,
    targetLabel: String,
    content: @Composable () -> Unit,
) {
    val delegate = LocalTextContextMenuToolbarProvider.current
    if (!enabled || delegate == null) {
        content()
        return
    }

    val prioritizedProvider =
        remember(delegate, targetLabel) {
            PrioritizingTextContextMenuProvider(
                delegate = delegate,
                targetLabel = targetLabel,
            )
        }
    CompositionLocalProvider(
        LocalTextContextMenuToolbarProvider provides prioritizedProvider,
        content = content,
    )
}

/** 委托平台 Provider 展示菜单，只在展示前重排 OrigRead 自己的 Process Text 项。 */
private class PrioritizingTextContextMenuProvider(
    private val delegate: TextContextMenuProvider,
    private val targetLabel: String,
) : TextContextMenuProvider {
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        delegate.showTextContextMenu(
            object : TextContextMenuDataProvider by dataProvider {
                override fun data(): TextContextMenuData =
                    dataProvider.data().prioritizeProcessTextItem(targetLabel)
            }
        )
    }
}

/** 保持其他菜单项相对顺序不变，只把匹配的 OrigRead Process Text 项移到第一个位置。 */
private fun TextContextMenuData.prioritizeProcessTextItem(targetLabel: String): TextContextMenuData {
    val targetIndex =
        components.indexOfFirst { component ->
            component is TextContextMenuItem &&
                component.key is ProcessTextKey &&
                component.label == targetLabel
        }
    if (targetIndex <= 0) return this

    val target = components[targetIndex]
    val reordered =
        buildList(components.size) {
            add(target)
            components.forEachIndexed { index, component ->
                if (index != targetIndex) add(component)
            }
        }
    return TextContextMenuData(reordered)
}
