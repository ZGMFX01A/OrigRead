package me.ash.reader.ui.component.webview

/**
 * WebView 正文完整渲染所依赖的稳定输入。
 *
 * AI 摘要进度、流式文本等阅读页外围状态不属于正文渲染输入；这些状态变化时必须保留现有
 * WebView 页面，避免重复 loadDataWithBaseURL 导致正文闪烁、图片重复请求和滚动位置抖动。
 */
internal data class WebViewRenderSpec(
    val content: String,
    val fontSize: Int,
    val fontPath: String?,
    val lineHeight: Float,
    val letterSpacing: Float,
    val textMargin: Int,
    val textColor: Int,
    val textBold: Boolean,
    val textAlign: String,
    val boldTextColor: Int,
    val subheadBold: Boolean,
    val subheadUpperCase: Boolean,
    val imgMargin: Int,
    val imgBorderRadius: Int,
    val linkTextColor: Int,
    val codeTextColor: Int,
    val codeBgColor: Int,
    val selectionTextColor: Int,
    val selectionBgColor: Int,
    val boldCharacters: Boolean,
)

/** 仅当正文或正文样式真实变化时允许重载 WebView。 */
internal class WebViewRenderGuard {
    private var lastSpec: WebViewRenderSpec? = null

    /**
     * 返回 true 表示调用方应重新生成并加载 HTML。
     * 新 AndroidView factory 建立实体时必须 reset，保证新 WebView 一定完成首次加载。
     */
    fun shouldReload(spec: WebViewRenderSpec): Boolean {
        if (lastSpec == spec) return false
        lastSpec = spec
        return true
    }

    fun reset() {
        lastSpec = null
    }
}
