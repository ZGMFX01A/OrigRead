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
