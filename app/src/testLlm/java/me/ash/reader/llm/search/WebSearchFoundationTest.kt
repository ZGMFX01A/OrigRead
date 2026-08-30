package me.ash.reader.llm.search

import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.SocketPolicy
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.llm.runtime.LlmContextType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

class WebSearchFoundationTest {
    @Test
    fun `prepared search uses frozen provider and key without rereading repository`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"results":[]}""")
            )
            val repository = mock<WebSearchRepository>()
            val httpClient = AiHttpClient()
            val service =
                WebSearchService(
                    repository = repository,
                    exa = ExaWebSearchProvider(httpClient, Dispatchers.IO),
                    tavily = TavilyWebSearchProvider(httpClient, Dispatchers.IO),
                    brave = BraveWebSearchProvider(httpClient, Dispatchers.IO),
                    perplexity = PerplexityWebSearchProvider(httpClient, Dispatchers.IO),
                    linkup = LinkupWebSearchProvider(httpClient, Dispatchers.IO),
                    firecrawl = FirecrawlWebSearchProvider(httpClient, Dispatchers.IO),
                    keenable = KeenableWebSearchProvider(httpClient, Dispatchers.IO),
                    searxng = SearxngWebSearchProvider(httpClient, Dispatchers.IO),
                )
            val frozenProfile =
                WebSearchProviderProfile(
                    id = "exa-frozen",
                    kind = WebSearchProviderKind.EXA,
                    name = "Frozen Exa",
                    endpoint = server.url("/search").toString(),
                )

            service.searchPrepared(
                request = WebSearchRequest(query = "frozen request", maxResults = 3),
                snapshot = WebSearchProviderSnapshot(profile = frozenProfile, apiKey = "frozen-secret"),
            )

            val recorded = server.takeRequest()
            assertEquals("frozen-secret", recorded.getHeader("x-api-key"))
            assertEquals("/search", recorded.requestUrl?.encodedPath)
            verifyNoInteractions(repository)
        } finally {
            server.shutdown()
        }
    }

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
            assertEquals("instant", json.getString("type"))
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
            assertEquals("false", recorded.requestUrl?.queryParameter("extra_snippets"))
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
            assertEquals(512, json.getInt("max_tokens_per_page"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `web search call overrides long ai client timeout`() {
        val httpClient = AiHttpClient()
        val httpRequest =
            okhttp3.Request.Builder()
                .url("https://example.com/search")
                .build()
        val searchRequest =
            WebSearchRequest(
                query = "latest android news",
                timeoutMillis = 3_000L,
            )

        val call = httpClient.newWebSearchCall(httpRequest, searchRequest)

        assertEquals(TimeUnit.MILLISECONDS.toNanos(3_000L), call.timeout().timeoutNanos())
    }

    @Test
    fun `web search call aborts delayed provider at request budget`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(2, TimeUnit.SECONDS)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"results":[]}""")
            )
            val provider = ExaWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            val startedAt = System.nanoTime()

            val failure =
                runCatching {
                    provider.search(
                        profile =
                            WebSearchProviderProfile(
                                id = "exa-timeout",
                                kind = WebSearchProviderKind.EXA,
                                endpoint = server.url("/search").toString(),
                            ),
                        apiKey = "exa-secret",
                        request =
                            WebSearchRequest(
                                query = "latest android news",
                                timeoutMillis = 400L,
                            ),
                    )
                }.exceptionOrNull()
            val elapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue("延迟请求应被 Dedicated Search timeout 中止", failure != null)
            assertTrue("实际耗时不应等满服务端 2 秒，elapsed=$elapsedMillis ms", elapsedMillis < 1_500L)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `web search cancellation cancels blocked provider call`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val provider = ExaWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            val job =
                launch(Dispatchers.IO) {
                    provider.search(
                        profile =
                            WebSearchProviderProfile(
                                id = "exa-cancel",
                                kind = WebSearchProviderKind.EXA,
                                endpoint = server.url("/search").toString(),
                            ),
                        apiKey = "exa-secret",
                        request = WebSearchRequest(query = "cancel me", timeoutMillis = 10_000L),
                    )
                }

            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
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
        assertTrue(shouldAutoSearch("我要知道当前最新的消息"))
        assertTrue(shouldAutoSearch("这件事情目前最新进展如何"))
        assertTrue(shouldAutoSearch("截至目前有什么新消息"))
        assertTrue(shouldAutoSearch("帮我网上搜索一下这个消息"))
        assertTrue(shouldAutoSearch("look up the latest release"))
        assertTrue(shouldAutoSearch("What is the current price of Bitcoin?"))
        assertTrue(shouldAutoSearch("Any updates on the launch?"))
        assertTrue(shouldAutoSearch("What changed this week?"))
        assertFalse(shouldAutoSearch("解释一下这篇文章里的虚拟线程"))
        assertFalse(shouldAutoSearch("这体现在哪些方面？"))
        assertFalse(shouldAutoSearch("当前提条件满足时会怎样？"))
        assertFalse(shouldAutoSearch("请解释后来居上的含义"))
        assertFalse(shouldAutoSearch("在网上面试和线下面试的区别"))
        assertFalse(shouldAutoSearch("解释一下最近邻算法"))
        assertFalse(shouldAutoSearch("搜索算法的时间复杂度是什么"))
        assertFalse(shouldAutoSearch("这个方案最新颖的地方是什么"))
        assertFalse(shouldAutoSearch("Explain electric current"))
        assertFalse(shouldAutoSearch("What is alternating current?"))
        assertFalse(shouldAutoSearch("Undercurrents in the ocean"))
        assertFalse(shouldAutoSearch("The history of cryptocurrency"))
        assertFalse(shouldAutoSearch("Update a SQL row with this statement"))
    }

    @Test
    fun `search decision exposes not needed triggered and force semantics`() {
        assertEquals(
            WebSearchRequestStatus.TRIGGERED,
            resolveWebSearchDecision(true, WebSearchMode.AUTO, "我要知道当前最新的消息").status,
        )
        assertEquals(
            WebSearchRequestStatus.NOT_NEEDED,
            resolveWebSearchDecision(true, WebSearchMode.AUTO, "总结这篇文章").status,
        )
        val force = resolveWebSearchDecision(true, WebSearchMode.FORCE, "总结这篇文章")
        assertEquals(WebSearchRequestStatus.TRIGGERED, force.status)
        assertTrue(force.required)
        assertEquals(
            WebSearchRequestStatus.NOT_NEEDED,
            resolveWebSearchDecision(false, WebSearchMode.FORCE, "查一下最新消息").status,
        )
    }

    @Test
    fun `auto search failure falls back while force returns required failure`() {
        val auto =
            buildWebSearchFailureResult(
                required = false,
                providerName = "Exa",
                error = InterruptedIOException("timeout"),
            )
        assertEquals(WebSearchRequestStatus.FAILED_FALLBACK, auto.status)
        assertFalse(auto.requiredFailure)
        assertEquals("Exa 搜索超时", auto.errorMessage)

        val force =
            buildWebSearchFailureResult(
                required = true,
                providerName = "Exa",
                error = InterruptedIOException("timeout"),
            )
        assertEquals(WebSearchRequestStatus.FAILED_REQUIRED, force.status)
        assertTrue(force.requiredFailure)
        assertEquals("Exa 搜索超时", force.errorMessage)
    }

    @Test
    fun `stopping generation only cancels an in flight search`() {
        assertEquals(
            WebSearchRequestStatus.CANCELLED,
            webSearchStatusAfterGenerationStopped(WebSearchRequestStatus.TRIGGERED),
        )
        assertEquals(
            WebSearchRequestStatus.SUCCESS,
            webSearchStatusAfterGenerationStopped(WebSearchRequestStatus.SUCCESS),
        )
        assertEquals(
            WebSearchRequestStatus.FAILED_FALLBACK,
            webSearchStatusAfterGenerationStopped(WebSearchRequestStatus.FAILED_FALLBACK),
        )
        assertEquals(
            WebSearchRequestStatus.FAILED_REQUIRED,
            webSearchStatusAfterGenerationStopped(WebSearchRequestStatus.FAILED_REQUIRED),
        )
        assertEquals(null, webSearchStatusAfterGenerationStopped(null))
    }

    @Test
    fun `empty search response maps auto to fallback and force to required failure`() {
        val response =
            WebSearchResponse(
                providerId = "exa",
                providerName = "Exa",
                backendKind = WebSearchBackendKind.RAW_SEARCH,
                results = emptyList(),
            )
        val auto = buildWebSearchResult(response = response, required = false)
        val force = buildWebSearchResult(response = response, required = true)

        assertEquals(WebSearchRequestStatus.EMPTY_RESULT, auto.status)
        assertFalse(auto.requiredFailure)
        assertEquals(response, auto.response)
        assertEquals(WebSearchRequestStatus.FAILED_REQUIRED, force.status)
        assertTrue(force.requiredFailure)
        assertEquals(null, force.response)
    }

    @Test
    fun `search response conservatively deduplicates equivalent urls`() {
        val response =
            WebSearchResponse(
                providerId = "exa",
                providerName = "Exa",
                backendKind = WebSearchBackendKind.RAW_SEARCH,
                results =
                    listOf(
                        WebSearchResult("First", " HTTPS://EXAMPLE.COM:443/story/?utm_source=app#section "),
                        WebSearchResult("Duplicate", "https://example.com/story?fbclid=abc"),
                        WebSearchResult("Http remains distinct", "http://example.com/story"),
                        WebSearchResult("Www remains distinct", "https://www.example.com/story"),
                        WebSearchResult("Business query remains distinct", "https://example.com/story?lang=zh"),
                    ),
            )

        val normalized = response.deduplicateResultsByUrl()

        assertEquals(4, normalized.results.size)
        assertEquals(" HTTPS://EXAMPLE.COM:443/story/?utm_source=app#section ", normalized.results.first().url)
        assertTrue(normalized.results.any { it.url == "http://example.com/story" })
        assertTrue(normalized.results.any { it.url == "https://www.example.com/story" })
        assertTrue(normalized.results.any { it.url.endsWith("?lang=zh") })
    }

    @Test
    fun `configured provider selection skips unfinished default provider`() {
        val unfinishedDefault =
            WebSearchProviderProfile(id = "exa", kind = WebSearchProviderKind.EXA)
        val fallback =
            WebSearchProviderProfile(id = "tavily", kind = WebSearchProviderKind.TAVILY)
        // configuredProviders 已排除 unfinishedDefault；即便 default id 仍指向它，也必须回退到 Tavily。
        assertEquals(
            fallback,
            selectConfiguredSearchProvider(
                configuredProviders = listOf(fallback),
                defaultProviderId = unfinishedDefault.id,
            ),
        )
        assertEquals(
            fallback,
            selectConfiguredSearchProvider(
                configuredProviders = listOf(fallback),
                defaultProviderId = fallback.id,
            ),
        )
        assertEquals(null, selectConfiguredSearchProvider(emptyList(), unfinishedDefault.id))
    }

    @Test
    fun `multiple configured search providers freeze only the selected default provider`() {
        val exa = WebSearchProviderProfile(id = "exa", kind = WebSearchProviderKind.EXA)
        val tavily = WebSearchProviderProfile(id = "tavily", kind = WebSearchProviderKind.TAVILY)
        val keenable = WebSearchProviderProfile(id = "keenable", kind = WebSearchProviderKind.KEENABLE)

        val prepared =
            buildWebSearchPreparedRequest(
                decision = WebSearchDecision(WebSearchRequestStatus.TRIGGERED, required = false),
                articleTitle = "OrigRead v1.2.0",
                userInput = "latest updates",
                configuredProviders = listOf(exa, tavily, keenable),
                defaultProviderId = tavily.id,
            )

        assertEquals(tavily.id, prepared.providerId)
        assertEquals(tavily.name, prepared.providerName)
        assertEquals(WebSearchProviderKind.TAVILY, prepared.providerKind)
        assertEquals("OrigRead v1.2.0 — latest updates", prepared.query)
    }

    @Test
    fun `search query adds article title for pronoun follow-up`() {
        assertEquals(
            "Project Valhalla — 这件事后来有什么最新进展？",
            buildSearchQuery("Project Valhalla", "这件事后来有什么最新进展？"),
        )
    }

    @Test
    fun `prepared search freezes exact query provider and auto timeout`() {
        val provider =
            WebSearchProviderProfile(id = "exa", kind = WebSearchProviderKind.EXA, name = "Exa Main")
        val prepared =
            buildWebSearchPreparedRequest(
                decision =
                    resolveWebSearchDecision(
                        enabled = true,
                        mode = WebSearchMode.AUTO,
                        userInput = "这件事后来有什么最新进展？",
                    ),
                articleTitle = "Project Valhalla",
                userInput = "这件事后来有什么最新进展？",
                configuredProviders = listOf(provider),
                defaultProviderId = provider.id,
            )

        assertTrue(prepared.triggered)
        assertFalse(prepared.required)
        assertEquals("Project Valhalla — 这件事后来有什么最新进展？", prepared.query)
        assertEquals(prepared.query, prepared.request?.query)
        assertEquals("exa", prepared.providerId)
        assertEquals("Exa Main", prepared.providerName)
        assertEquals(WebSearchProviderKind.EXA, prepared.providerKind)
        assertEquals(3_000L, prepared.request?.timeoutMillis)
        assertEquals(null, prepared.preflightErrorMessage)
    }

    @Test
    fun `prepared search uses configured result count`() {
        val provider = WebSearchProviderProfile(id = "exa", kind = WebSearchProviderKind.EXA)
        val prepared =
            buildWebSearchPreparedRequest(
                decision = WebSearchDecision(WebSearchRequestStatus.TRIGGERED, required = false),
                articleTitle = "OrigRead",
                userInput = "latest updates",
                configuredProviders = listOf(provider),
                defaultProviderId = provider.id,
                maxResults = 9,
            )

        assertEquals(9, prepared.request?.maxResults)
    }

    @Test
    fun `web search result count defaults to five and clamps to supported range`() {
        assertEquals(5, WebSearchSettings().maxResults)
        assertEquals(MIN_WEB_SEARCH_MAX_RESULTS, normalizeWebSearchMaxResults(0))
        assertEquals(7, normalizeWebSearchMaxResults(7))
        assertEquals(MAX_WEB_SEARCH_MAX_RESULTS, normalizeWebSearchMaxResults(99))
    }

    @Test
    fun `prepared force search freezes twelve second budget`() {
        val provider = WebSearchProviderProfile(id = "tavily", kind = WebSearchProviderKind.TAVILY)
        val prepared =
            buildWebSearchPreparedRequest(
                decision =
                    resolveWebSearchDecision(
                        enabled = true,
                        mode = WebSearchMode.FORCE,
                        userInput = "总结这篇文章",
                    ),
                articleTitle = "Article title",
                userInput = "总结这篇文章",
                configuredProviders = listOf(provider),
                defaultProviderId = provider.id,
            )

        assertTrue(prepared.required)
        assertEquals(12_000L, prepared.request?.timeoutMillis)
        assertEquals(prepared.query, prepared.request?.query)
    }

    @Test
    fun `prepared search keeps exact query when no provider is configured`() {
        val prepared =
            buildWebSearchPreparedRequest(
                decision =
                    resolveWebSearchDecision(
                        enabled = true,
                        mode = WebSearchMode.AUTO,
                        userInput = "查一下最新消息",
                    ),
                articleTitle = "OrigRead",
                userInput = "查一下最新消息",
                configuredProviders = emptyList(),
                defaultProviderId = null,
            )

        assertTrue(prepared.triggered)
        assertEquals("OrigRead — 查一下最新消息", prepared.query)
        assertEquals(null, prepared.providerId)
        assertEquals(null, prepared.request)
        assertEquals("尚未配置可用的 Web Search Provider", prepared.preflightErrorMessage)
    }

    @Test
    fun `not needed search plan does not create query provider or request`() {
        val prepared =
            buildWebSearchPreparedRequest(
                decision =
                    resolveWebSearchDecision(
                        enabled = true,
                        mode = WebSearchMode.AUTO,
                        userInput = "解释这篇文章",
                    ),
                articleTitle = "Article title",
                userInput = "解释这篇文章",
                configuredProviders =
                    listOf(WebSearchProviderProfile(id = "exa", kind = WebSearchProviderKind.EXA)),
                defaultProviderId = "exa",
            )

        assertFalse(prepared.triggered)
        assertEquals(null, prepared.query)
        assertEquals(null, prepared.providerId)
        assertEquals(null, prepared.request)
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
    fun `web search secret mask keeps exact secret length`() {
        assertEquals("", webSearchSecretMask(0))
        assertEquals("•••", webSearchSecretMask(3))
        assertEquals(128, webSearchSecretMask(128).length)
        assertEquals("", webSearchSecretMask(-1))
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
