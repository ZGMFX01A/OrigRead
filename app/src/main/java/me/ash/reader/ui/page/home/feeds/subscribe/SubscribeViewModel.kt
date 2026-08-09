package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.ash.reader.R
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.website.CandidateState
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.ui.ext.formatUrl

@HiltViewModel
class SubscribeViewModel
@Inject
constructor(
    private val opmlService: OpmlService,
    val rssService: RssService,
    private val rssHelper: RssHelper,
    private val rssHubResolver: RssHubResolver,
    private val websiteHelper: WebsiteHelper,
    private val jsonSourceHelper: JsonSourceHelper,
    private val androidStringsHelper: AndroidStringsHelper,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val accountService: AccountService,
) : ViewModel() {

    private val _subscribeUiState = MutableStateFlow(SubscribeUiState())
    val subscribeUiState: StateFlow<SubscribeUiState> = _subscribeUiState.asStateFlow()

    private val _subscribeState: MutableStateFlow<SubscribeState> =
        MutableStateFlow(SubscribeState.Hidden)
    val subscribeState = _subscribeState.asStateFlow()

    val groupsFlow = MutableStateFlow<List<Group>>(emptyList())

    init {
        viewModelScope.launch {
            accountService.currentAccountFlow.collectLatest {
                rssService.get().pullGroups().collect { groupsFlow.value = it }
            }
        }
        viewModelScope.launch {
            groupsFlow.collect { groups ->
                _subscribeState.update {
                    when (it) {
                        is SubscribeState.Configure -> it.copy(groups = groups)
                        else -> it
                    }
                }
            }
        }
    }

    fun reset() {
        cancelSearch()
    }

    fun importFromInputStream(inputStream: InputStream) {
        applicationScope.launch {
            opmlService.saveToDatabase(inputStream)
            rssService.get().doSyncOneTime()
        }
    }

    fun selectedGroup(groupId: String) {
        _subscribeState.update {
            when (it) {
                is SubscribeState.Configure -> it.copy(selectedGroupId = groupId)
                else -> it
            }
        }
    }

    fun addNewGroup() {
        if (_subscribeUiState.value.newGroupContent.isNotBlank()) {
            applicationScope.launch {
                // TODO: How to add a single group without no feeds via Google Reader API?
                selectedGroup(
                    rssService.get().addGroup(null, _subscribeUiState.value.newGroupContent)
                )
                hideNewGroupDialog()
                _subscribeUiState.update { it.copy(newGroupContent = "") }
            }
        }
    }

    fun toggleParseFullContentPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(fullContent = !state.fullContent, browser = false)

                else -> state
            }
        }
    }

    fun toggleOpenInBrowserPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(browser = !state.browser, fullContent = false)

                else -> state
            }
        }
    }

    fun toggleAllowNotificationPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure -> state.copy(notification = !state.notification)
                else -> state
            }
        }
    }

    fun searchFeed() {
        val currentState = _subscribeState.value
        if (currentState !is SubscribeState.Idle) return
        viewModelScope.launch {
            val feedLink = currentState.linkState.text.trim().toString().formatUrl()
            currentState.linkState.edit { this.replace(0, length, feedLink) }

            if (rssService.get().isFeedExist(feedLink)) {
                _subscribeState.value =
                    currentState.copy(
                        errorMessage = androidStringsHelper.getString(R.string.already_subscribed)
                    )
                return@launch
            }
            val groups = groupsFlow.value
            val firstGroupId = groups.firstOrNull()?.id ?: return@launch

            val job =
                viewModelScope.launch {
                    if (
                        accountService.getCurrentAccount().type.id == AccountType.Local.id &&
                            isExplicitJsonEndpoint(feedLink)
                    ) {
                        _subscribeState.value =
                            SubscribeState.Fetching(
                                linkState = currentState.linkState,
                                job = coroutineContext[Job]!!,
                                stage = SearchStage.CHECKING_JSON,
                            )
                        val directJsonResult = runCatching { jsonSourceHelper.probe(feedLink) }
                        val directJsonSource = directJsonResult.getOrNull()
                        if (directJsonSource != null) {
                            applyBestCandidate(
                                candidates =
                                    listOf(
                                        SubscribeCandidateProbe(
                                            feed = directJsonSource.feed,
                                            feedLink = directJsonSource.endpointUrl,
                                            sourceType = SourceType.JSON,
                                            kind = SourceCandidateKind.JSON,
                                        )
                                    ),
                                idleState = currentState,
                                firstGroupId = firstGroupId,
                                lastError = null,
                            )
                            return@launch
                        }

                        // 明确输入 JSON/API 地址时只走 JSON 探测，失败后不得再交给 RSS/网页发现，
                        // 否则会把 JSON 响应错误识别成站点首页并跳转到官方 Feed。
                        _subscribeState.value =
                            currentState.copy(
                                errorMessage =
                                    directJsonResult.exceptionOrNull()?.message
                                        ?: "未能从该地址识别出有效的 JSON 文章列表"
                            )
                        return@launch
                    }

                    val candidates = mutableListOf<SubscribeCandidateProbe>()
                    var lastError: Throwable? = null

                    updateSearchStage(SearchStage.CHECKING_RSS)
                    runCatching { withTimeout(20_000) { rssHelper.discoverFeed(feedLink) } }
                        .onSuccess { discovered ->
                            candidates +=
                                SubscribeCandidateProbe(
                                    feed = discovered.feed,
                                    feedLink = discovered.feedUrl,
                                    sourceType = SourceType.RSS,
                                    kind =
                                        if (discovered.discoveredFromPage) {
                                            SourceCandidateKind.RSS_DISCOVERED
                                        } else {
                                            SourceCandidateKind.RSS_DIRECT
                                        },
                                )
                        }.onFailure { lastError = it }

                    if (accountService.getCurrentAccount().type.id != AccountType.Local.id) {
                        applyBestCandidate(candidates, currentState, firstGroupId, lastError)
                        return@launch
                    }

                    updateSearchStage(SearchStage.CHECKING_RSSHUB)
                    val rssHubResults =
                        runCatching { rssHubResolver.probe(feedLink) }.getOrDefault(emptyList())
                    rssHubResults.filter { it.available }.forEach { result ->
                        candidates +=
                            SubscribeCandidateProbe(
                                feed = requireNotNull(result.feed),
                                feedLink = requireNotNull(result.match.feedUrl),
                                sourceType = SourceType.RSS,
                                kind = SourceCandidateKind.RSSHUB,
                                sourceNotice =
                                    androidStringsHelper.getString(
                                        R.string.rsshub_source_notice,
                                        result.match.route.name,
                                    ),
                            )
                    }
                    val rssHubNotice = rssHubFailureNotice(rssHubResults)

                    updateSearchStage(SearchStage.CHECKING_JSON)
                    runCatching { jsonSourceHelper.probe(feedLink) }
                        .onSuccess { jsonSource ->
                            if (jsonSource != null) {
                                candidates +=
                                    SubscribeCandidateProbe(
                                        feed = jsonSource.feed,
                                        feedLink = jsonSource.endpointUrl,
                                        sourceType = SourceType.JSON,
                                        kind = SourceCandidateKind.JSON,
                                    )
                            }
                        }.onFailure { lastError = it }

                    updateSearchStage(SearchStage.CHECKING_WEBSITE)
                    runCatching { withTimeout(15_000) { websiteHelper.inspect(feedLink) } }
                        .onSuccess { website ->
                            candidates +=
                                SubscribeCandidateProbe(
                                    feed = website,
                                    feedLink = feedLink,
                                    sourceType = SourceType.WEBSITE,
                                    kind = SourceCandidateKind.WEBSITE,
                                    sourceNotice = rssHubNotice,
                                    browser = !websiteHelper.hasRule(feedLink),
                                )
                        }.onFailure { lastError = it }

                    // 只有所有静态来源都未通过统一健康检查时才启动 WebView，
                    // 避免动态渲染增加普通 RSS、JSON 和静态网站的添加耗时。
                    if (SubscribeCandidateSelector.rank(candidates).isEmpty()) {
                        updateSearchStage(SearchStage.CHECKING_DYNAMIC_WEBSITE)
                        runCatching { withTimeout(20_000) { websiteHelper.inspectDynamic(feedLink) } }
                            .onSuccess { website ->
                                candidates +=
                                    SubscribeCandidateProbe(
                                        feed = website,
                                        feedLink = feedLink,
                                        sourceType = SourceType.WEBSITE,
                                        kind = SourceCandidateKind.WEBSITE_DYNAMIC,
                                        sourceNotice =
                                            androidStringsHelper.getString(
                                                R.string.dynamic_website_source_notice
                                            ),
                                        browser = !websiteHelper.hasRule(feedLink),
                                        dynamicRendering = true,
                                    )
                            }.onFailure { lastError = it }
                    }

                    applyBestCandidate(
                        candidates = candidates,
                        idleState = currentState,
                        firstGroupId = firstGroupId,
                        lastError = lastError,
                        fallbackMessage = rssHubNotice,
                    )
                }

            _subscribeState.value =
                SubscribeState.Fetching(
                    linkState = currentState.linkState,
                    job = job,
                    stage = SearchStage.CHECKING_RSS,
                )
        }
    }

    /** 明确 API 地址优先按 JSON 探测，普通首页仍保持 RSS 优先。 */
    private fun isExplicitJsonEndpoint(url: String): Boolean =
        runCatching {
            val path = URI(url).path.orEmpty().lowercase()
            path.contains("/wp-json/") ||
                path.endsWith(".json") ||
                path.startsWith("/api/") ||
                path.contains("/api/")
        }.getOrDefault(false)

    /** 更新统一来源探测的当前阶段。 */
    private fun updateSearchStage(stage: SearchStage) {
        _subscribeState.update { state ->
            if (state is SubscribeState.Fetching) state.copy(stage = stage) else state
        }
    }

    /** 使用统一健康检查选出最高分候选，并转换成订阅配置状态。 */
    private fun applyBestCandidate(
        candidates: List<SubscribeCandidateProbe>,
        idleState: SubscribeState.Idle,
        firstGroupId: String,
        lastError: Throwable?,
        fallbackMessage: String? = null,
    ) {
        val rankedCandidates = SubscribeCandidateSelector.rank(candidates)
        val selected = rankedCandidates.firstOrNull()

        if (selected == null) {
            _subscribeState.value =
                idleState.copy(
                    errorMessage =
                        lastError?.message
                            ?: fallbackMessage
                            ?: androidStringsHelper.getString(R.string.website_request_failed)
                )
            return
        }

        _subscribeState.value =
            SubscribeState.Configure(
                searchedFeed = selected.feed,
                feedLink = selected.feedLink,
                sourcePageUrl = idleState.linkState.text.toString(),
                groups = groupsFlow.value,
                selectedGroupId = firstGroupId,
                sourceType = selected.sourceType,
                sourceNotice = selected.sourceNotice,
                browser = selected.browser,
                candidates = rankedCandidates,
                selectedCandidateId = selected.id,
                dynamicRendering = selected.dynamicRendering,
            )
    }

    /** RSSHub 网络失败仅作为非阻断提示，不影响其他候选参与评分。 */
    private fun rssHubFailureNotice(results: List<me.ash.reader.infrastructure.rsshub.RssHubProbeResult>): String? =
        results.firstNotNullOfOrNull { result ->
            when (result.state) {
                CandidateState.TIMEOUT -> androidStringsHelper.getString(R.string.rsshub_timeout_notice)
                CandidateState.NETWORK_UNAVAILABLE ->
                    androidStringsHelper.getString(R.string.rsshub_network_unavailable_notice)
                CandidateState.NEEDS_INPUT ->
                    androidStringsHelper.getString(
                        R.string.rsshub_missing_parameters_notice,
                        result.match.route.name,
                        result.match.missingParameters.joinToString(),
                    )
                else -> null
            }
        }

    /** 在配置页切换有效来源候选，同时保留分组和订阅偏好。 */
    fun selectSourceCandidate(candidateId: String) {
        _subscribeState.update { state ->
            if (state !is SubscribeState.Configure) return@update state
            val selected = state.candidates.firstOrNull { it.id == candidateId } ?: return@update state

            state.copy(
                searchedFeed = selected.feed,
                feedLink = selected.feedLink,
                sourceType = selected.sourceType,
                sourceNotice = selected.sourceNotice,
                browser = selected.browser,
                dynamicRendering = selected.dynamicRendering,
                selectedCandidateId = selected.id,
            )
        }
    }

    fun cancelSearch() {
        _subscribeState.value.let {
            if (it is SubscribeState.Fetching && it.job.isActive) {
                it.job.cancel()
            }
        }
    }

    fun subscribe() {
        val state = _subscribeState.value
        if (state !is SubscribeState.Configure) return

        applicationScope.launch {
            val searchedFeed = state.searchedFeed
            when (state.sourceType) {
                SourceType.RSS -> {
                    val selectedKind =
                        state.candidates.firstOrNull { it.id == state.selectedCandidateId }?.kind
                    if (selectedKind == SourceCandidateKind.RSSHUB) {
                        rssService.subscribeRssHub(
                            searchedFeed = searchedFeed,
                            feedLink = state.feedLink,
                            sourcePageUrl = state.sourcePageUrl,
                            groupId = state.selectedGroupId,
                            isNotification = state.notification,
                            isFullContent = state.fullContent,
                            isBrowser = state.browser,
                        )
                    } else {
                        rssService.get().subscribe(
                            searchedFeed = searchedFeed,
                            feedLink = state.feedLink,
                            groupId = state.selectedGroupId,
                            isNotification = state.notification,
                            isFullContent = state.fullContent,
                            isBrowser = state.browser,
                        )
                    }
                }

                SourceType.WEBSITE ->
                    rssService.subscribeWebsite(
                        searchedFeed = searchedFeed,
                        feedLink = state.feedLink,
                        groupId = state.selectedGroupId,
                        isNotification = state.notification,
                        isFullContent = state.fullContent,
                        isBrowser = state.browser,
                    ).also { feedId ->
                        websiteHelper.setDynamicRenderingEnabled(feedId, state.dynamicRendering)
                        // 网站来源保存后没有预置文章，需要立即执行一次同步填充列表。
                        rssService.get().doSyncOneTime()
                    }

                SourceType.JSON ->
                    rssService.subscribeJson(
                        searchedFeed = searchedFeed,
                        feedLink = state.feedLink,
                        groupId = state.selectedGroupId,
                        isNotification = state.notification,
                        isFullContent = state.fullContent,
                        isBrowser = state.browser,
                    ).also { rssService.get().doSyncOneTime() }
            }
            hideDrawer()
        }
    }

    fun inputNewGroup(content: String) {
        _subscribeUiState.update { it.copy(newGroupContent = content) }
    }

    fun handleSharedUrlFromIntent(url: String) {
        openFeedUrl(url)
    }

    /** 从内置来源目录进入时复用现有发现、重复校验与订阅配置流程。 */
    fun openFeedFromCatalog(url: String) {
        openFeedUrl(url)
    }

    private fun openFeedUrl(url: String) {
        viewModelScope
            .launch {
                _subscribeState.update { SubscribeState.Idle(linkState = TextFieldState(url)) }
                delay(50)
            }
            .invokeOnCompletion { searchFeed() }
    }

    fun showDrawer() {
        _subscribeState.value =
            SubscribeState.Idle(importFromOpmlEnabled = rssService.get().importSubscription)
    }

    fun hideDrawer() {
        cancelSearch()
        _subscribeState.value = SubscribeState.Hidden
    }

    fun showNewGroupDialog() {
        _subscribeUiState.update { it.copy(newGroupDialogVisible = true) }
    }

    fun hideNewGroupDialog() {
        _subscribeUiState.update { it.copy(newGroupDialogVisible = false) }
    }

    fun showRenameDialog() {
        _subscribeUiState.update { it.copy(renameDialogVisible = true) }
        _subscribeUiState.update { uiState ->
            (_subscribeState.value as? SubscribeState.Configure)?.searchedFeed?.title?.let { title
                ->
                uiState.copy(newName = title)
            } ?: uiState
        }
    }

    fun hideRenameDialog() {
        _subscribeUiState.update { it.copy(renameDialogVisible = false, newName = "") }
    }

    fun inputNewName(content: String) {
        _subscribeUiState.update { it.copy(newName = content) }
    }

    fun renameFeed() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(
                        searchedFeed =
                            state.searchedFeed.apply { title = _subscribeUiState.value.newName }
                    )

                else -> state
            }
        }
    }
}

enum class SearchStage {
    CHECKING_RSS,
    CHECKING_RSSHUB,
    CHECKING_JSON,
    CHECKING_WEBSITE,
    CHECKING_DYNAMIC_WEBSITE,
}

data class SubscribeUiState(
    val newGroupDialogVisible: Boolean = false,
    val newGroupContent: String = "",
    val newName: String = "",
    val renameDialogVisible: Boolean = false,
)

sealed interface SubscribeState {
    object Hidden : SubscribeState

    sealed interface Visible

    sealed interface Input : SubscribeState, Visible {
        val linkState: TextFieldState
    }

    data class Idle(
        override val linkState: TextFieldState = TextFieldState(),
        val importFromOpmlEnabled: Boolean = false,
        val errorMessage: String? = null,
    ) : SubscribeState, Input

    data class Fetching(
        override val linkState: TextFieldState,
        val job: Job,
        val stage: SearchStage,
    ) :
        SubscribeState, Input

    data class Configure(
        val searchedFeed: SyndFeed,
        val feedLink: String,
        val sourcePageUrl: String,
        val groups: List<Group> = emptyList(),
        val notification: Boolean = false,
        val fullContent: Boolean = false,
        val browser: Boolean = false,
        val selectedGroupId: String,
        val sourceType: SourceType = SourceType.RSS,
        val sourceNotice: String? = null,
        val candidates: List<SubscribeSourceCandidate> = emptyList(),
        val selectedCandidateId: String? = null,
        val dynamicRendering: Boolean = false,
    ) : SubscribeState, Visible
}
