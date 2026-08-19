package me.ash.reader.infrastructure.rsshub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RssHubLocationTest {
    @Test
    fun `内置实例地区使用中立代码并按界面语言显示`() {
        val official = RssHubSettingsRepository.defaultInstances().first { it.id == "official" }

        assertEquals("US", official.location)
        assertEquals("🇺🇸 美国", RssHubLocation.display(official.location, "zh"))
        assertEquals("US United States", RssHubLocation.display(official.location, "en"))
        assertFalse(RssHubLocation.display(official.location, "en").contains("美国"))
    }

    @Test
    fun `旧版中文地区值会自动规范化`() {
        assertEquals("US", RssHubLocation.canonical("official", "🇺🇸 美国"))
        assertEquals("AE", RssHubLocation.canonical("", "🇦🇪 阿联酋"))
        assertEquals("GLOBAL", RssHubLocation.canonical("virworks", "🇺🇳 多地负载均衡"))
    }
}
