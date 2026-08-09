package me.ash.reader.infrastructure.website

import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleLinkHeuristicsTest {
    @Test
    fun `rejects navigation routes and search queries`() {
        assertRejected("作者编辑主页", "https://news.example.com/author/editor-1001")
        assertRejected("Android 标签页", "https://news.example.com/tag/android-1001")
        assertRejected("搜索相关内容", "https://news.example.com/search?q=origread")
        assertRejected("用户注册入口", "https://news.example.com/register")
        assertRejected("hide", "https://news.example.com/hide?id=1001&goto=news")
        assertRejected("example.com", "https://news.example.com/from?site=example.com")
        assertRejected("AI4Science", "https://news.example.com/news/column?columnId=35")
        assertRejected("示例用户", "https://news.example.com/u/1001")
    }

    @Test
    fun `keeps article urls even when title contains navigation words`() {
        assertAccepted("搜索技术升级带来的新变化", "https://news.example.com/article/1001")
        assertAccepted("分类算法如何改善新闻推荐", "https://news.example.com/post?id=1002&category=tech")
        assertAccepted("真实文章标题", "https://news.example.com/archives/1786073720538.html")
        assertAccepted("另一篇真实文章", "https://news.example.com/archive/long-article-slug-1002")
    }

    @Test
    fun `rejects archive listing roots without rejecting archive article permalinks`() {
        assertRejected("历史归档", "https://news.example.com/archives/")
        assertRejected("更多历史文章", "https://news.example.com/archives/page/2")
    }

    private fun assertRejected(title: String, url: String) {
        val element = Jsoup.parse("<a href=\"$url\">$title</a>", "https://news.example.com/").selectFirst("a")!!
        assertTrue(ArticleLinkHeuristics.shouldReject(element, title, url))
    }

    private fun assertAccepted(title: String, url: String) {
        val element = Jsoup.parse("<a class=\"title\" href=\"$url\">$title</a>", "https://news.example.com/").selectFirst("a")!!
        assertFalse(ArticleLinkHeuristics.shouldReject(element, title, url))
    }
}
