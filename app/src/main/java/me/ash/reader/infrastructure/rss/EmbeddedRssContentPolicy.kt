package me.ash.reader.infrastructure.rss

import java.net.URI
import org.jsoup.Jsoup

/**
 * 判断 RSS 条目本身是否已经携带足够完整的正文，可直接作为阅读器全文使用。
 *
 * 微信公众号的第三方 RSS（例如 Wechat2RSS）通常已经在 content:encoded 中提供完整文章；
 * 此时再次访问 mp.weixin.qq.com 只会额外触发风控/验证码，没有正文收益。
 */
internal object EmbeddedRssContentPolicy {
    private const val WECHAT_HOST = "mp.weixin.qq.com"
    private const val MIN_VISIBLE_TEXT_LENGTH = 600
    private const val MIN_COMPACT_VISIBLE_TEXT_LENGTH = 250
    private const val MIN_CONTENT_BLOCKS = 4

    fun shouldUseAsFullContent(link: String, html: String): Boolean {
        if (!isWeChatArticle(link) || html.isBlank()) return false

        val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return false
        val body = document.body()
        val visibleTextLength = body.text().trim().length
        if (visibleTextLength >= MIN_VISIBLE_TEXT_LENGTH) return true

        // 兼容篇幅较短但结构明显完整的文章，避免仅按字数把短文误判成摘要。
        val contentBlocks =
            body.select("p, blockquote, li, h1, h2, h3")
                .count { it.text().trim().length >= 12 }
        return visibleTextLength >= MIN_COMPACT_VISIBLE_TEXT_LENGTH &&
            contentBlocks >= MIN_CONTENT_BLOCKS
    }

    private fun isWeChatArticle(link: String): Boolean =
        runCatching {
            val uri = URI(link)
            val host = uri.host.orEmpty().lowercase()
            val path = uri.path.orEmpty()
            (host == WECHAT_HOST || host.endsWith(".$WECHAT_HOST")) && path == "/s"
        }.getOrDefault(false)
}
