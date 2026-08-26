package me.ash.reader.infrastructure.rss

import android.content.Context
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.content.ContentExtractionService
import me.ash.reader.infrastructure.content.DynamicArticleContentService
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class RssHelperConditionalRequestTest {
    @Test
    fun `GBK RSS without HTTP charset follows XML declaration for discovery and refresh`() = runBlocking {
        val server = MockWebServer()
        val xml =
            """<?xml version="1.0" encoding="gbk"?>
                <rss version="2.0"><channel>
                <title>吾爱破解 - 52pojie.cn</title><link>https://www.52pojie.cn/forum.php</link>
                <item><guid>1</guid><title>中文测试主题</title>
                <link>https://www.52pojie.cn/thread-1-1-1.html</link>
                <pubDate>Tue, 25 Aug 2026 16:29:35 +0000</pubDate>
                <description><![CDATA[中文摘要]]></description></item>
                </channel></rss>""".trimIndent()
        val encoded = xml.toByteArray(Charset.forName("GB18030"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(Buffer().write(encoded))
        )
        server.start()
        try {
            val helper = helper()
            val feedUrl = server.url("/forum.php?mod=rss").toString()

            val discovered = helper.parseFeedDirect(feedUrl = feedUrl, iconSourceUrl = "")
            assertEquals("吾爱破解 - 52pojie.cn", discovered.title)
            assertEquals("中文测试主题", discovered.entries.single().title)

            val streamingFeed =
                parseSyndFeed(
                    inputStream = ByteArrayInputStream(encoded),
                    contentType = "application/xml",
                    preserveWireFeed = true,
                )
            assertEquals("吾爱破解 - 52pojie.cn", streamingFeed.title)
            assertEquals("中文测试主题", streamingFeed.entries.single().title)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `conditional RSS request sends validators and returns notModified on 304`() = runBlocking {
        val server = MockWebServer()
        val lastModified = "Tue, 18 Aug 2026 12:00:00 GMT"
        server.enqueue(
            MockResponse()
                .setResponseCode(304)
                .setHeader("ETag", "\"etag-v1\"")
                .setHeader("Last-Modified", lastModified)
        )
        server.start()
        try {
            val feed = rssFeed(server.url("/feed.xml").toString())
            val helper = helper()

            val result =
                helper.queryRssXmlConditional(
                    feed = feed,
                    latestLink = "",
                    preDate = Date(1_786_000_000_000L),
                    etag = "\"etag-v1\"",
                    lastModified = lastModified,
                )
            val request = server.takeRequest()

            assertEquals("\"etag-v1\"", request.getHeader("If-None-Match"))
            assertEquals(lastModified, request.getHeader("If-Modified-Since"))
            assertTrue(result.notModified)
            assertTrue(result.successful)
            assertTrue(result.articles.isEmpty())
            assertEquals("\"etag-v1\"", result.etag)
            assertEquals(lastModified, result.lastModified)
        } finally {
            server.shutdown()
        }
    }

    private fun helper() =
        RssHelper(
            context = mock<Context>(),
            ioDispatcher = Dispatchers.Unconfined,
            okHttpClient = OkHttpClient(),
            contentExtractionService = mock<ContentExtractionService>(),
            dynamicArticleContentService = mock<DynamicArticleContentService>(),
            articleWebSessionManager = mock<ArticleWebSessionManager>(),
        )

    private fun rssFeed(url: String) =
        Feed(
            id = "1\$feed",
            name = "Large feed",
            url = url,
            groupId = "1\$group",
            accountId = 1,
            sourceType = SourceType.RSS,
        )
}
