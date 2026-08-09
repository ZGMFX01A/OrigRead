package me.ash.reader.ui.page.home.reading

import java.net.URI

internal fun isHttpUrl(url: String): Boolean = runCatching {
    URI(url).scheme?.lowercase() in setOf("http", "https")
}.getOrDefault(false)

internal fun isWeChatArticleUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.host.equals(WECHAT_HOST, ignoreCase = true) && uri.path == WECHAT_ARTICLE_PATH
}.getOrDefault(false)

internal fun isWeChatVerificationUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.host.equals(WECHAT_HOST, ignoreCase = true) &&
        uri.path.orEmpty().contains(WECHAT_CAPTCHA_PATH, ignoreCase = true)
}.getOrDefault(false)

internal const val MAX_CAPTURE_ATTEMPTS = 12
internal const val CAPTURE_RETRY_DELAY_MS = 500L
private const val MAX_CAPTURED_HTML_CHARS = 750_000
private const val WECHAT_HOST = "mp.weixin.qq.com"
private const val WECHAT_ARTICLE_PATH = "/s"
private const val WECHAT_CAPTCHA_PATH = "wappoc_appmsgcaptcha"

internal val WECHAT_CAPTURE_SCRIPT =
    """
    (function() {
        var content = document.getElementById("js_content");
        if (!content) return "";
        var text = (content.innerText || content.textContent || "").trim();
        if (!text) return "";
        var html = content.outerHTML || "";
        return html.length > $MAX_CAPTURED_HTML_CHARS
            ? html.substring(0, $MAX_CAPTURED_HTML_CHARS)
            : html;
    })();
    """.trimIndent()

internal val DOM_CAPTURE_SCRIPT =
    """
    (function() {
        var root = document.documentElement;
        if (!root) return "";
        var html = root.outerHTML || "";
        return html.length > $MAX_CAPTURED_HTML_CHARS
            ? html.substring(0, $MAX_CAPTURED_HTML_CHARS)
            : html;
    })();
    """.trimIndent()
