package me.ash.reader.infrastructure.rss

import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class RssHelperConditionalRequestTest {
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
            val feed =
                Feed(
                    id = "1\$feed",
                    name = "Large feed",
                    url = server.url("/feed.xml").toString(),
                    groupId = "1\$group",
                    accountId = 1,
                    sourceType = SourceType.RSS,
                )
            val helper =
                RssHelper(
                    context = mock<Context>(),
                    ioDispatcher = Dispatchers.Unconfined,
                    okHttpClient = OkHttpClient(),
                    contentExtractionService = mock<ContentExtractionService>(),
                    dynamicArticleContentService = mock<DynamicArticleContentService>(),
                    articleWebSessionManager = mock<ArticleWebSessionManager>(),
                )

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
}
