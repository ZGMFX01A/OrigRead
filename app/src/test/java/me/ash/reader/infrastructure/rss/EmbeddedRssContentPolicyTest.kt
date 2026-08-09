package me.ash.reader.infrastructure.rss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedRssContentPolicyTest {
    private val weChatArticle =
        "https://mp.weixin.qq.com/s?__biz=MzIyMDE5OTYyMw==&mid=2651051632&idx=1&sn=abc"

    @Test
    fun `wechat rss with substantial embedded article is treated as full content`() {
        val html =
            (1..8).joinToString(separator = "") { index ->
                "<p>第${index}段：这是由 RSS content:encoded 直接提供的公众号正文，" +
                    "包含足够完整的上下文、论述和文章内容，不需要再次访问微信原网页。</p>"
            }

        assertTrue(EmbeddedRssContentPolicy.shouldUseAsFullContent(weChatArticle, html))
    }

    @Test
    fun `wechat rss with short description still needs original full content`() {
        assertFalse(
            EmbeddedRssContentPolicy.shouldUseAsFullContent(
                weChatArticle,
                "<p>泡沫中不能说的秘密</p>",
            ),
        )
    }

    @Test
    fun `long normal rss article is not affected by wechat policy`() {
        val html = "<p>${"普通网站正文".repeat(200)}</p>"

        assertFalse(
            EmbeddedRssContentPolicy.shouldUseAsFullContent(
                "https://example.com/article/1",
                html,
            ),
        )
    }

    @Test
    fun `wechat verification page is not treated as article`() {
        val html = "<p>${"安全验证提示".repeat(100)}</p>"

        assertFalse(
            EmbeddedRssContentPolicy.shouldUseAsFullContent(
                "https://mp.weixin.qq.com/mp/wappoc_appmsgcaptcha?poc_token=token",
                html,
            ),
        )
    }
}
