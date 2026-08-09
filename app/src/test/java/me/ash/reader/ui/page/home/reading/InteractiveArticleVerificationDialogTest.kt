package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveArticleVerificationDialogTest {
    @Test
    fun `wechat article url is recognized`() {
        assertTrue(
            isWeChatArticleUrl(
                "https://mp.weixin.qq.com/s?__biz=MjM5ODI5Njc2MA==&mid=2655942318&idx=1&sn=abc",
            ),
        )
        assertFalse(
            isWeChatArticleUrl(
                "https://mp.weixin.qq.com/mp/wappoc_appmsgcaptcha?poc_token=token",
            ),
        )
    }

    @Test
    fun `wechat poc captcha url is recognized as verification page`() {
        assertTrue(
            isWeChatVerificationUrl(
                "https://mp.weixin.qq.com/mp/wappoc_appmsgcaptcha" +
                    "?poc_token=token&target_url=https%3A%2F%2Fmp.weixin.qq.com%2Fs%3F__biz%3Ddemo",
            ),
        )
        assertFalse(
            isWeChatVerificationUrl(
                "https://mp.weixin.qq.com/s?__biz=demo&mid=1&idx=1",
            ),
        )
    }
}
