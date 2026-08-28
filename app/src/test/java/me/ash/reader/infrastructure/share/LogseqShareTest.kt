package me.ash.reader.infrastructure.share

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogseqShareTest {
    @Test
    fun `quick capture uri preserves chinese reserved characters and newlines`() {
        val title = "AI & Android #1"
        val url = "https://example.com/a?x=1&y=中文#section"
        val markdown = "# 标题\n\n- 第一行 & 第二行\n- #标签 😀"

        val uri = LogseqShare.buildQuickCaptureUri(title, url, markdown)
        val params = decodeQuery(uri)

        assertTrue(uri.startsWith("logseq://x-callback-url/quickCapture?"))
        assertEquals(title, params["title"])
        assertEquals(url, params["url"])
        assertEquals(markdown, params["content"])
    }

    @Test
    fun `quick capture uri keeps long markdown intact`() {
        val markdown = buildString {
            repeat(4_000) { index -> append("第$index 行：AI & Kotlin #tag\n") }
        }

        val uri = LogseqShare.buildQuickCaptureUri("长文", "https://example.com", markdown)

        assertEquals(markdown, decodeQuery(uri)["content"])
    }

    /** 将测试 URI 的查询参数还原，验证编码后的参数边界与原始内容完全一致。 */
    private fun decodeQuery(uri: String): Map<String, String> =
        URI(uri).rawQuery
            .split('&')
            .associate { pair ->
                val parts = pair.split('=', limit = 2)
                decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}
