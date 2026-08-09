package me.ash.reader.ui.page.home.feeds.drawer.feed

import androidx.compose.material.ExperimentalMaterialApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.service.RssService
import me.ash.reader.R
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.infrastructure.website.WebsitePageTooComplexException
import me.ash.reader.infrastructure.website.WebsiteParseCandidate

@OptIn(ExperimentalMaterialApi::class)
@HiltViewModel
class FeedOptionViewModel
@Inject
constructor(
    val rssService: RssService,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val rssHelper: RssHelper,
    private val feedDao: FeedDao,
    private val websiteHelper: WebsiteHelper,
    private val articleFilterRepository: ArticleFilterRepository,
    private val androidStringsHelper: AndroidStringsHelper,
) : ViewModel() {

    private val _feedOptionUiState = MutableStateFlow(FeedOptionUiState())
    val feedOptionUiState: StateFlow<FeedOptionUiState> = _feedOptionUiState.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            rssService.flow().collectLatest {
                it.pullGroups().collectLatest { groups ->
                    _feedOptionUiState.update { it.copy(groups = groups) }
                }
            }
        }
    }

    fun showSourceFilterDialog() {
        val feed = _feedOptionUiState.value.feed ?: return
        _feedOptionUiState.update {
            it.copy(
                sourceFilterDialogVisible = true,
                sourceFilterRules = articleFilterRepository.getByFeed(feed.id),
                sourceFilterError = null,
            )
        }
    }

    fun hideSourceFilterDialog() {
        _feedOptionUiState.update { it.copy(sourceFilterDialogVisible = false, sourceFilterError = null) }
    }

    fun addSourceFilter(pattern: String, type: ArticleFilterRuleType): Boolean {
        val feed = _feedOptionUiState.value.feed ?: return false
        return runCatching {
            articleFilterRepository.add(pattern, feed.id, feed.name, type)
            _feedOptionUiState.update {
                it.copy(
                    sourceFilterRules = articleFilterRepository.getByFeed(feed.id),
                    sourceFilterError = null,
                )
            }
        }.onFailure { error ->
            _feedOptionUiState.update { it.copy(sourceFilterError = error.message) }
        }.isSuccess
    }

    fun setSourceFilterEnabled(rule: ArticleFilterRule, enabled: Boolean) {
        articleFilterRepository.setEnabled(rule, enabled)
        showSourceFilterDialog()
    }

    fun deleteSourceFilter(rule: ArticleFilterRule) {
        articleFilterRepository.delete(rule)
        showSourceFilterDialog()
    }

    fun hideWebsiteParserDialog() {
        _feedOptionUiState.update { it.copy(websiteParserDialogVisible = false) }
    }

    suspend fun fetchFeed(feedId: String) {
        val feed = rssService.get().findFeedById(feedId)
        val preference = feed?.takeIf { it.sourceType == SourceType.WEBSITE }
            ?.let { websiteHelper.getParsePreference(it.id) }
        _feedOptionUiState.update {
            it.copy(
                feed = feed,
                selectedGroupId = feed?.groupId ?: "",
                preferredWebsiteRuleId = preference?.preferredRuleId,
                preferredWebsiteRuleName =
                    preference?.preferredRuleName
                        ?: websiteHelper.getRuleName(preference?.preferredRuleId),
                lastSelectedWebsiteRuleId = preference?.lastSelectedRuleId,
                sourceFilterRules = feed?.let { articleFilterRepository.getByFeed(it.id) }.orEmpty(),
            )
        }
    }

    /** 在当前来源设置抽屉内展示加载状态并后台评估候选，避免创建独立 Dialog Window。 */
    fun showWebsiteParserDialog() {
        val feed = _feedOptionUiState.value.feed ?: return
        if (feed.sourceType != SourceType.WEBSITE) return
        _feedOptionUiState.update {
            it.copy(
                websiteParserDialogVisible = true,
                websiteParserLoading = true,
                websiteParserError = null,
                websiteCandidates = emptyList(),
            )
        }
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching { websiteHelper.evaluateCandidates(feed) }
            withContext(mainDispatcher) {
                val error = result.exceptionOrNull()
                _feedOptionUiState.update {
                    it.copy(
                        websiteParserLoading = false,
                        websiteCandidates = result.getOrDefault(emptyList()),
                        websiteParserError =
                            if (error is WebsitePageTooComplexException) {
                                androidStringsHelper.getString(R.string.website_parser_page_too_complex)
                            } else {
                                error?.message
                            },
                    )
                }
            }
        }
    }

    /** 保存来源级规则偏好并触发同步；null 表示恢复自动选择。 */
    fun selectWebsiteRule(ruleId: String?) {
        val feed = _feedOptionUiState.value.feed ?: return
        val selectedRuleName =
            ruleId?.let { selectedId ->
                _feedOptionUiState.value.websiteCandidates
                    .firstOrNull { it.rule.id == selectedId }
                    ?.rule
                    ?.name
                    ?: websiteHelper.getRuleName(selectedId)
            }
        websiteHelper.setPreferredRule(feed.id, ruleId, selectedRuleName)
        _feedOptionUiState.update {
            it.copy(
                preferredWebsiteRuleId = ruleId,
                preferredWebsiteRuleName = selectedRuleName,
                websiteParserDialogVisible = false,
            )
        }
        applicationScope.launch(ioDispatcher) {
            rssService.get().doSyncOneTime()
        }
    }

    fun showNewGroupDialog() {
        _feedOptionUiState.update { it.copy(newGroupDialogVisible = true, newGroupContent = "") }
    }

    fun hideNewGroupDialog() {
        _feedOptionUiState.update { it.copy(newGroupDialogVisible = false, newGroupContent = "") }
    }

    fun inputNewGroup(content: String) {
        _feedOptionUiState.update { it.copy(newGroupContent = content) }
    }

    fun addNewGroup() {
        if (_feedOptionUiState.value.newGroupContent.isNotBlank()) {
            applicationScope.launch {
                selectedGroup(
                    rssService
                        .get()
                        .addGroup(
                            destFeed = _feedOptionUiState.value.feed,
                            newGroupName = _feedOptionUiState.value.newGroupContent,
                        )
                )
                hideNewGroupDialog()
            }
        }
    }

    fun selectedGroup(groupId: String) {
        applicationScope.launch(ioDispatcher) {
            _feedOptionUiState.value.feed?.let {
                rssService
                    .get()
                    .moveFeed(originGroupId = it.groupId, feed = it.copy(groupId = groupId))
                fetchFeed(it.id)
            }
        }
    }

    fun changeParseFullContentPreset() {
        viewModelScope.launch(ioDispatcher) {
            _feedOptionUiState.value.feed?.let {
                val isFullContent = !it.isFullContent
                val isBrowser = if (isFullContent) false else it.isBrowser
                rssService
                    .get()
                    .updateFeed(it.copy(isFullContent = isFullContent, isBrowser = isBrowser))
                fetchFeed(it.id)
            }
        }
    }

    fun changeOpenInBrowserPreset() {
        viewModelScope.launch(ioDispatcher) {
            _feedOptionUiState.value.feed?.let {
                val isBrowser = !it.isBrowser
                val isFullContent = if (isBrowser) false else it.isFullContent
                rssService
                    .get()
                    .updateFeed(it.copy(isBrowser = isBrowser, isFullContent = isFullContent))
                fetchFeed(it.id)
            }
        }
    }

    fun changeAllowNotificationPreset() {
        viewModelScope.launch(ioDispatcher) {
            _feedOptionUiState.value.feed?.let {
                rssService.get().updateFeed(it.copy(isNotification = !it.isNotification))
                fetchFeed(it.id)
            }
        }
    }

    fun delete(callback: () -> Unit = {}) {
        _feedOptionUiState.value.feed?.let {
            applicationScope.launch(ioDispatcher) {
                articleFilterRepository.deleteByFeed(it.id)
                rssService.get().deleteFeed(it)
                withContext(mainDispatcher) { callback() }
            }
        }
    }

    fun hideDeleteDialog() {
        _feedOptionUiState.update { it.copy(deleteDialogVisible = false) }
    }

    fun showDeleteDialog() {
        _feedOptionUiState.update { it.copy(deleteDialogVisible = true) }
    }

    fun showClearDialog() {
        _feedOptionUiState.update { it.copy(clearDialogVisible = true) }
    }

    fun hideClearDialog() {
        _feedOptionUiState.update { it.copy(clearDialogVisible = false) }
    }

    fun clearFeed(callback: () -> Unit = {}) {
        _feedOptionUiState.value.feed?.let {
            viewModelScope.launch(ioDispatcher) {
                rssService.get().deleteArticles(feed = it)
                withContext(mainDispatcher) { callback() }
            }
        }
    }

    fun renameFeed() {
        _feedOptionUiState.value.feed?.let {
            applicationScope.launch {
                rssService.get().renameFeed(it.copy(name = _feedOptionUiState.value.newName))
                _feedOptionUiState.update { it.copy(renameDialogVisible = false) }
            }
        }
    }

    fun showRenameDialog() {
        _feedOptionUiState.update {
            it.copy(renameDialogVisible = true, newName = _feedOptionUiState.value.feed?.name ?: "")
        }
    }

    fun hideRenameDialog() {
        _feedOptionUiState.update { it.copy(renameDialogVisible = false, newName = "") }
    }

    fun inputNewName(content: String) {
        _feedOptionUiState.update { it.copy(newName = content) }
    }

    fun showFeedUrlDialog() {
        _feedOptionUiState.update {
            it.copy(
                changeUrlDialogVisible = true,
                newUrl = _feedOptionUiState.value.feed?.url ?: "",
            )
        }
    }

    fun hideFeedUrlDialog() {
        _feedOptionUiState.update { it.copy(changeUrlDialogVisible = false, newUrl = "") }
    }

    fun inputNewUrl(content: String) {
        _feedOptionUiState.update { it.copy(newUrl = content) }
    }

    fun changeFeedUrl() {
        _feedOptionUiState.value.feed?.let {
            applicationScope.launch {
                rssService.get().changeFeedUrl(it.copy(url = _feedOptionUiState.value.newUrl))
                _feedOptionUiState.update { it.copy(changeUrlDialogVisible = false) }
            }
        }
    }

    fun reloadIcon() {
        _feedOptionUiState.value.feed?.let { feed ->
            viewModelScope.launch(ioDispatcher) {
                val icon = rssHelper.queryRssIconLink(feed.url) ?: return@launch
                feedDao.update(feed.copy(icon = icon))
                fetchFeed(feed.id)
            }
        }
    }
}

data class FeedOptionUiState(
    val feed: Feed? = null,
    val selectedGroupId: String = "",
    val newGroupContent: String = "",
    val newGroupDialogVisible: Boolean = false,
    val groups: List<Group> = emptyList(),
    val deleteDialogVisible: Boolean = false,
    val clearDialogVisible: Boolean = false,
    val newName: String = "",
    val renameDialogVisible: Boolean = false,
    val newUrl: String = "",
    val changeUrlDialogVisible: Boolean = false,
    val websiteParserDialogVisible: Boolean = false,
    val websiteParserLoading: Boolean = false,
    val websiteParserError: String? = null,
    val websiteCandidates: List<WebsiteParseCandidate> = emptyList(),
    val preferredWebsiteRuleId: String? = null,
    val preferredWebsiteRuleName: String? = null,
    val lastSelectedWebsiteRuleId: String? = null,
    val sourceFilterDialogVisible: Boolean = false,
    val sourceFilterRules: List<ArticleFilterRule> = emptyList(),
    val sourceFilterError: String? = null,
)
