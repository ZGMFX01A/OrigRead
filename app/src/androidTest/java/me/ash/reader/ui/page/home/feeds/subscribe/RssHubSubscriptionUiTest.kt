package me.ash.reader.ui.page.home.feeds.subscribe

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ash.reader.R
import me.ash.reader.infrastructure.android.AndroidApp
import me.ash.reader.infrastructure.android.MainActivity
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 从真实 MainActivity 走完整“添加来源 -> RSSHub 探测 -> 确认订阅 -> 持久化”链路。
 *
 * 这里特意不 mock SubscribeViewModel / RssService / Repository：要防的是 UI 层在保存时把
 * preferredInstance 和 lastResolvedInstance 再次混为一谈，或者 rsshub:// 被错误改写。
 */
@RunWith(AndroidJUnit4::class)
class RssHubSubscriptionUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun userReportedRssHubInputsPersistLogicalSubscriptionIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        runBlocking { context.dataStore.put(PreferencesKey.isFirstLaunch, false) }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val repository = RssHubSubscriptionRepository(context, RssHubSettingsRepository(context))
        try {
            subscribeThroughUi(
                input = "https://rsshub.app/zhihu/hot",
                routePath = "/zhihu/hot",
                context = context,
            )
            val zhihuDescriptor = descriptorForRoute(repository, "/zhihu/hot")
            assertEquals("https://rsshub.app/zhihu/hot", zhihuDescriptor.originalInput)
            assertEquals("/zhihu/hot", zhihuDescriptor.routePath)
            assertEquals("https://rsshub.app", zhihuDescriptor.preferredInstance)
            assertTrue(!zhihuDescriptor.lastResolvedInstance.isNullOrBlank())
            assertTrue(!zhihuDescriptor.lastResolvedUrl.isNullOrBlank())
            assertTrue(zhihuDescriptor.lastResolvedUrl!!.endsWith("/zhihu/hot"))

            subscribeThroughUi(
                input = "rsshub://bilibili/user/dynamic/1161918898",
                routePath = "/bilibili/user/dynamic/1161918898",
                context = context,
            )
            val bilibiliDescriptor = descriptorForRoute(repository, "/bilibili/user/dynamic/1161918898")
            assertEquals("rsshub://bilibili/user/dynamic/1161918898", bilibiliDescriptor.originalInput)
            assertEquals("/bilibili/user/dynamic/1161918898", bilibiliDescriptor.routePath)
            assertNull(bilibiliDescriptor.preferredInstance)
            assertTrue(!bilibiliDescriptor.lastResolvedInstance.isNullOrBlank())
            assertTrue(!bilibiliDescriptor.lastResolvedUrl.isNullOrBlank())
            assertTrue(bilibiliDescriptor.lastResolvedUrl!!.endsWith("/bilibili/user/dynamic/1161918898"))

            verifyPersistedFeedRecoversFromDeadInstance(
                app = context.applicationContext as AndroidApp,
                repository = repository,
                routePath = "/zhihu/hot",
            )
        } finally {
            scenario.close()
        }
    }

    private fun subscribeThroughUi(input: String, routePath: String, context: android.content.Context) {
        val subscribe = context.getString(R.string.subscribe)
        val search = context.getString(R.string.search)
        val confirm = context.getString(R.string.confirm_subscribe)

        composeRule.onNodeWithContentDescription(subscribe).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement(input)

        var configured = false
        for (attempt in 0 until 2) {
            composeRule.onNodeWithText(search).performClick()
            configured =
                runCatching {
                    composeRule.waitUntil(timeoutMillis = 25_000) {
                        composeRule.onAllNodesWithText(confirm).fetchSemanticsNodes().isNotEmpty()
                    }
                }.isSuccess
            if (configured) break
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText(search).fetchSemanticsNodes().isNotEmpty()
            }
        }

        assertTrue("RSSHub UI should resolve at least one usable instance for $routePath", configured)
        composeRule.onNodeWithText(confirm).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(confirm).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun descriptorForRoute(
        repository: RssHubSubscriptionRepository,
        routePath: String,
    ) =
        repository.findFeedIdsByRoute(routePath)
            .firstNotNullOfOrNull(repository::descriptor)
            .also { assertNotNull("RSSHub descriptor should be persisted for $routePath", it) }
            ?: error("Missing RSSHub descriptor for $routePath")

    private fun verifyPersistedFeedRecoversFromDeadInstance(
        app: AndroidApp,
        repository: RssHubSubscriptionRepository,
        routePath: String,
    ) = runBlocking {
        val feedDao = app.androidDatabase.feedDao()
        val feedId = repository.findFeedIdsByRoute(routePath).first()
        val persistedFeed = requireNotNull(feedDao.queryById(feedId))
        val persistedDescriptor = requireNotNull(repository.descriptor(feedId))
        val deadInstance = "https://127.0.0.1:1"
        val deadUrl = "$deadInstance$routePath"

        feedDao.update(persistedFeed.copy(url = deadUrl))
        repository.record(
            feedId,
            persistedDescriptor.copy(
                lastResolvedInstance = deadInstance,
                lastResolvedUrl = deadUrl,
            ),
        )

        app.localRssService.sync(
            accountId = persistedFeed.accountId,
            feedId = feedId,
            groupId = null,
        )

        val recoveredFeed = requireNotNull(feedDao.queryById(feedId))
        val recoveredDescriptor = requireNotNull(repository.descriptor(feedId))
        assertNotEquals("RSSHub sync must leave the unreachable instance", deadUrl, recoveredFeed.url)
        assertNotEquals(deadInstance, recoveredDescriptor.lastResolvedInstance)
        assertEquals(routePath, recoveredDescriptor.routePath)
        assertEquals(persistedDescriptor.preferredInstance, recoveredDescriptor.preferredInstance)
        assertEquals(recoveredFeed.url, recoveredDescriptor.lastResolvedUrl)
        assertTrue(recoveredFeed.url.endsWith(routePath))
    }
}
