package me.ash.reader.domain.model.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RssReadingModePolicyTest {
    @Test
    fun `RSS来源会清理历史全文解析和浏览器打开状态`() {
        val feed = feed(SourceType.RSS, fullContent = true, browser = true)

        val normalized = feed.normalizeRssReadingMode()

        assertFalse(normalized.isFullContent)
        assertFalse(normalized.isBrowser)
    }

    @Test
    fun `已正确的RSS来源不产生多余副本`() {
        val feed = feed(SourceType.RSS, fullContent = false, browser = false)

        assertSame(feed, feed.normalizeRssReadingMode())
    }

    @Test
    fun `Website来源保留用户阅读方式配置`() {
        val feed = feed(SourceType.WEBSITE, fullContent = true, browser = true)

        val normalized = feed.normalizeRssReadingMode()

        assertTrue(normalized.isFullContent)
        assertTrue(normalized.isBrowser)
    }

    private fun feed(sourceType: SourceType, fullContent: Boolean, browser: Boolean) =
        Feed(
            id = "feed",
            name = "Feed",
            url = "https://example.com/feed.xml",
            groupId = "group",
            accountId = 1,
            isFullContent = fullContent,
            isBrowser = browser,
            sourceType = sourceType,
        )
}
