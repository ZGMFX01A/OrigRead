package me.ash.reader.llm.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.llm.runtime.LlmContextType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchFoundationTest {
    @Test
    fun `exa response normalizes search metadata and content`() {
        val profile = WebSearchProviderProfile(id = "exa-1", kind = WebSearchProviderKind.EXA)
        val response =
            parseExaSearchResponse(
                profile,
                """
                {
                  "results": [{
                    "title": "Example result",
                    "url": "https://example.com/post",
                    "publishedDate": "2026-08-24T10:00:00Z",
                    "highlights": ["first highlight", "second highlight"],
                    "text": "full text"
                  }]
                }
                """.trimIndent(),
            )

        assertEquals("exa-1", response.providerId)
        assertEquals(WebSearchBackendKind.RAW_SEARCH, response.backendKind)
        assertEquals(1, response.results.size)
        assertEquals("Example result", response.results.single().title)
        assertEquals("example.com", response.results.single().source)
        assertTrue(response.results.single().snippet.contains("first highlight"))
        assertEquals("full text", response.results.single().content)
    }

    @Test
    fun `tavily response normalizes snippet raw content and answer`() {
        val profile = WebSearchProviderProfile(id = "tavily-1", kind = WebSearchProviderKind.TAVILY)
        val response =
            parseTavilySearchResponse(
                profile,
                """
                {
                  "answer": "optional answer",
                  "results": [{
                    "title": "Tavily result",
                    "url": "https://example.org/news",
                    "content": "search snippet",
                    "raw_content": "raw page content"
                  }]
                }
                """.trimIndent(),
            )

        assertEquals("optional answer", response.answer)
        assertEquals("search snippet", response.results.single().snippet)
        assertEquals("raw page content", response.results.single().content)
        assertEquals("example.org", response.results.single().source)
    }

    @Test
    fun `exa adapter sends api key and normalized request body`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"results":[]}""")
            )
            val provider = ExaWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            provider.search(
                profile =
                    WebSearchProviderProfile(
                        id = "exa-test",
                        kind = WebSearchProviderKind.EXA,
                        endpoint = server.url("/search").toString(),
                    ),
                apiKey = "exa-secret",
                request = WebSearchRequest(query = "latest android news", maxResults = 7),
            )

            val recorded = server.takeRequest()
            assertEquals("exa-secret", recorded.getHeader("x-api-key"))
            val json = JSONObject(recorded.body.readUtf8())
            assertEquals("latest android news", json.getString("query"))
            assertEquals(7, json.getInt("numResults"))
            assertTrue(json.getJSONObject("contents").getBoolean("highlights"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tavily adapter sends bearer key and normalized request body`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"results":[]}""")
            )
            val provider = TavilyWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            provider.search(
                profile =
                    WebSearchProviderProfile(
                        id = "tavily-test",
                        kind = WebSearchProviderKind.TAVILY,
                        endpoint = server.url("/search").toString(),
                    ),
                apiKey = "tvly-secret",
                request =
                    WebSearchRequest(
                        query = "current java release",
                        maxResults = 4,
                        includeContent = true,
                    ),
            )

            val recorded = server.takeRequest()
            assertEquals("Bearer tvly-secret", recorded.getHeader("Authorization"))
            val json = JSONObject(recorded.body.readUtf8())
            assertEquals("current java release", json.getString("query"))
            assertEquals(4, json.getInt("max_results"))
            assertTrue(json.getBoolean("include_raw_content"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `brave adapter sends subscription token and query parameters`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"web":{"results":[]}}""")
            )
            val provider = BraveWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            provider.search(
                profile =
                    WebSearchProviderProfile(
                        id = "brave-test",
                        kind = WebSearchProviderKind.BRAVE,
                        endpoint = server.url("/search").toString(),
                    ),
                apiKey = "brave-secret",
                request = WebSearchRequest(query = "android compose", maxResults = 6),
            )

            val recorded = server.takeRequest()
            assertEquals("brave-secret", recorded.getHeader("X-Subscription-Token"))
            assertEquals("android compose", recorded.requestUrl?.queryParameter("q"))
            assertEquals("6", recorded.requestUrl?.queryParameter("count"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `perplexity adapter sends bearer key and raw search request`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"results":[]}""")
            )
            val provider = PerplexityWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            provider.search(
                profile =
                    WebSearchProviderProfile(
                        id = "pplx-test",
                        kind = WebSearchProviderKind.PERPLEXITY,
                        endpoint = server.url("/search").toString(),
                    ),
                apiKey = "pplx-secret",
                request = WebSearchRequest(query = "current android version", maxResults = 5),
            )

            val recorded = server.takeRequest()
            assertEquals("Bearer pplx-secret", recorded.getHeader("Authorization"))
            val json = JSONObject(recorded.body.readUtf8())
            assertEquals("current android version", json.getString("query"))
            assertEquals(5, json.getInt("max_results"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `linkup response normalizes sourced search results`() {
        val profile = WebSearchProviderProfile(id = "linkup", kind = WebSearchProviderKind.LINKUP)
        val response =
            parseLinkupSearchResponse(
                profile,
                """
                {
                  "results": [{
                    "name": "Official report",
                    "url": "https://example.net/report",
                    "content": "reported evidence",
                    "type": "text"
                  }]
                }
                """.trimIndent(),
            )

        assertEquals("Official report", response.results.single().title)
        assertEquals("reported evidence", response.results.single().snippet)
        assertEquals("example.net", response.results.single().source)
    }

    @Test
    fun `firecrawl response keeps optional markdown content`() {
        val profile = WebSearchProviderProfile(id = "firecrawl", kind = WebSearchProviderKind.FIRECRAWL)
        val response =
            parseFirecrawlSearchResponse(
                profile,
                """
                {
                  "success": true,
                  "data": {
                    "web": [{
                      "title": "Search result",
                      "description": "short description",
                      "url": "https://example.dev/page",
                      "markdown": "# Full page"
                    }]
                  }
                }
                """.trimIndent(),
            )

        assertEquals("short description", response.results.single().snippet)
        assertEquals("# Full page", response.results.single().content)
    }

    @Test
    fun `keenable keyless adapter uses app title and normalized request body`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"query":"android","results":[]}""")
            )
            val provider = KeenableWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            provider.search(
                profile =
                    WebSearchProviderProfile(
                        id = "keenable-public",
                        kind = WebSearchProviderKind.KEENABLE,
                        endpoint = server.url("/v1/search/public").toString(),
                    ),
                apiKey = "",
                request = WebSearchRequest(query = "android web search", maxResults = 4),
            )

            val recorded = server.takeRequest()
            assertEquals("OrigRead", recorded.getHeader("X-Keenable-Title"))
            assertEquals(null, recorded.getHeader("X-API-Key"))
            val json = JSONObject(recorded.body.readUtf8())
            assertEquals("android web search", json.getString("query"))
            assertEquals(4, json.getInt("max_results"))
            assertFalse(WebSearchProviderKind.KEENABLE.requiresApiKey)
            assertTrue(WebSearchProviderKind.KEENABLE.supportsApiKey)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `keenable keyed adapter sends api key and normalizes response`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "query": "android",
                          "results": [{
                            "title": "Keenable result",
                            "url": "https://example.com/keenable",
                            "description": "short description",
                            "snippet": "longer search snippet",
                            "published_at": "2026-08-27T10:00:00Z"
                          }]
                        }
                        """.trimIndent()
                    )
            )
            val provider = KeenableWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            val response =
                provider.search(
                    profile =
                        WebSearchProviderProfile(
                            id = "keenable-keyed",
                            kind = WebSearchProviderKind.KEENABLE,
                            endpoint = server.url("/v1/search").toString(),
                        ),
                    apiKey = "keen-secret",
                    request = WebSearchRequest(query = "android", maxResults = 5),
                )

            val recorded = server.takeRequest()
            assertEquals("keen-secret", recorded.getHeader("X-API-Key"))
            assertEquals(null, recorded.getHeader("X-Keenable-Title"))
            assertEquals("Keenable result", response.results.single().title)
            assertEquals("longer search snippet", response.results.single().snippet)
            assertEquals("2026-08-27T10:00:00Z", response.results.single().publishedAt)
            assertEquals("example.com", response.results.single().source)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `keenable official endpoint switches between public and keyed variants`() {
        assertEquals(
            "https://api.keenable.ai/v1/search/public",
            resolveKeenableEndpoint("https://api.keenable.ai/v1/search", hasApiKey = false),
        )
        assertEquals(
            "https://api.keenable.ai/v1/search",
            resolveKeenableEndpoint("https://api.keenable.ai/v1/search/public", hasApiKey = true),
        )
        assertEquals(
            "https://search.example.com/custom",
            resolveKeenableEndpoint("https://search.example.com/custom", hasApiKey = true),
        )
    }

    @Test
    fun `searxng adapter needs no api key and requests json format`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"results":[]}""")
            )
            val provider = SearxngWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            provider.search(
                profile =
                    WebSearchProviderProfile(
                        id = "searxng-test",
                        kind = WebSearchProviderKind.SEARXNG,
                        endpoint = server.url("/search").toString(),
                    ),
                apiKey = "",
                request = WebSearchRequest(query = "self hosted search", maxResults = 3),
            )

            val recorded = server.takeRequest()
            assertEquals("self hosted search", recorded.requestUrl?.queryParameter("q"))
            assertEquals("json", recorded.requestUrl?.queryParameter("format"))
            assertFalse(WebSearchProviderKind.SEARXNG.requiresApiKey)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `perplexity response preserves date and source`() {
        val profile = WebSearchProviderProfile(id = "pplx", kind = WebSearchProviderKind.PERPLEXITY)
        val response =
            parsePerplexitySearchResponse(
                profile,
                """
                {
                  "results": [{
                    "title": "Current result",
                    "url": "https://example.com/current",
                    "snippet": "fresh context",
                    "date": "2026-08-25"
                  }]
                }
                """.trimIndent(),
            )

        assertEquals("2026-08-25", response.results.single().publishedAt)
        assertEquals("example.com", response.results.single().source)
    }

    @Test
    fun `auto search is conservative and recognizes explicit freshness intent`() {
        assertTrue(shouldAutoSearch("这件事后来有什么最新进展？"))
        assertTrue(shouldAutoSearch("look up the latest release"))
        assertFalse(shouldAutoSearch("解释一下这篇文章里的虚拟线程"))
    }

    @Test
    fun `search query adds article title for pronoun follow-up`() {
        assertEquals(
            "Project Valhalla — 这件事后来有什么最新进展？",
            buildSearchQuery("Project Valhalla", "这件事后来有什么最新进展？"),
        )
    }

    @Test
    fun `search results become high priority reference contexts`() {
        val contexts =
            WebSearchResponse(
                providerId = "exa-1",
                providerName = "Exa",
                backendKind = WebSearchBackendKind.RAW_SEARCH,
                results =
                    listOf(
                        WebSearchResult(
                            title = "Fresh source",
                            url = "https://example.com/fresh",
                            snippet = "latest evidence",
                        )
                    ),
            ).toContextItems()

        assertEquals(1, contexts.size)
        assertEquals(LlmContextType.WEB_SEARCH_RESULT, contexts.single().type)
        assertEquals("https://example.com/fresh", contexts.single().sourceId)
        assertTrue(contexts.single().priority < 120)
        assertTrue(contexts.single().priority > 100)
        assertEquals("latest evidence", contexts.single().content)
    }

    @Test
    fun `disabled default search provider falls back to another enabled provider`() {
        val providers =
            listOf(
                WebSearchProviderProfile(id = "exa", kind = WebSearchProviderKind.EXA, enabled = false),
                WebSearchProviderProfile(id = "tavily", kind = WebSearchProviderKind.TAVILY, enabled = true),
            )

        assertEquals("tavily", normalizedDefaultProviderId(providers, "exa"))
    }

    @Test
    fun `web search reference context preserves source url`() {
        val context =
            WebSearchResponse(
                providerId = "exa",
                providerName = "Exa",
                backendKind = WebSearchBackendKind.RAW_SEARCH,
                results =
                    listOf(
                        WebSearchResult(
                            title = "Source title",
                            url = "https://example.com/source",
                            snippet = "source evidence",
                        )
                    ),
            ).toContextItems().single()

        assertEquals("https://example.com/source", context.sourceId)
        assertTrue(context.content.contains("source evidence"))
    }
}

