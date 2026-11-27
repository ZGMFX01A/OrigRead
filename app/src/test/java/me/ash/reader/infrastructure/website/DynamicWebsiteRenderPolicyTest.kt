package me.ash.reader.infrastructure.website

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicWebsiteRenderPolicyTest {
    @Test
    fun `js content cropping is limited to Wechat domain boundaries`() {
        listOf("https://mp.weixin.qq.com/s?id=1", "https://weixin.qq.com/", "https://MP.WEIXIN.QQ.COM/s")
            .forEach {
                assertTrue(it, DynamicWebsiteRenderPolicy.allowsWechatContentCrop(it))
                assertTrue(DynamicWebsiteRenderPolicy.captureDomScript(it, 750000).contains("var wechatContent = document.querySelector('#js_content');"))
            }
        listOf("https://example.com/js_content", "https://weixin.qq.com.evil.test/", "https://evilweixin.qq.com/", "file://weixin.qq.com/a", "invalid")
            .forEach {
                assertFalse(it, DynamicWebsiteRenderPolicy.allowsWechatContentCrop(it))
                val script = DynamicWebsiteRenderPolicy.captureDomScript(it, 750000)
                assertTrue(script.contains("var wechatContent = null;"))
                assertFalse(script.contains("querySelector('#js_content')"))
            }
    }

    @Test
    fun `allows only http same site navigation`() {
        val initial = "https://news.example.com/list"

        assertTrue(DynamicWebsiteRenderPolicy.isAllowedNavigation(initial, "https://www.news.example.com/latest"))
        assertTrue(DynamicWebsiteRenderPolicy.isAllowedNavigation(initial, "https://example.com/home"))
        assertFalse(DynamicWebsiteRenderPolicy.isAllowedNavigation(initial, "https://example.org/home"))
        assertFalse(DynamicWebsiteRenderPolicy.isAllowedNavigation(initial, "javascript:alert(1)"))
        assertFalse(DynamicWebsiteRenderPolicy.isAllowedNavigation(initial, "file:///tmp/page.html"))
    }

    @Test
    fun `decodes escaped evaluate javascript result`() {
        val html = "<html><body>动态内容\n\"标题\"</body></html>"

        assertEquals(
            html,
            DynamicWebsiteRenderPolicy.decodeJavascriptString(Json.encodeToString(html)),
        )
    }

    @Test
    fun `wechat captcha url requires interactive verification`() {
        assertTrue(
            DynamicWebsiteRenderPolicy.requiresInteractiveVerification(
                "https://mp.weixin.qq.com/mp/wappoc_appmsgcaptcha?poc_token=token",
            ),
        )
        assertFalse(
            DynamicWebsiteRenderPolicy.requiresInteractiveVerification(
                "https://mp.weixin.qq.com/s?__biz=test&mid=1&idx=1",
            ),
        )
    }
}
