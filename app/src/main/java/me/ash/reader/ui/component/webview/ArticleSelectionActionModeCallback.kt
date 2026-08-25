package me.ash.reader.ui.component.webview

import android.content.Context
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import org.json.JSONTokener

/**
 * OrigRead 正文专用 WebView。
 *
 * Android WebView 没有公开 `setCustomSelectionActionModeCallback`；但 View 的 `startActionMode` 是公开扩展点。
 * 这里不替换 Chromium 的默认菜单逻辑，只在 ActionMode 创建时包装其 callback。
 */
internal class ArticleSelectionWebView(context: Context) : WebView(context) {
    private var selectionActionLabel: String? = null
    private var onSelectedTextAction: ((String) -> Unit)? = null

    /** 设置或清除当前正文的应用内选区动作；Standard edition 传 null 时完全保持 WebView 默认行为。 */
    fun configureSelectionAction(
        label: String?,
        onSelectedText: ((String) -> Unit)?,
    ) {
        selectionActionLabel = label
        onSelectedTextAction = onSelectedText
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(wrapSelectionCallback(callback))

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(wrapSelectionCallback(callback), type)

    /** 避免 WebView 的两个 startActionMode 重载互相转发时重复包装同一个 callback。 */
    private fun wrapSelectionCallback(callback: ActionMode.Callback?): ActionMode.Callback? {
        if (callback == null || callback is ArticleSelectionActionModeCallback) return callback
        val label = selectionActionLabel ?: return callback
        val onSelectedText = onSelectedTextAction ?: return callback
        return ArticleSelectionActionModeCallback(
            webView = this,
            label = label,
            onSelectedText = onSelectedText,
            delegate = callback,
        )
    }
}

/**
 * WebView 正文的应用内选区动作。
 *
 * WebView 的系统 `PROCESS_TEXT` 项通常被平台放到溢出页；这里在 OrigRead 自己的正文 ActionMode 中增加
 * 高优先级“AI”项，并直接读取当前 WebView selection。这样默认 WebView 阅读模式不再依赖系统决定
 * OrigRead 动作显示在第几页，同时仍只影响当前这个正文 WebView。
 */
private class ArticleSelectionActionModeCallback(
    private val webView: WebView,
    private val label: String,
    private val onSelectedText: (String) -> Unit,
    private val delegate: ActionMode.Callback,
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (!delegate.onCreateActionMode(mode, menu)) return false
        ensureAskAiItem(menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val delegateChanged = delegate.onPrepareActionMode(mode, menu)
        return ensureAskAiItem(menu) || delegateChanged
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != ASK_AI_MENU_ITEM_ID) {
            return delegate.onActionItemClicked(mode, item)
        }

        // evaluateJavascript 的回调晚于点击事件；先读取 selection，再结束 ActionMode，避免选区提前清空。
        webView.evaluateJavascript(READ_SELECTION_SCRIPT) { encodedSelection ->
            decodeJavascriptString(encodedSelection)?.let(onSelectedText)
            mode.finish()
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        delegate.onDestroyActionMode(mode)
    }

    /**
     * 删除 WebView 自动查询出的同名 PROCESS_TEXT 项，避免“AI”出现两次；随后以 order=0 和 ALWAYS
     * 加回应用内动作，使它成为正文浮动工具栏的第一优先项。
     */
    private fun ensureAskAiItem(menu: Menu): Boolean {
        var changed = false
        for (index in menu.size() - 1 downTo 0) {
            val item = menu.getItem(index)
            if (item.itemId == ASK_AI_MENU_ITEM_ID) continue
            val isOrigReadProcessText =
                item.intent?.action == Intent.ACTION_PROCESS_TEXT &&
                    item.intent?.component?.packageName == webView.context.packageName
            if (isOrigReadProcessText) {
                menu.removeItem(item.itemId)
                changed = true
            }
        }

        val existing = menu.findItem(ASK_AI_MENU_ITEM_ID)
        val askAiItem =
            existing
                ?: menu.add(
                    Menu.NONE,
                    ASK_AI_MENU_ITEM_ID,
                    /* order = */ 0,
                    label,
                ).also { changed = true }
        askAiItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return changed
    }
}

/** WebView evaluateJavascript 返回 JSON 编码字符串；这里只接受真实 String，拒绝 null/对象等异常结果。 */
private fun decodeJavascriptString(encoded: String?): String? {
    if (encoded.isNullOrBlank() || encoded == "null") return null
    val value = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
    return value?.trim()?.take(MAX_SELECTED_TEXT_CHARS)?.takeIf(String::isNotBlank)
}

private const val ASK_AI_MENU_ITEM_ID = 0x0A11_0001
private const val MAX_SELECTED_TEXT_CHARS = 20_000
private const val READ_SELECTION_SCRIPT =
    "(function(){var selection=window.getSelection();return selection?selection.toString():'';})()"
