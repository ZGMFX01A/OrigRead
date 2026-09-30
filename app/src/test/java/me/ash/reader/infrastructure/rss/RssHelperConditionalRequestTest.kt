package me.ash.reader.infrastructure.rss

import android.content.Context
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.content.ContentExtractionService
import me.ash.reader.infrastructure.content.DynamicArticleContentService
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class RssHelperConditionalRequestTest {
    @Test
    fun `valid RSS with a challenge example in a long prolog is never rejected by html hints`() = runBlocking {
        val server = MockWebServer()
        val prolog = "<!--<html><title>Just a moment...</title>" + " ".repeat(70_000) + "-->"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(prolog +
            "<rss version=\"2.0\"><channel><title>Valid RSS</title><link>https://example.com</link><description>News</description></channel></rss>"))
        server.start()
        try {
            assertEquals("Valid RSS", helper().parseFeedDirect(server.url("/feed").toString(), "").title)
        } finally { server.shutdown() }
    }
    @Test
    fun `challenge titles and standalone verification pages are classified without inline markers`() = runBlocking {
        val server = MockWebServer()
        val bodies = listOf(
            "<html><title>Just a moment...</title><body>Please wait</body></html>",
            "<html><title>Attention Required! | Cloudflare</title></html>",
            "<html><title>Verify you are human</title><script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script></html>",
        )
        bodies.forEach { server.enqueue(MockResponse().setResponseCode(403)
            .setHeader("Content-Type", "text/html").setBody(it)) }
        server.start()
        try {
            bodies.forEach {
                val error = runCatching { helper().parseFeedDirect(server.url("/feed").toString(), "") }
                    .exceptionOrNull() as FeedFetchException
                assertEquals(FeedFetchFailureReason.BLOCKED_RESPONSE, error.reason)
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `a normal page with a Turnstile widget is not reported as a challenge wall`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(
            "<html><title>News</title><script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script><body>Sign in</body></html>"))
        server.start()
        try {
            val error = runCatching { helper().parseFeedDirect(server.url("/feed").toString(), "") }
                .exceptionOrNull() as FeedFetchException
            assertEquals(FeedFetchFailureReason.HTML_RESPONSE, error.reason)
        } finally { server.shutdown() }
    }

    @Test
    fun `RSSHub refresh uses bounded request policy and preserves conditional validators`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(304))
        server.start()
        try {
            val timeouts = mutableListOf<Long>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                timeouts += chain.connectTimeoutMillis().toLong()
                timeouts += chain.readTimeoutMillis().toLong()
                timeouts += java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos())
                chain.proceed(chain.request())
            }.build()
            val feed = rssFeed(server.url("/zhihu/hot").toString())
            val result = helper(client).queryRssHubXmlConditional(feed, "", etag = "etag")
            assertTrue(result.notModified)
            assertEquals(listOf(3000L, 10000L, 11000L), timeouts)
            assertEquals("etag", server.takeRequest().getHeader("If-None-Match"))
        } finally { server.shutdown() }
    }

    @Test
    fun `ordinary RSS keeps its existing network policy`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(304))
        server.start()
        try {
            val observed = mutableListOf<Int>()
            val client = OkHttpClient.Builder().connectTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(17, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
                    observed += chain.connectTimeoutMillis()
                    observed += chain.readTimeoutMillis()
                    chain.proceed(chain.request())
                }.build()
            assertTrue(helper(client).queryRssXmlConditional(rssFeed(server.url("/feed").toString()), "").notModified)
            assertEquals(listOf(7000, 17000), observed)
        } finally { server.shutdown() }
    }

    @Test
    fun `Atom query output remains subscribable and refreshable when empty`() = runBlocking {
        val server = MockWebServer()
        val atom = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Atom News</title><id>urn:news</id><updated>2026-09-30T00:00:00Z</updated></feed>"""
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/atom+xml").setBody(atom)) }
        server.start()
        try {
            val feed = rssFeed(server.url("/zhihu/hot?format=atom").toString())
            val helper = helper()
            assertEquals("Atom News", helper.parseFeedDirect(feed.url, "").title)
            val result = helper.queryRssHubXmlConditional(feed, "")
            assertTrue(result.successful)
            assertTrue(result.articles.isEmpty())
            assertEquals(null, result.failure)
        } finally { server.shutdown() }
    }

    @Test
    fun `JSON and RSS3 outputs produce an unsupported format error instead of invalid XML`() = runBlocking {
        val server = MockWebServer()
        listOf("application/feed+json", "application/json", "text/plain").forEach { type ->
            server.enqueue(MockResponse().setHeader("Content-Type", type).setBody("""{"version":"https://jsonfeed.org/version/1.1","title":"News","items":[]}"""))
        }
        server.start()
        try {
            listOf("json", "rss3", "ums").forEach { format ->
                val failure = runCatching { helper().parseFeedDirect(server.url("/zhihu/hot?format=$format").toString(), "") }
                    .exceptionOrNull() as FeedFetchException
                assertEquals(FeedFetchFailureReason.UNSUPPORTED_FORMAT, failure.reason)
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `refresh exposes HTML and malformed XML failures rather than treating them as empty feeds`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Login</html>"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/xml").setBody("<rss><channel>"))
        server.start()
        try {
            org.mockito.Mockito.mockStatic(android.util.Log::class.java).use {
                listOf(FeedFetchFailureReason.HTML_RESPONSE, FeedFetchFailureReason.INVALID_CONTENT).forEach { reason ->
                    val result = helper().queryRssHubXmlConditional(rssFeed(server.url("/feed").toString()), "")
                    assertTrue(!result.successful)
                    assertEquals(reason, (result.failure as FeedFetchException).reason)
                }
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `cancellation closes a call while waiting for its response body`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<rss version=\"2.0\"><channel><title>News</title></channel></rss>")
            .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
        server.start()
        try {
            val headersReceived = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).also { headersReceived.complete(Unit) }
            }.build()
            val job = launch(Dispatchers.IO) {
                helper(client).parseFeedDirect(server.url("/feed").toString(), "")
            }
            withTimeout(2_000) { headersReceived.await() }
            delay(50)
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }

    @Test
    fun `valid RSS mislabeled as html remains subscribable`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(
            """<rss version="2.0"><channel><title>Valid RSS</title><link>https://example.com</link><description>News</description></channel></rss>"""
        ))
        server.start()
        try {
            assertEquals("Valid RSS", helper().parseFeedDirect(server.url("/feed").toString(), "").title)
        } finally { server.shutdown() }
    }

    @Test
    fun `plain maintenance and rate limit pages are not anti bot challenges`() = runBlocking {
        val server = MockWebServer()
        listOf(403, 429, 503).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html")
                .setBody("<html><title>Service unavailable</title><body>Maintenance</body></html>"))
        }
        server.start()
        try {
            listOf(403, 429, 503).forEach { code ->
                val error = runCatching { helper().parseFeedDirect(server.url("/feed").toString(), "") }
                    .exceptionOrNull() as FeedFetchException
                assertEquals(FeedFetchFailureReason.HTTP_ERROR, error.reason)
                assertEquals(code, error.statusCode)
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `cloudflare challenge header is recognized without body markers`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("cf-mitigated", "challenge")
            .setHeader("Content-Type", "text/html").setBody("<html>Please wait</html>"))
        server.start()
        try {
            val error = runCatching { helper().parseFeedDirect(server.url("/feed").toString(), "") }
                .exceptionOrNull() as FeedFetchException
            assertEquals(FeedFetchFailureReason.BLOCKED_RESPONSE, error.reason)
        } finally { server.shutdown() }
    }

    @Test
    fun `direct feed preserves http status when error body is truncated`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody("upstream temporarily unavailable")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.start()
        try {
            val result = runCatching {
                helper().parseFeedDirect(server.url("/rsshub-route").toString(), iconSourceUrl = "")
            }
            val error = result.exceptionOrNull() as FeedFetchException

            assertEquals(FeedFetchFailureReason.HTTP_ERROR, error.reason)
            assertEquals(502, error.statusCode)
            assertTrue(error.message.orEmpty().contains("HTTP 502"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `valid XML mislabeled as HTML survives UTF16 and a prolog longer than the sniff window`() = runBlocking {
        val server = MockWebServer()
        val feed = "<rss version=\"2.0\"><channel><title>News</title><link>https://example.com</link><description>News</description></channel></rss>"
        val utf16 = ("<?xml version=\"1.0\" encoding=\"UTF-16BE\"?>" + feed).toByteArray(Charset.forName("UTF-16BE"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(utf16)))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
            .setBody("<?xml version=\"1.0\"?><!--" + "x".repeat(70_000) + "-->" + feed))
        server.start()
        try {
            repeat(2) { assertEquals("News", helper().parseFeedDirect(server.url("/feed").toString(), "").title) }
        } finally { server.shutdown() }
    }

    @Test
    fun `connection failure after a large XML prefix is not reported as malformed XML`() = runBlocking {
        val server = MockWebServer()
        val xml = "<rss version=\"2.0\"><channel><title>News</title><link>https://example.com</link><description>" +
            "a".repeat(200_000) + "</description></channel></rss>"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/rss+xml").setBody(xml)
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.start()
        try {
            val error = runCatching { helper().parseFeedDirect(server.url("/feed").toString(), "") }.exceptionOrNull()
            assertTrue("Expected transport error, got $error", error is java.io.IOException && error !is FeedFetchException)
        } finally { server.shutdown() }
    }

    @Test
    fun `direct feed classifies cloudflare html instead of reporting invalid xml`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <!doctype html><html><head><title>Just a moment...</title></head>
                    <body><script src="/cdn-cgi/challenge-platform/test"></script>Cloudflare</body></html>
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val result = runCatching {
                helper().parseFeedDirect(server.url("/zhihu/hot").toString(), iconSourceUrl = "")
            }
            val error = result.exceptionOrNull() as FeedFetchException

            assertEquals(FeedFetchFailureReason.BLOCKED_RESPONSE, error.reason)
            assertEquals(403, error.statusCode)
            assertTrue(error.message.orEmpty().contains("anti-bot"))
            assertTrue(!error.message.orEmpty().contains("invalid XML", ignoreCase = true))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rss shaped html url still performs standard alternate autodiscovery`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.requestUrl?.encodedPath) {
                        "/feed" ->
                            MockResponse()
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody(
                                    """
                                    <html><head>
                                    <title>Feed landing page</title>
                                    <link rel="alternate" type="application/rss+xml" href="/actual.xml">
                                    </head><body>Not an RSS document</body></html>
                                    """.trimIndent()
                                )

                        "/actual.xml" ->
                            MockResponse()
                                .setHeader("Content-Type", "application/rss+xml")
                                .setBody(
                                    """
                                    <?xml version="1.0" encoding="UTF-8"?>
                                    <rss version="2.0"><channel>
                                    <title>Discovered Feed</title><link>https://example.com</link>
                                    <item><title>Article</title><link>https://example.com/article</link></item>
                                    </channel></rss>
                                    """.trimIndent()
                                )

                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        try {
            val inputUrl = server.url("/feed").toString()
            val expectedFeedUrl = server.url("/actual.xml").toString()
            val helper = helper()

            // Sanity-check the fixture itself before exercising page autodiscovery.
            assertEquals(
                "Discovered Feed",
                helper.parseFeedDirect(expectedFeedUrl, iconSourceUrl = inputUrl).title,
            )

            val result = runCatching { helper.discoverFeed(inputUrl) }
            val requestedPaths =
                buildList {
                    repeat(server.requestCount) {
                        add(server.takeRequest().requestUrl?.encodedPath.orEmpty())
                    }
                }
            assertTrue(
                "Expected alternate autodiscovery to succeed; requests=$requestedPaths error=${result.exceptionOrNull()}",
                result.isSuccess,
            )
            val discovered = result.getOrThrow()

            assertTrue(discovered.discoveredFromPage)
            assertEquals(expectedFeedUrl, discovered.feedUrl)
            assertTrue("Expected alternate feed request, requests=$requestedPaths", "/actual.xml" in requestedPaths)
            assertEquals("Discovered Feed", discovered.feed.title)
        } finally {
            server.shutdown()
        }
    }

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

    private fun helper(client: OkHttpClient = OkHttpClient()) =
        RssHelper(
            context = mock<Context>(),
            ioDispatcher = Dispatchers.Unconfined,
            okHttpClient = client,
            contentExtractionService = mock<ContentExtractionService>(),
            dynamicArticleContentService = mock<DynamicArticleContentService>(),
            articleWebSessionManager =
                mock<ArticleWebSessionManager> {
                    on { desktopHttpUserAgent } doReturn "Mozilla/5.0 OrigRead-Test"
                },
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
