package me.ash.reader.llm.search

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.ai.AiHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSecurityAndNullTest {
    @Test
    fun `search providers ignore json null values instead of emitting literal null`() {
        val tavily =
            parseTavilySearchResponse(
                WebSearchProviderProfile(id = "tavily", kind = WebSearchProviderKind.TAVILY),
                """{"answer":null,"results":[{"title":null,"url":"https://example.com/t","content":null,"raw_content":null,"published_date":null}]}""",
            )
        assertNull(tavily.answer)
        assertEquals("https://example.com/t", tavily.results.single().title)
        assertEquals("", tavily.results.single().snippet)
        assertNull(tavily.results.single().content)
        assertNull(tavily.results.single().publishedAt)

        val bocha =
            parseBochaSearchResponse(
                WebSearchProviderProfile(id = "bocha", kind = WebSearchProviderKind.BOCHA),
                """{"code":200,"data":{"webPages":{"value":[{"name":null,"url":"https://example.com/b","snippet":null,"summary":null,"siteName":null,"datePublished":null}]}}}""",
            )
        assertEquals("https://example.com/b", bocha.results.single().title)
        assertEquals("", bocha.results.single().snippet)
        assertEquals("example.com", bocha.results.single().source)
        assertNull(bocha.results.single().publishedAt)
        assertNull(bocha.results.single().content)

        val brave =
            parseBraveSearchResponse(
                WebSearchProviderProfile(id = "brave", kind = WebSearchProviderKind.BRAVE),
                """{"web":{"results":[{"title":null,"url":"https://example.com/br","description":null,"extra_snippets":[null,"ok"],"page_age":null}]}}""",
            )
        assertEquals("https://example.com/br", brave.results.single().title)
        assertEquals("ok", brave.results.single().snippet)
        assertNull(brave.results.single().publishedAt)

        val exa =
            parseExaSearchResponse(
                WebSearchProviderProfile(id = "exa", kind = WebSearchProviderKind.EXA),
                """{"results":[{"title":null,"url":"https://example.com/e","highlights":[null],"text":null,"publishedDate":null}]}""",
            )
        assertEquals("https://example.com/e", exa.results.single().title)
        assertEquals("", exa.results.single().snippet)
        assertNull(exa.results.single().publishedAt)
        assertNull(exa.results.single().content)

        val firecrawl =
            parseFirecrawlSearchResponse(
                WebSearchProviderProfile(id = "firecrawl", kind = WebSearchProviderKind.FIRECRAWL),
                """{"data":{"web":[{"title":null,"url":"https://example.com/f","description":null,"markdown":null}]}}""",
            )
        assertEquals("https://example.com/f", firecrawl.results.single().title)
        assertEquals("", firecrawl.results.single().snippet)
        assertNull(firecrawl.results.single().content)

        val keenable =
            parseKeenableSearchResponse(
                WebSearchProviderProfile(id = "keenable", kind = WebSearchProviderKind.KEENABLE),
                """{"results":[{"title":null,"url":"https://example.com/k","snippet":null,"description":null,"published_at":null}]}""",
            )
        assertEquals("https://example.com/k", keenable.results.single().title)
        assertEquals("", keenable.results.single().snippet)
        assertNull(keenable.results.single().publishedAt)

        val linkup =
            parseLinkupSearchResponse(
                WebSearchProviderProfile(id = "linkup", kind = WebSearchProviderKind.LINKUP),
                """{"results":[{"name":null,"url":"https://example.com/l","content":null}]}""",
            )
        assertEquals("https://example.com/l", linkup.results.single().title)
        assertEquals("", linkup.results.single().snippet)

        val perplexity =
            parsePerplexitySearchResponse(
                WebSearchProviderProfile(id = "pplx", kind = WebSearchProviderKind.PERPLEXITY),
                """{"results":[{"title":null,"url":"https://example.com/p","snippet":null,"date":null}]}""",
            )
        assertEquals("https://example.com/p", perplexity.results.single().title)
        assertEquals("", perplexity.results.single().snippet)
        assertNull(perplexity.results.single().publishedAt)

        val searxng =
            parseSearxngSearchResponse(
                WebSearchProviderProfile(id = "searxng", kind = WebSearchProviderKind.SEARXNG),
                """{"results":[{"title":null,"url":"https://example.com/s","content":null,"publishedDate":null}]}""",
            )
        assertEquals("https://example.com/s", searxng.results.single().title)
        assertEquals("", searxng.results.single().snippet)
        assertNull(searxng.results.single().publishedAt)

        val zhipu =
            parseZhipuSearchResponse(
                WebSearchProviderProfile(id = "zhipu", kind = WebSearchProviderKind.ZHIPU),
                """{"search_result":[{"title":null,"link":"https://example.com/z","content":null,"media":null,"publish_date":null}]}""",
                includeContent = true,
            )
        assertEquals("https://example.com/z", zhipu.results.single().title)
        assertEquals("", zhipu.results.single().snippet)
        assertEquals("example.com", zhipu.results.single().source)
        assertNull(zhipu.results.single().publishedAt)
        assertNull(zhipu.results.single().content)
    }

    @Test
    fun `web search rejects 302 credential redirect and oversized payloads`() = runBlocking {
        val searchServer = MockWebServer()
        val evilServer = MockWebServer()
        searchServer.start()
        evilServer.start()
        try {
            evilServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))
            searchServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", evilServer.url("/leak").toString())
            )
            val tavily = TavilyWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            val failure =
                runCatching {
                    tavily.search(
                        profile =
                            WebSearchProviderProfile(
                                id = "tavily",
                                kind = WebSearchProviderKind.TAVILY,
                                endpoint = searchServer.url("/search").toString(),
                            ),
                        apiKey = "secret-key",
                        request = WebSearchRequest(query = "test"),
                    )
                }.exceptionOrNull()
            assertTrue(failure is WebSearchException)
            assertEquals(0, evilServer.requestCount)

            val oversized = "a".repeat((MAX_WEB_SEARCH_RESPONSE_BYTES + 1024).toInt())
            searchServer.enqueue(MockResponse().setResponseCode(200).setBody(oversized))
            val tooLargeFailure =
                runCatching {
                    tavily.search(
                        profile =
                            WebSearchProviderProfile(
                                id = "tavily",
                                kind = WebSearchProviderKind.TAVILY,
                                endpoint = searchServer.url("/search").toString(),
                            ),
                        apiKey = "secret-key",
                        request = WebSearchRequest(query = "test"),
                    )
                }.exceptionOrNull()
            assertTrue(tooLargeFailure is IOException)
        } finally {
            searchServer.shutdown()
            evilServer.shutdown()
        }
    }
}
