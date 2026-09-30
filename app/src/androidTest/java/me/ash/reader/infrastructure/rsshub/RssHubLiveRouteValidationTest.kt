package me.ash.reader.infrastructure.rsshub

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.ash.reader.infrastructure.android.AndroidApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用生产探测入口。用户原始故障样本是硬验收，其余矩阵用于跨 route-family 的广域回归。 */
@RunWith(AndroidJUnit4::class)
class RssHubLiveRouteValidationTest {
    @Test
    fun validateUserReportedRoutesAgainstCurrentInstancePool() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as AndroidApp
        val resolver = RssHubResolver(
            RssHubRouteMatcher(RssHubRouteCatalog(context)),
            app.rssHelper,
            RssHubSettingsRepository(context),
            app.okHttpClient,
            app.ioDispatcher,
        )
        // 覆盖用户报告样本之外的多种 route family：静态、动态参数、可选参数、带 query、
        // 不同上游站点与不同反爬强度。公共实例本身会波动，因此逐项保留完整诊断；
        // 整体仍要求至少有多个不同 family 能真实拉取，避免“测试全红但只记录原因也算通过”。
        val cases = listOf(
            "https://rsshub.app/zhihu/hot" to "/zhihu/hot",
            "rsshub://bilibili/user/dynamic/1161918898" to "/bilibili/user/dynamic/1161918898",
            "rsshub://tiddlywiki/releases" to "/tiddlywiki/releases",
            "rsshub://bilibili/hot-search" to "/bilibili/hot-search",
            "rsshub://sspai/matrix" to "/sspai/matrix",
            "rsshub://douban/movie/coming" to "/douban/movie/coming",
            "rsshub://telegram/blog" to "/telegram/blog",
            "rsshub://weibo/search/hot" to "/weibo/search/hot",
            "rsshub://github/trending/daily" to "/github/trending/daily",
            "rsshub://github/issue/DIYgod/RSSHub?filter_link=https%3A%2F%2Fgithub.com" to
                "/github/issue/DIYgod/RSSHub?filter_link=https%3A%2F%2Fgithub.com",
        )
        val requiredRoutes =
            setOf(
                "/zhihu/hot",
                "/bilibili/user/dynamic/1161918898",
            )
        val usableFamilies = linkedSetOf<String>()
        cases.forEach { (input, routePath) ->
            val parsed = requireNotNull(RssHubInputParser.parseExplicit(input))
            assertEquals(routePath, parsed.routePath)
            var results = resolver.probeRoute(parsed.routePath, parsed.preferredInstance)
            // 公共实例会短时抖动；原始回归样本允许再走一次已经更新过健康排序的生产路径。
            // 第二次仍无可用实例就直接失败，不能由其它 family 的成功掩盖。
            if (routePath in requiredRoutes && results.none { it.available }) {
                results = resolver.probeRoute(parsed.routePath, parsed.preferredInstance)
            }
            assertTrue("Explicit route must retain either success or a failure diagnostic", results.isNotEmpty())
            results.forEach { result ->
                assertEquals(routePath, result.routePath)
                if (!result.available) assertNotNull(result.failureReason)
                Log.i(TAG, "RESULT route=$routePath instance=${result.instanceBaseUrl} " +
                    "available=${result.available} reason=${result.failureReason} code=${result.statusCode} " +
                    "detail=${result.message.orEmpty()}")
            }
            if (results.any { it.available }) {
                usableFamilies += RssHubInputParser.routeFamily(routePath)
            }
            if (routePath in requiredRoutes) {
                assertTrue(
                    "Required RSSHub regression route must have at least one usable instance: $routePath; " +
                        "diagnostics=${results.joinToString { "${it.instanceBaseUrl}:${it.failureReason}:${it.statusCode}" }}",
                    results.any { it.available },
                )
            }
            Log.i(TAG, "SUMMARY route=$routePath usable=${results.count { it.available }}")
        }
        assertTrue(
            "Broad RSSHub live matrix should have at least 3 usable route families; actual=$usableFamilies",
            usableFamilies.size >= 3,
        )
    }

    private companion object {
        const val TAG = "RssHubLiveValidation"
    }
}
