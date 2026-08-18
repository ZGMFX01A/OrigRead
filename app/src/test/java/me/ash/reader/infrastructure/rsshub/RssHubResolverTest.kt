package me.ash.reader.infrastructure.rsshub

import com.rometools.rome.feed.synd.SyndFeedImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.website.CandidateState
import okhttp3.OkHttpClient
import okio.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RssHubResolverTest {
    private val inputUrl = "https://example.com/user/42"

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
            whenever(rssHelper.parseFeedDirect(any(), eq(inputUrl), any()))
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
            }.whenever(rssHelper).parseFeedDirect(any(), eq(inputUrl), any())

            val result = resolver(routeMatcher, rssHelper, settingsRepository).probe(inputUrl)

            assertEquals(1, result.size)
            assertTrue(result.single().available)
            assertEquals(secondMatch.feedUrl, result.single().match.feedUrl)
            assertEquals("42", result.single().match.parameters["id"])
            verify(settingsRepository).recordFailure(eq(firstInstance), any())
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
            }.whenever(rssHelper).parseFeedDirect(any(), eq(inputUrl), any())

            val result = resolver(routeMatcher, rssHelper, settingsRepository).probe(inputUrl)
            val available = result.filter(RssHubProbeResult::available)

            assertEquals(setOf("hot", "telegraph"), available.map { it.match.route.id }.toSet())
            assertEquals(firstHot.feedUrl, available.first { it.match.route.id == "hot" }.match.feedUrl)
            assertEquals(secondTelegraph.feedUrl, available.first { it.match.route.id == "telegraph" }.match.feedUrl)
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
                .whenever(rssHelper).parseFeedDirect(any(), eq(inputUrl), any())

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

    private fun resolver(
        routeMatcher: RssHubRouteMatcher,
        rssHelper: RssHelper,
        settingsRepository: RssHubSettingsRepository,
    ) = RssHubResolver(
        routeMatcher = routeMatcher,
        rssHelper = rssHelper,
        settingsRepository = settingsRepository,
        okHttpClient = OkHttpClient(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun enabledSettingsRepository(vararg instances: String): RssHubSettingsRepository =
        mock<RssHubSettingsRepository>().also { repository ->
            whenever(repository.current()).thenReturn(RssHubSettings(enabled = true))
            whenever(repository.candidateInstances(any())).thenReturn(instances.toList())
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
