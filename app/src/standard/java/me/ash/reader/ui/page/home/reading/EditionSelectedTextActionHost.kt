package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable

/** Standard edition 不注册“AI”文本选择动作。 */
@Composable
internal fun EditionSelectedTextActionHost(
    enabled: Boolean,
    onSelectedText: (String) -> Unit,
) {
    // Standard 版本刻意保持系统原生文本选择菜单，不增加 LLM action。
}
