package me.ash.reader.infrastructure.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JVM 单测无法覆盖 Android ICU/ART 的运行时差异。这里直接在设备/AVD 上初始化并执行摘要策略，
 * 防止元数据协议或跨语言长度正则再次造成 Android-only 类初始化/PatternSyntaxException。
 */
@RunWith(AndroidJUnit4::class)
class AiSummaryPolicyInstrumentedTest {
    @Test
    fun parsesV2MetadataOnAndroidRuntime() {
        val parsed =
            parseAiSummaryModelOutput(
                """<!-- origread-summary-v2: {"v":2,"form":"news","domain":"technology"} -->
                Android 运行时摘要正文""".trimIndent()
            )
        assertEquals(AiArticleForm.NEWS, parsed.articleForm)
        assertEquals("technology", parsed.domain)
        assertEquals("Android 运行时摘要正文", parsed.summary)

        val plain = parseAiSummaryModelOutput("普通摘要正文")
        assertNull(plain.articleForm)
        assertNull(plain.domain)
        assertEquals("普通摘要正文", plain.summary)
    }

    @Test
    fun measuresCrossLanguageLengthOnAndroidRuntime() {
        assertEquals(6, measureEffectiveLength("hello world 2026"))
        assertEquals(6, measureEffectiveLength("中文测试文本"))
        assertTrue(measureAiSummaryInput("## 方法\n\n- sample 120\n- control 60\n- treatment 60").effectiveLength > 0)
    }
}
