package me.ash.reader.infrastructure.website

import android.content.Context
import java.nio.file.Files
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WebsiteHelperAutomaticRuleCacheTest {
    private val tempDir = Files.createTempDirectory("website-helper-cache-test").toFile()
    private lateinit var server: MockWebServer
    private lateinit var preferenceRepository: WebsiteParsePreferenceRepository
    private lateinit var dynamicHtmlRenderer: DynamicWebsiteHtmlRenderer
    private lateinit var helper: WebsiteHelper
    private lateinit var feed: Feed

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir)
        preferenceRepository = WebsiteParsePreferenceRepository(context)
        dynamicHtmlRenderer = mock()
        helper = WebsiteHelper(
            okHttpClient = OkHttpClient(),
            ruleRepository = WebsiteRuleRepository(context),
            preferenceRepository = preferenceRepository,
            dynamicHtmlRenderer = dynamicHtmlRenderer,
            ioDispatcher = Dispatchers.IO,
        )
        feed = Feed(
            id = "website-feed",
            name = "Automatic website",
            url = server.url("/").toString(),
            groupId = "group-1",
            accountId = 1,
            sourceType = SourceType.WEBSITE,
        )
    }

    @Test
    fun `static website inspection returns parsed entries for unified source scoring`() = runBlocking {
        server.enqueue(htmlResponse(sample("website-samples/url-clusters.html")))

        val inspected = helper.inspect(feed.url, FETCHED_AT)

        assertEquals(5, inspected.entries.size)
        assertEquals(5, inspected.entries.mapNotNull { it.link }.distinct().size)
        assertNotNull(inspected.entries.first().publishedDate)
    }

    @Test
    fun `dynamic website preference uses rendered dom without static network request`() = runBlocking {
        val renderedHtml = sample("website-samples/url-clusters.html")
        whenever(dynamicHtmlRenderer.render(feed.url)).thenReturn(
            DynamicWebsiteRenderResult(
                finalUrl = feed.url,
                html = renderedHtml,
            )
        )
        preferenceRepository.setDynamicRenderingEnabled(feed.id, true)

        val articles = helper.fetchArticles(feed, FETCHED_AT)

        assertEquals(5, articles.size)
        assertEquals(0, server.requestCount)
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `reuses cached rule and reanalyzes after selector invalidation`() = runBlocking {
        server.enqueue(htmlResponse(sample("website-samples/url-clusters.html")))
        val firstArticles = helper.fetchArticles(feed, FETCHED_AT)
        val firstPreference = preferenceRepository.get(feed.id)
        val firstRule = firstPreference?.cachedAutomaticRule

        assertEquals(5, firstArticles.size)
        assertNotNull(firstRule)

        server.enqueue(htmlResponse(sample("website-samples/url-clusters.html").replace("100", "900")))
        val secondArticles = helper.fetchArticles(feed, FETCHED_AT)
        val secondPreference = preferenceRepository.get(feed.id)

        assertEquals(5, secondArticles.size)
        assertEquals(firstRule?.id, secondPreference?.cachedAutomaticRule?.id)
        assertEquals(firstPreference?.automaticRuleUpdatedAt, secondPreference?.automaticRuleUpdatedAt)

        server.enqueue(htmlResponse(changedStructureHtml()))
        val thirdArticles = helper.fetchArticles(feed, FETCHED_AT)
        val replacementRule = preferenceRepository.get(feed.id)?.cachedAutomaticRule

        assertEquals(4, thirdArticles.size)
        assertNotNull(replacementRule)
        assertNotEquals(firstRule?.id, replacementRule?.id)
        assertEquals("localhost/posts/{year}/{month}/{day}/{token}.html", replacementRule?.automaticUrlPattern)
    }

    @Test
    fun `periodically rescans stable cached rule and accumulates history score`() = runBlocking {
        val html = sample("website-samples/url-clusters.html")
        repeat(7) { server.enqueue(htmlResponse(html.replace("100", "${it + 1}00"))) }

        repeat(7) {
            val articles = helper.fetchArticles(feed, FETCHED_AT)
            assertEquals(5, articles.size)
        }

        val preference = preferenceRepository.get(feed.id)
        val cachedRuleId = preference?.cachedAutomaticRule?.id
        val history = preference?.automaticRuleHistory?.first { it.ruleId == cachedRuleId }

        assertNotNull(cachedRuleId)
        assertEquals(2, preference?.automaticFullScanCount)
        assertEquals(0, preference?.automaticReuseSinceFullScan)
        assertEquals(7, preference?.automaticSelectionStreak)
        assertEquals(2, history?.fullScanAppearances)
        assertEquals(7, history?.successfulSelections)
        assertEquals(12, AutomaticRuleStabilityScorer.score(preference, cachedRuleId.orEmpty()))
    }

    private fun htmlResponse(body: String) =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .setBody(body)

    private fun sample(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "Missing test resource: $path" }
            .readText()

    private fun changedStructureHtml(): String = """
        <!doctype html>
        <html><body><main><div class="stream">
          <div class="entry"><h3><a class="headline" href="/posts/2026/08/05/cache-rebuilt-2001.html">缓存失效后重新识别文章一</a></h3></div>
          <div class="entry"><h3><a class="headline" href="/posts/2026/08/05/cache-rebuilt-2002.html">缓存失效后重新识别文章二</a></h3></div>
          <div class="entry"><h3><a class="headline" href="/posts/2026/08/05/cache-rebuilt-2003.html">缓存失效后重新识别文章三</a></h3></div>
          <div class="entry"><h3><a class="headline" href="/posts/2026/08/05/cache-rebuilt-2004.html">缓存失效后重新识别文章四</a></h3></div>
        </div></main></body></html>
    """.trimIndent()

    private companion object {
        val FETCHED_AT = Date(1_786_000_000_000L)
    }
}
