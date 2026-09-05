package me.ash.reader.ui.page.home.feeds.subscribe

import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.discovery.FeedDiscoveryCatalog
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.json.JsonSourceProbeResult
import me.ash.reader.infrastructure.rss.DiscoveredFeed
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.website.WebsiteHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SubscribePipelineUnitTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var accountService: AccountService
    private lateinit var rssRepository: AbstractRssRepository
    private lateinit var rssService: RssService
    private lateinit var rssHubSettingsRepository: RssHubSettingsRepository
    private lateinit var opmlService: OpmlService
    private lateinit var rssHelper: RssHelper
    private lateinit var rssHubResolver: RssHubResolver
    private lateinit var websiteHelper: WebsiteHelper
    private lateinit var jsonSourceHelper: JsonSourceHelper
    private lateinit var feedDiscoveryCatalog: FeedDiscoveryCatalog
    private lateinit var androidStringsHelper: AndroidStringsHelper

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        accountService = mock()
        val currentAccount = Account(1, "Local", AccountType.Local)
        whenever(accountService.getCurrentAccount()).thenReturn(currentAccount)
        whenever(accountService.currentAccountFlow).thenReturn(MutableStateFlow(currentAccount))

        rssRepository = mock()
        val groups = mutableListOf(Group("group_1", "Default Group", 1))
        whenever(rssRepository.pullGroups()).thenReturn(kotlinx.coroutines.flow.flowOf(groups))
        runBlocking {
            whenever(rssRepository.isFeedExist(any())).thenReturn(false)
        }
        rssService = mock()
        whenever(rssService.get()).thenReturn(rssRepository)

        rssHubSettingsRepository = mock()
        whenever(rssHubSettingsRepository.current()).thenReturn(RssHubSettings())

        opmlService = mock()
        rssHelper = mock()
        rssHubResolver = mock()
        websiteHelper = mock()
        jsonSourceHelper = mock()
        feedDiscoveryCatalog = mock()
        whenever(feedDiscoveryCatalog.matchUrl(any())).thenReturn(me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch())
        androidStringsHelper = mock {
            on { getString(any()) } doReturn "Localized Notice"
            on { getString(any(), any()) } doReturn "Localized Notice"
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SubscribeViewModel {
        val vm =
            SubscribeViewModel(
                opmlService = opmlService,
                rssService = rssService,
                rssHelper = rssHelper,
                rssHubResolver = rssHubResolver,
                rssHubSettingsRepository = rssHubSettingsRepository,
                websiteHelper = websiteHelper,
                jsonSourceHelper = jsonSourceHelper,
                feedDiscoveryCatalog = feedDiscoveryCatalog,
                androidStringsHelper = androidStringsHelper,
                applicationScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                accountService = accountService,
            )
        testDispatcher.scheduler.runCurrent()
        return vm
    }

    private fun createFeed(title: String = "Test Feed"): SyndFeed =
        SyndFeedImpl().apply {
            this.title = title
            link = "https://example.com"
            entries =
                listOf(
                    SyndEntryImpl().apply {
                        this.title = "Sample Article"
                        link = "https://example.com/article/1"
                        publishedDate = Date()
                    }
                )
        }

    /**
     * P1 核心验证：通用网页在未发现原生 RSS 但同时存在 JSON 来源与 RSSHub 时，
     * 必须优先探测并命中 JSON，流水线在 JSON 处直接短路，绝不调用 RSSHub 探测。
     */
    @Test
    fun `generic page resolves to json and short-circuits without probing rsshub`() = runBlocking {
        val pageUrl = "https://example.com/blog"
        val feed = createFeed("JSON Posts")

        // 原生 RSS 探测失败
        whenever(rssHelper.discoverFeed(any())).thenThrow(IOException("No RSS"))

        // JSON 探测命中
        whenever(jsonSourceHelper.probe(any())).thenReturn(
            JsonSourceProbeResult(
                rule = mock(),
                endpointUrl = "https://example.com/wp-json/wp/v2/posts",
                feed = feed,
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, pageUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        // 验证 RSSHub 和 Website 绝不被探测（已被 JSON 提前短路）
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)

        val state = viewModel.subscribeState.value
        assertTrue("State must be Configure, was $state", state is SubscribeState.Configure)
        val configure = state as SubscribeState.Configure
        assertEquals(SourceType.JSON, configure.sourceType)
        assertEquals("https://example.com/wp-json/wp/v2/posts", configure.feedLink)
        assertEquals(SourceCandidateKind.JSON, configure.candidates.first().kind)
    }

    /**
     * 原生 RSS 优先短路验证：通用网页发现原生 RSS 时，立即在 RSS 处短路，
     * 绝不执行 JSON、RSSHub 或网页探测。
     */
    @Test
    fun `generic page with confirmed direct rss short-circuits before json and rsshub`() = runBlocking {
        val pageUrl = "https://example.com/news"
        val feed = createFeed("Direct RSS")

        whenever(rssHelper.discoverFeed(any())).thenReturn(
            DiscoveredFeed(
                feedUrl = "https://example.com/feed.xml",
                feed = feed,
                discoveredFromPage = false,
                etag = "etag-123",
                lastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, pageUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        // 验证 JSON、RSSHub 和 Website 均被短路，绝不产生交互
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)

        val state = viewModel.subscribeState.value
        assertTrue("State must be Configure", state is SubscribeState.Configure)
        val configure = state as SubscribeState.Configure
        assertEquals(SourceType.RSS, configure.sourceType)
        assertEquals("https://example.com/feed.xml", configure.feedLink)
        assertEquals(SourceCandidateKind.RSS_DIRECT, configure.candidates.first().kind)
    }

    /**
     * P2 核心验证：明确的 RSSHub 实例 Feed 必须调用 parseFeedDirect，严禁调用 discoverFeed，
     * 且初始 stage 为 CHECKING_RSSHUB。
     */
    @Test
    fun `explicit rsshub endpoint uses parseFeedDirect without html discovery and sets stage checking rsshub`() = runBlocking {
        val rssHubUrl = "https://rsshub.app/bilibili/user/video/2267573"
        val feed = createFeed("Bilibili RSSHub")

        whenever(rssHelper.parseFeedDirect(any())).thenReturn(feed)

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, rssHubUrl) }

        val emittedStates = mutableListOf<SubscribeState>()
        val collectJob = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.subscribeState.collect { emittedStates.add(it) }
        }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        collectJob.cancel()

        // 验证初始状态为 Fetching 且 stage 对齐为 CHECKING_RSSHUB
        val fetchingState = emittedStates.filterIsInstance<SubscribeState.Fetching>().firstOrNull()
        assertNotNull("Should transition through Fetching state", fetchingState)
        assertEquals(SearchStage.CHECKING_RSSHUB, fetchingState!!.stage)

        // 验证 JSON、RSSHub 解析器与网页爬虫绝不产生交互
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)

        val state = viewModel.subscribeState.value
        assertTrue("State must be Configure, was $state", state is SubscribeState.Configure)
        val configure = state as SubscribeState.Configure
        assertEquals(SourceType.RSS, configure.sourceType)
        assertEquals(SourceCandidateKind.RSSHUB, configure.candidates.first().kind)
    }

    /** 远端账号遇到已知 RSSHub route 时只能把它当普通 RSS，不能生成 Local-only RSSHub 候选。 */
    @Test
    fun `known rsshub route becomes direct rss on non local account`() = runBlocking {
        val feverAccount = Account(2, "Fever Server", AccountType.Fever)
        whenever(accountService.getCurrentAccount()).thenReturn(feverAccount)
        whenever(accountService.currentAccountFlow).thenReturn(MutableStateFlow(feverAccount))

        val rssHubUrl = "https://rsshub.app/bilibili/user/video/2267573"
        whenever(rssHelper.parseFeedDirect(any())).thenReturn(createFeed("RSSHub as plain RSS"))

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, rssHubUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(websiteHelper)
        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(SourceType.RSS, state.sourceType)
        assertEquals(SourceCandidateKind.RSS_DIRECT, state.candidates.first().kind)
    }

    /** RSS-like URL 只影响顺序；RSS 解析失败后必须继续 JSON，并在 JSON 证明成功时短路。 */
    @Test
    fun `rss hint falls back to json when rss proof fails`() = runBlocking {
        val rssLikeUrl = "https://example.com/api/feed.xml"
        whenever(rssHelper.discoverFeed(any())).thenThrow(IOException("Not actually RSS"))
        whenever(jsonSourceHelper.probe(any())).thenReturn(
            JsonSourceProbeResult(
                rule = mock(),
                endpointUrl = "https://example.com/api/posts",
                feed = createFeed("JSON behind RSS-like URL"),
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, rssLikeUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)
        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(SourceType.JSON, state.sourceType)
        assertEquals(SourceCandidateKind.JSON, state.candidates.first().kind)
    }

    /** 弱 /feed 与 /api/ 冲突时优先 JSON 只是调度优化；JSON 证明成功后无需发起 RSS 探测。 */
    @Test
    fun `api feed conflict tries json first and short circuits on proof`() = runBlocking {
        val ambiguousUrl = "https://example.com/api/v1/feed"
        whenever(jsonSourceHelper.probe(any())).thenReturn(
            JsonSourceProbeResult(
                rule = mock(),
                endpointUrl = ambiguousUrl,
                feed = createFeed("JSON API Feed"),
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, ambiguousUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(rssHelper)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)
        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(SourceType.JSON, state.sourceType)
        assertEquals(SourceCandidateKind.JSON, state.candidates.first().kind)
    }

    /** JSON-like URL 只影响顺序；JSON 解析失败后必须继续 RSS，并在 RSS 证明成功时短路。 */
    @Test
    fun `json hint falls back to rss when json proof fails`() = runBlocking {
        val jsonLikeUrl = "https://example.com/posts.json"
        whenever(jsonSourceHelper.probe(any())).thenReturn(null)
        whenever(rssHelper.discoverFeed(any())).thenReturn(
            DiscoveredFeed(
                feedUrl = jsonLikeUrl,
                feed = createFeed("RSS behind JSON-like URL"),
                discoveredFromPage = false,
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, jsonLikeUrl) }

        val emittedStates = mutableListOf<SubscribeState>()
        val collectJob = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.subscribeState.collect { emittedStates.add(it) }
        }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        collectJob.cancel()

        val fetchingState = emittedStates.filterIsInstance<SubscribeState.Fetching>().firstOrNull()
        assertNotNull("Should transition through Fetching state", fetchingState)
        assertEquals(SearchStage.CHECKING_JSON, fetchingState!!.stage)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)

        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(SourceType.RSS, state.sourceType)
        assertEquals(SourceCandidateKind.RSS_DIRECT, state.candidates.first().kind)
    }

    /**
     * 非 Local 账号验证：非本地账号在 RSS 探测失败后直接结算，不探测 JSON 或 RSSHub。
     */
    @Test
    fun `non-local account skips json and rsshub entirely when rss fails`() = runBlocking {
        val feverAccount = Account(2, "Fever Server", AccountType.Fever)
        whenever(accountService.getCurrentAccount()).thenReturn(feverAccount)
        whenever(accountService.currentAccountFlow).thenReturn(MutableStateFlow(feverAccount))

        val pageUrl = "https://example.com/generic-page"
        whenever(rssHelper.discoverFeed(any())).thenThrow(IOException("No feed found"))

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, pageUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        // 非本地账号绝不进入 JSON 或 RSSHub 探测
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)
    }
}
