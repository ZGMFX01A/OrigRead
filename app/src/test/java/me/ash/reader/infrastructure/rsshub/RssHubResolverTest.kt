package me.ash.reader.infrastructure.rsshub

import com.rometools.rome.feed.synd.SyndFeedImpl
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import me.ash.reader.infrastructure.rss.FeedFetchException
import me.ash.reader.infrastructure.rss.FeedFetchFailureReason
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.website.CandidateState
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okio.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class RssHubResolverTest {
    private val inputUrl = "https://example.com/user/42"

    @Test
    fun `explicit ordinary RSS probing keeps typed errors even with RSSHub disabled`(): Unit = runBlocking {
        val helper = mock<RssHelper>()
        val repository = enabledSettingsRepository("https://public.example")
        whenever(repository.current()).thenReturn(RssHubSettings(enabled = false))
        doAnswer { throw FeedFetchException(FeedFetchFailureReason.BLOCKED_RESPONSE, 403, "challenge") }
            .whenever(helper).parseFeedDirect(any(), any(), any())
        val result = resolver(mock(), helper, repository)
            .probeExplicitRoute("/zhihu/hot?code=private", "https://specified.example")
        assertEquals(RssHubFailureReason.BLOCKED, result.failureReason)
        assertEquals(403, result.statusCode)
        verify(helper).parseFeedDirect(eq("https://specified.example/zhihu/hot?code=private"), eq(""), any())
        verify(helper, never()).parseFeedDirect(eq("https://public.example/zhihu/hot?code=private"), any(), any())
        verify(repository, never()).recordFailure(any(), any())
    }

    @Test
    fun `direct route probing limits in flight instances to eight and still reaches queued candidates`() = runTest {
        val instances = (1..9).map { "https://instance$it.example" }
        val helper = mock<RssHelper>()
        var active = 0
        var maximum = 0
        val reached = mutableListOf<String>()
        whenever(helper.parseFeedDirect(any(), any(), any())).doSuspendableAnswer { invocation ->
            val url = invocation.getArgument<String>(0)
            active++
            maximum = maxOf(maximum, active)
            reached += url
            try {
                delay(100)
                if (!url.startsWith(instances.last() + "/")) throw IOException("unavailable")
                SyndFeedImpl()
            } finally { active-- }
        }
        val results = resolver(mock(), helper, enabledSettingsRepository(*instances.toTypedArray()),
            StandardTestDispatcher(testScheduler)).probeRoute("/zhihu/hot")
        assertTrue("No more than eight instance requests may overlap", maximum <= 8)
        assertTrue(reached.contains(instances.last() + "/zhihu/hot"))
        assertTrue(results.any { it.available })
        assertEquals(0, active)
    }

    @Test
    fun `instance access credentials never enter the public fallback pool`() = runBlocking {
        listOf("key=private", "%6bey=private", "code=private").forEach { credential ->
            val helper = mock<RssHelper>()
            val privateInstance = "https://private.example/prefix"
            val publicInstance = "https://public.example"
            val path = "/zhihu/hot?$credential&limit=5"
            doAnswer { throw FeedFetchException(FeedFetchFailureReason.HTTP_ERROR, 401, "auth failed") }
                .whenever(helper).parseFeedDirect(any(), any(), any())
            val results = resolver(mock(), helper, enabledSettingsRepository(publicInstance))
                .probeRoute(path, privateInstance)
            assertEquals(listOf(privateInstance), results.map { it.instanceBaseUrl })
            verify(helper).parseFeedDirect(eq(privateInstance + path), any(), any())
            verify(helper, never()).parseFeedDirect(eq(publicInstance + path), any(), any())
        }
    }

    @Test
    fun `credentials on a logical route require an explicit instance before networking`() = runBlocking {
        val helper = mock<RssHelper>()
        val result = resolver(mock(), helper, enabledSettingsRepository("https://public.example"))
            .probeRoute("/zhihu/hot?key=private").single()
        assertEquals(RssHubFailureReason.AUTHENTICATION_REQUIRES_INSTANCE, result.failureReason)
        verifyNoInteractions(helper)
    }

    @Test
    fun `automatic recovery never forces a disabled historical instance into the pool`(): Unit = runBlocking {
        val old = "https://disabled.example"
        val healthy = "https://healthy.example"
        val repository = enabledSettingsRepository(healthy)
        whenever(repository.current()).thenReturn(RssHubSettings(instances = listOf(
            RssHubInstance("old", old, "", "", enabled = false),
            RssHubInstance("healthy", healthy, "", ""),
        )))
        val helper = mock<RssHelper>()
        whenever(helper.parseFeedDirect(any(), any(), any())).thenReturn(SyndFeedImpl())
        val result = resolver(mock(), helper, repository).probeRouteForRecovery("/zhihu/hot", old).single()
        assertEquals(healthy, result.instanceBaseUrl)
        verify(helper, never()).parseFeedDirect(eq("$old/zhihu/hot"), any(), any())
    }

    @Test
    fun `authenticated automatic recovery respects removal and disabling of its bound instance`() = runBlocking {
        listOf(emptyList(), listOf(RssHubInstance("bound", "https://bound.example", "", "", enabled = false)))
            .forEach { instances ->
                val helper = mock<RssHelper>()
                val repository = enabledSettingsRepository("https://public.example")
                whenever(repository.current()).thenReturn(RssHubSettings(instances = instances))
                val result = resolver(mock(), helper, repository)
                    .probeRouteForRecovery("/zhihu/hot?code=private", "https://bound.example").single()
                assertEquals(RssHubFailureReason.BOUND_INSTANCE_DISABLED, result.failureReason)
                verifyNoInteractions(helper)
            }
    }

    @Test
    fun `authenticated recovery only requests its enabled bound instance`(): Unit = runBlocking {
        val bound = "https://bound.example"
        val helper = mock<RssHelper>()
        val repository = enabledSettingsRepository("https://public.example")
        whenever(repository.current()).thenReturn(RssHubSettings(instances = listOf(
            RssHubInstance("bound", bound, "", ""),
        )))
        whenever(helper.parseFeedDirect(any(), any(), any())).thenReturn(SyndFeedImpl())
        assertEquals(bound, resolver(mock(), helper, repository)
            .probeRouteForRecovery("/zhihu/hot?key=private", bound).single().instanceBaseUrl)
        verify(helper).parseFeedDirect(eq("$bound/zhihu/hot?key=private"), any(), any())
    }

    @Test
    fun `authenticated recovery canonicalizes both settings and stored instance identity`(): Unit = runBlocking {
        val configured = "https://HUB.EXAMPLE:443/prefix/"
        val canonical = "https://hub.example/prefix"
        val helper = mock<RssHelper>()
        val repository = enabledSettingsRepository(configured)
        whenever(repository.current()).thenReturn(RssHubSettings(instances = listOf(
            RssHubInstance("bound", configured, "", ""),
        )))
        whenever(helper.parseFeedDirect(any(), any(), any())).thenReturn(SyndFeedImpl())
        val parsed = requireNotNull(RssHubInputParser.parseExplicit(
            "https://HUB.EXAMPLE:443/prefix/zhihu/hot?key=private", listOf(configured),
        ))
        val result = resolver(mock(), helper, repository)
            .probeRouteForRecovery(parsed.routePath, parsed.preferredInstance).single()
        assertTrue(result.available)
        assertEquals(canonical, result.instanceBaseUrl)
        verify(helper).parseFeedDirect(eq("$canonical/zhihu/hot?key=private"), any(), any())
    }

    @Test
    fun `credential binding distinguishes deployments on the same origin`() = runBlocking {
        val helper = mock<RssHelper>()
        val repository = enabledSettingsRepository("https://hub.example/other")
        whenever(repository.current()).thenReturn(RssHubSettings(instances = listOf(
            RssHubInstance("other", "https://hub.example/other", "", ""),
        )))
        val result = resolver(mock(), helper, repository)
            .probeRouteForRecovery("/zhihu/hot?key=private", "https://hub.example/original").single()
        assertEquals(RssHubFailureReason.BOUND_INSTANCE_DISABLED, result.failureReason)
        verifyNoInteractions(helper)
    }

    @Test
    fun `Radar budget expiration preserves HTTP diagnostics and also reports unfinished validation`() = runTest {
        val first = "https://first.example"
        val slow = "https://slow.example"
        val matcher = mock<RssHubRouteMatcher>()
        listOf(first, slow).forEach { instance ->
            whenever(matcher.match(eq(inputUrl), eq(instance), any()))
                .thenReturn(listOf(resolved("hot", "$instance/zhihu/hot")))
        }
        val helper = mock<RssHelper>()
        whenever(helper.parseFeedDirect(any(), any(), any())).doSuspendableAnswer { invocation ->
            if (invocation.getArgument<String>(0).startsWith(first)) {
                throw FeedFetchException(FeedFetchFailureReason.HTTP_ERROR, 503, "maintenance")
            }
            delay(20_000)
            SyndFeedImpl()
        }
        val results = resolver(matcher, helper, enabledSettingsRepository(first, slow),
            StandardTestDispatcher(testScheduler)).probe(inputUrl)
        assertEquals(listOf(RssHubFailureReason.HTTP_ERROR, RssHubFailureReason.PROBE_BUDGET_EXHAUSTED),
            results.map { it.failureReason })
        assertEquals(503, results.first().statusCode)
    }

    @Test
    fun `unsupported feed output retains a distinct failure reason`() = runBlocking {
        val helper = mock<RssHelper>()
        doAnswer { throw FeedFetchException(FeedFetchFailureReason.UNSUPPORTED_FORMAT, 200, "JSON feed") }
            .whenever(helper).parseFeedDirect(any(), any(), any())
        val result = resolver(mock(), helper, enabledSettingsRepository("https://hub.example"))
            .probeRoute("/zhihu/hot?format=json").single()
        assertEquals(RssHubFailureReason.UNSUPPORTED_FORMAT, result.failureReason)
    }

    @Test
    fun `Radar network failures cool the instance globally but HTTP route errors do not`(): Unit = runBlocking {
        val instance = "https://hub.example.com"
        val matcher = mock<RssHubRouteMatcher>()
        whenever(matcher.match(eq(inputUrl), eq(instance), any()))
            .thenReturn(listOf(resolved("hot", "$instance/zhihu/hot")))
        val helper = mock<RssHelper>()
        val repository = enabledSettingsRepository(instance)
        doAnswer { throw SocketTimeoutException() }.whenever(helper).parseFeedDirect(any(), any(), any())
        resolver(matcher, helper, repository).probe(inputUrl)
        verify(repository).recordFailure(eq(instance), any())

        val httpRepository = enabledSettingsRepository(instance)
        doAnswer { throw FeedFetchException(FeedFetchFailureReason.HTTP_ERROR, 503, "maintenance") }
            .whenever(helper).parseFeedDirect(any(), any(), any())
        resolver(matcher, helper, httpRepository).probe(inputUrl)
        verify(httpRepository, never()).recordFailure(any(), any())
    }

    @Test
    fun `budget expiration preserves completed diagnostics while slow instances run in parallel`() = runTest {
        val helper = mock<RssHelper>()
        whenever(helper.parseFeedDirect(any(), any(), any())).doSuspendableAnswer { invocation ->
            if (invocation.getArgument<String>(0).contains("first.example")) {
                throw FeedFetchException(FeedFetchFailureReason.HTTP_ERROR, 503, "maintenance")
            }
            delay(20_000)
            SyndFeedImpl()
        }
        val results = resolver(mock(), helper, enabledSettingsRepository(
            "https://first.example", "https://slow.example", "https://untried.example",
        ), StandardTestDispatcher(testScheduler)).probeRoute("/zhihu/hot")
        assertEquals(listOf(RssHubFailureReason.HTTP_ERROR, RssHubFailureReason.PROBE_BUDGET_EXHAUSTED),
            results.map { it.failureReason })
        verify(helper).parseFeedDirect(eq("https://slow.example/zhihu/hot"), any(), any())
        verify(helper).parseFeedDirect(eq("https://untried.example/zhihu/hot"), any(), any())
    }

    @Test
    fun `last of sixteen instances is still reached before total probe budget`() = runTest {
        val helper = mock<RssHelper>()
        val instances = (1..16).map { "https://instance-$it.example" }
        whenever(helper.parseFeedDirect(any(), any(), any())).doSuspendableAnswer { invocation ->
            val feedUrl = invocation.getArgument<String>(0)
            if (feedUrl.startsWith(instances.last())) {
                SyndFeedImpl()
            } else {
                delay(6_000)
                error("slow instance should be cancelled after a healthy peer succeeds")
            }
        }

        val results = resolver(
            mock(),
            helper,
            enabledSettingsRepository(*instances.toTypedArray()),
            StandardTestDispatcher(testScheduler),
        ).probeRoute("/tiddlywiki/releases")

        assertEquals(instances.last(), results.first(RssHubProbeResult::available).instanceBaseUrl)
        assertTrue(testScheduler.currentTime < 12_000)
        instances.forEach { instance ->
            verify(helper).parseFeedDirect(eq("$instance/tiddlywiki/releases"), any(), any())
        }
    }

    @Test
    fun `RSSHub probing skips page icon requests after feed validation`(): Unit = runBlocking {
        val helper = mock<RssHelper>()
        whenever(helper.parseFeedDirect(any(), any(), any())).thenReturn(SyndFeedImpl())
        val repository = enabledSettingsRepository("https://hub.example.com")
        val result = resolver(mock(), helper, repository).probeRoute("/zhihu/hot")
        assertTrue(result.single().available)
        verify(helper).parseFeedDirect(eq("https://hub.example.com/zhihu/hot"), eq(""), any())
        Unit
    }

    @Test
    fun `transport errors retain their actual failure type`() = runBlocking {
        val cases = listOf(
            java.io.EOFException("closed") to RssHubFailureReason.CONNECTION_CLOSED,
            java.net.ProtocolException("unexpected end of stream") to RssHubFailureReason.CONNECTION_CLOSED,
            java.net.UnknownHostException("dns") to RssHubFailureReason.DNS_FAILURE,
            javax.net.ssl.SSLHandshakeException("tls") to RssHubFailureReason.TLS_ERROR,
            java.io.InterruptedIOException("timeout") to RssHubFailureReason.TIMEOUT,
        )
        cases.forEach { (error, expected) ->
            val helper = mock<RssHelper>()
            doAnswer { throw error }.whenever(helper).parseFeedDirect(any(), any(), any())
            val result = resolver(mock(), helper, enabledSettingsRepository("https://hub.example.com"))
                .probeRoute("/zhihu/hot").single()
            assertEquals(expected, result.failureReason)
        }
    }

    @Test
    fun `HTTP 429 cools the whole instance while ordinary route HTTP errors stay route scoped`(): Unit = runBlocking {
        val instance = "https://limited.example.com"

        val limitedHelper = mock<RssHelper>()
        val limitedRepository = enabledSettingsRepository(instance)
        doAnswer {
            throw FeedFetchException(
                FeedFetchFailureReason.HTTP_ERROR,
                429,
                "rate limited",
            )
        }.whenever(limitedHelper).parseFeedDirect(any(), any(), any())
        resolver(mock(), limitedHelper, limitedRepository).probeRoute("/zhihu/hot")
        verify(limitedRepository).recordFailure(eq(instance), any())

        val routeErrorHelper = mock<RssHelper>()
        val routeErrorRepository = enabledSettingsRepository(instance)
        doAnswer {
            throw FeedFetchException(
                FeedFetchFailureReason.HTTP_ERROR,
                503,
                "upstream unavailable",
            )
        }.whenever(routeErrorHelper).parseFeedDirect(any(), any(), any())
        resolver(mock(), routeErrorHelper, routeErrorRepository).probeRoute("/zhihu/hot")
        verify(routeErrorRepository, never()).recordFailure(any(), any())
        Unit
    }

    @Test
    fun `缺少参数的路由只返回提示且绝不发起网络请求`() {
        runBlocking {
            val routeMatcher = mock<RssHubRouteMatcher>()
            val rssHelper = mock<RssHelper>()
            val settingsRepository = enabledSettingsRepository()
            val unresolved =
                RssHubRouteMatch(
                    route = route("missing"),
                    missingParameters = listOf("id"),
                )
            whenever(routeMatcher.match(eq(inputUrl), eq("https://rsshub.example.com"), any()))
                .thenReturn(listOf(unresolved))

            val resolver = resolver(routeMatcher, rssHelper, settingsRepository)
            val result = resolver.probe(inputUrl, "https://rsshub.example.com").single()

            assertEquals(CandidateState.NEEDS_INPUT, result.state)
            assertEquals(listOf("id"), result.match.missingParameters)
            verify(rssHelper, never()).parseFeedDirect(any(), any(), any())
        }
    }

    @Test
    fun `同一网址多个动态路由成功时全部返回候选`() {
        runBlocking {
            val routeMatcher = mock<RssHubRouteMatcher>()
            val rssHelper = mock<RssHelper>()
            val settingsRepository = enabledSettingsRepository()
            val matches =
                listOf(
                    resolved("first", "https://rsshub.example.com/example/user/42"),
                    resolved("second", "https://rsshub.example.com/example/posts/42"),
                )
            whenever(routeMatcher.match(eq(inputUrl), eq("https://rsshub.example.com"), any()))
                .thenReturn(matches)
            whenever(rssHelper.parseFeedDirect(any(), eq(""), any()))
                .thenReturn(SyndFeedImpl())

            val result = resolver(routeMatcher, rssHelper, settingsRepository)
                .probe(inputUrl, "https://rsshub.example.com")

            assertEquals(2, result.size)
            assertTrue(result.all(RssHubProbeResult::available))
            assertEquals(matches.mapNotNull { it.feedUrl }.toSet(), result.mapNotNull { it.match.feedUrl }.toSet())
        }
    }

    @Test
    fun `首个实例网络失败后自动回退下一实例且不丢失动态参数`() {
        runBlocking {
            val firstInstance = "https://first.example.com"
            val secondInstance = "https://second.example.com"
            val routeMatcher = mock<RssHubRouteMatcher>()
            val rssHelper = mock<RssHelper>()
            val settingsRepository = enabledSettingsRepository(firstInstance, secondInstance)
            val firstMatch = resolved("dynamic", "$firstInstance/example/user/42")
            val secondMatch = resolved("dynamic", "$secondInstance/example/user/42")
            whenever(routeMatcher.match(eq(inputUrl), eq(firstInstance), any())).thenReturn(listOf(firstMatch))
            whenever(routeMatcher.match(eq(inputUrl), eq(secondInstance), any())).thenReturn(listOf(secondMatch))
            doAnswer { invocation ->
                val feedUrl = invocation.getArgument<String>(0)
                if (feedUrl.startsWith(firstInstance)) throw IOException("offline")
                SyndFeedImpl()
            }.whenever(rssHelper).parseFeedDirect(any(), eq(""), any())

            val result = resolver(routeMatcher, rssHelper, settingsRepository).probe(inputUrl)

            assertEquals(1, result.size)
            assertTrue(result.single().available)
            assertEquals(secondMatch.feedUrl, result.single().match.feedUrl)
            assertEquals("42", result.single().match.parameters["id"])
            verify(settingsRepository).recordRouteFailure(eq(firstInstance), eq("example/user/42"), any())
            verify(settingsRepository).recordRouteSuccess(secondInstance, "example/user/42")
            verify(settingsRepository).recordSuccess(secondInstance)
        }
    }

    @Test
    fun `不同实例分别提供不同路由时合并全部可用路由`() {
        runBlocking {
            val firstInstance = "https://first.example.com"
            val secondInstance = "https://second.example.com"
            val routeMatcher = mock<RssHubRouteMatcher>()
            val rssHelper = mock<RssHelper>()
            val settingsRepository = enabledSettingsRepository(firstInstance, secondInstance)
            val firstHot = resolved("hot", "$firstInstance/example/hot/42")
            val firstTelegraph = resolved("telegraph", "$firstInstance/example/telegraph/42")
            val secondHot = resolved("hot", "$secondInstance/example/hot/42")
            val secondTelegraph = resolved("telegraph", "$secondInstance/example/telegraph/42")
            whenever(routeMatcher.match(eq(inputUrl), eq(firstInstance), any()))
                .thenReturn(listOf(firstHot, firstTelegraph))
            whenever(routeMatcher.match(eq(inputUrl), eq(secondInstance), any()))
                .thenReturn(listOf(secondHot, secondTelegraph))
            doAnswer { invocation ->
                val feedUrl = invocation.getArgument<String>(0)
                when (feedUrl) {
                    firstHot.feedUrl, secondTelegraph.feedUrl -> SyndFeedImpl()
                    else -> throw IOException("route unavailable")
                }
            }.whenever(rssHelper).parseFeedDirect(any(), eq(""), any())

            val result = resolver(routeMatcher, rssHelper, settingsRepository).probe(inputUrl)
            val available = result.filter(RssHubProbeResult::available)

            assertEquals(setOf("hot", "telegraph"), available.map { it.match.route.id }.toSet())
            assertTrue(
                available.first { it.match.route.id == "hot" }.match.feedUrl in
                    setOf(firstHot.feedUrl, secondHot.feedUrl)
            )
            assertTrue(
                available.first { it.match.route.id == "telegraph" }.match.feedUrl in
                    setOf(firstTelegraph.feedUrl, secondTelegraph.feedUrl)
            )
        }
    }

    @Test
    fun `路由已匹配但所有实例失败时仍返回每条路由诊断`() {
        runBlocking {
            val firstInstance = "https://first.example.com"
            val secondInstance = "https://second.example.com"
            val routeMatcher = mock<RssHubRouteMatcher>()
            val rssHelper = mock<RssHelper>()
            val settingsRepository = enabledSettingsRepository(firstInstance, secondInstance)
            whenever(routeMatcher.match(eq(inputUrl), eq(firstInstance), any()))
                .thenReturn(
                    listOf(
                        resolved("hot", "$firstInstance/example/hot/42"),
                        resolved("telegraph", "$firstInstance/example/telegraph/42"),
                    )
                )
            whenever(routeMatcher.match(eq(inputUrl), eq(secondInstance), any()))
                .thenReturn(
                    listOf(
                        resolved("hot", "$secondInstance/example/hot/42"),
                        resolved("telegraph", "$secondInstance/example/telegraph/42"),
                    )
                )
            doAnswer { throw IOException("instance unavailable") }
                .whenever(rssHelper).parseFeedDirect(any(), eq(""), any())

            val result = resolver(routeMatcher, rssHelper, settingsRepository).probe(inputUrl)

            assertEquals(setOf("hot", "telegraph"), result.map { it.match.route.id }.toSet())
            assertTrue(result.none(RssHubProbeResult::available))
            assertTrue(result.all { it.state == CandidateState.NETWORK_UNAVAILABLE })
        }
    }

    @Test
    fun `rsshub关闭时仍返回本地已匹配路由`() {
        runBlocking {
            val routeMatcher = mock<RssHubRouteMatcher>()
            val rssHelper = mock<RssHelper>()
            val settingsRepository = mock<RssHubSettingsRepository>()
            val match = resolved("dynamic", "https://rsshub.app/example/user/42")
            whenever(routeMatcher.match(eq(inputUrl), eq(RssHubResolver.DEFAULT_INSTANCE), any()))
                .thenReturn(listOf(match))
            whenever(settingsRepository.current()).thenReturn(RssHubSettings(enabled = false))

            val result = resolver(routeMatcher, rssHelper, settingsRepository).probe(inputUrl)

            assertEquals(1, result.size)
            assertEquals(CandidateState.UNSUPPORTED, result.single().state)
            assertEquals("dynamic", result.single().match.route.id)
            verify(rssHelper, never()).parseFeedDirect(any(), any(), any())
        }
    }

    @Test
    fun `没有启用实例时仍返回本地已匹配路由`() {
        runBlocking {
            val routeMatcher = mock<RssHubRouteMatcher>()
            val rssHelper = mock<RssHelper>()
            val settingsRepository = enabledSettingsRepository()
            val match = resolved("dynamic", "https://rsshub.app/example/user/42")
            whenever(routeMatcher.match(eq(inputUrl), eq(RssHubResolver.DEFAULT_INSTANCE), any()))
                .thenReturn(listOf(match))

            val result = resolver(routeMatcher, rssHelper, settingsRepository).probe(inputUrl)

            assertEquals(1, result.size)
            assertEquals(CandidateState.UNSUPPORTED, result.single().state)
            assertEquals("dynamic", result.single().match.route.id)
            verify(rssHelper, never()).parseFeedDirect(any(), any(), any())
        }
    }

    @Test
    fun `explicit logical route falls back from blocked preferred instance`() = runBlocking {
        val firstInstance = "https://first.example.com"
        val secondInstance = "https://second.example.com"
        val routePath = "/zhihu/hot"
        val routeFamily = RssHubInputParser.routeFamily(routePath)
        val routeMatcher = mock<RssHubRouteMatcher>()
        val rssHelper = mock<RssHelper>()
        val settingsRepository = enabledSettingsRepository(secondInstance)
        whenever(settingsRepository.candidateInstancesForRoute(eq(routeFamily), any()))
            .thenReturn(listOf(secondInstance))
        doAnswer { invocation ->
            val feedUrl = invocation.getArgument<String>(0)
            if (feedUrl.startsWith(firstInstance)) {
                throw FeedFetchException(
                    reason = FeedFetchFailureReason.BLOCKED_RESPONSE,
                    statusCode = 403,
                    message = "Feed request was blocked by anti-bot verification (HTTP 403)",
                )
            }
            SyndFeedImpl()
        }.whenever(rssHelper).parseFeedDirect(any(), any(), any())

        val result =
            resolver(routeMatcher, rssHelper, settingsRepository)
                .probeRoute(routePath = routePath, preferredInstance = firstInstance)

        val success = result.first(RssHubProbeResult::available)
        assertEquals("$secondInstance$routePath", success.match.feedUrl)
        assertEquals(routePath, success.routePath)
        assertEquals(secondInstance, success.instanceBaseUrl)
        assertTrue(result.any { it.failureReason == RssHubFailureReason.BLOCKED })
        val clientCaptor = argumentCaptor<OkHttpClient>()
        verify(rssHelper, org.mockito.kotlin.atLeastOnce())
            .parseFeedDirect(any(), any(), clientCaptor.capture())
        assertTrue(clientCaptor.allValues.all { it.protocols == listOf(Protocol.HTTP_1_1) })
        verify(settingsRepository).recordRouteFailure(eq(firstInstance), eq(routeFamily), any())
        verify(settingsRepository).recordRouteSuccess(secondInstance, routeFamily)
    }

    @Test
    fun `explicit preferred instance participates in parallel fallback even when disabled from automatic pool`() = runTest {
        val preferred = "https://rsshub.app"
        val healthy = "https://healthy.example.com"
        val later = "https://later.example.com"
        val routePath = "/zhihu/hot"
        val routeMatcher = mock<RssHubRouteMatcher>()
        val rssHelper = mock<RssHelper>()
        val settingsRepository = enabledSettingsRepository(healthy, later)

        whenever(rssHelper.parseFeedDirect(any(), any(), any())).doSuspendableAnswer { invocation ->
            val feedUrl = invocation.getArgument<String>(0)
            when {
                feedUrl.startsWith(preferred) ->
                    throw FeedFetchException(
                        reason = FeedFetchFailureReason.BLOCKED_RESPONSE,
                        statusCode = 403,
                        message = "official instance restricted",
                    )
                feedUrl.startsWith(healthy) -> {
                    delay(10)
                    SyndFeedImpl()
                }
                else -> {
                    delay(10_000)
                    throw SocketTimeoutException("later candidate may race in bounded parallel probing")
                }
            }
        }

        val results =
            resolver(routeMatcher, rssHelper, settingsRepository)
                .probeRoute(routePath, preferred)

        assertEquals(healthy, results.first(RssHubProbeResult::available).instanceBaseUrl)
        verify(rssHelper).parseFeedDirect(eq("$preferred$routePath"), any(), any())
        verify(rssHelper).parseFeedDirect(eq("$healthy$routePath"), any(), any())
        Unit
    }

    @Test
    fun `explicit route preserves heterogeneous failure diagnostics across instances`() = runBlocking {
        val blocked = "https://blocked.example.com"
        val upstream = "https://upstream.example.com"
        val timeout = "https://timeout.example.com"
        val routePath = "/bilibili/user/dynamic/1161918898"
        val routeMatcher = mock<RssHubRouteMatcher>()
        val rssHelper = mock<RssHelper>()
        val settingsRepository = enabledSettingsRepository(blocked, upstream, timeout)

        doAnswer { invocation ->
            when (invocation.getArgument<String>(0).substringBefore(routePath)) {
                blocked ->
                    throw FeedFetchException(
                        reason = FeedFetchFailureReason.BLOCKED_RESPONSE,
                        statusCode = 403,
                        message = "blocked",
                    )
                upstream ->
                    throw FeedFetchException(
                        reason = FeedFetchFailureReason.HTTP_ERROR,
                        statusCode = 503,
                        message = "upstream unavailable",
                    )
                timeout -> throw SocketTimeoutException("timeout")
                else -> error("unexpected instance")
            }
        }.whenever(rssHelper).parseFeedDirect(any(), any(), any())

        val results = resolver(routeMatcher, rssHelper, settingsRepository).probeRoute(routePath)

        assertEquals(3, results.size)
        assertTrue(results.none(RssHubProbeResult::available))
        assertEquals(
            listOf(
                RssHubFailureReason.BLOCKED,
                RssHubFailureReason.HTTP_ERROR,
                RssHubFailureReason.TIMEOUT,
            ),
            results.map { it.failureReason },
        )
        assertTrue(results.all { it.routePath == routePath })
        assertEquals(listOf(blocked, upstream, timeout), results.map { it.instanceBaseUrl })
    }

    @Test
    fun `logical route probing covers anti crawler and ordinary route families`() = runBlocking {
        val healthy = "https://healthy.example.com"
        val routeMatcher = mock<RssHubRouteMatcher>()
        val rssHelper = mock<RssHelper>()
        val settingsRepository = enabledSettingsRepository(healthy)
        whenever(rssHelper.parseFeedDirect(any(), any(), any())).thenReturn(SyndFeedImpl())
        val resolver = resolver(routeMatcher, rssHelper, settingsRepository)

        val routes =
            listOf(
                "/zhihu/hot",
                "/bilibili/user/dynamic/1161918898",
                "/tiddlywiki/releases",
                "/bilibili/hot-search",
                "/sspai/matrix",
                "/douban/movie/coming",
                "/telegram/blog",
                "/weibo/search/hot",
                "/github/trending/daily",
                "/github/issue/DIYgod/RSSHub?filter_link=https%3A%2F%2Fgithub.com",
            )

        routes.forEach { routePath ->
            val success = resolver.probeRoute(routePath).first(RssHubProbeResult::available)
            assertEquals(routePath, success.routePath)
            assertEquals("$healthy$routePath", success.match.feedUrl)
            assertEquals(healthy, success.instanceBaseUrl)
        }
    }

    private fun resolver(
        routeMatcher: RssHubRouteMatcher,
        rssHelper: RssHelper,
        settingsRepository: RssHubSettingsRepository,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = RssHubResolver(
        routeMatcher = routeMatcher,
        rssHelper = rssHelper,
        settingsRepository = settingsRepository,
        okHttpClient = OkHttpClient(),
        ioDispatcher = dispatcher,
    )

    private fun enabledSettingsRepository(vararg instances: String): RssHubSettingsRepository =
        mock<RssHubSettingsRepository>().also { repository ->
            whenever(repository.current()).thenReturn(RssHubSettings(enabled = true))
            whenever(repository.candidateInstances(any())).thenReturn(instances.toList())
            whenever(repository.candidateInstancesForRoute(any(), any())).thenReturn(instances.toList())
        }

    private fun route(id: String) =
        RssHubRouteDefinition(
            id = id,
            name = id,
            host = "example.com",
            pathPrefix = "/user",
            target = "/example/user/:id",
            sourcePathTemplate = "/user/:id",
        )

    private fun resolved(id: String, feedUrl: String) =
        RssHubRouteMatch(
            route = route(id),
            feedUrl = feedUrl,
            parameters = mapOf("id" to "42"),
        )
}
