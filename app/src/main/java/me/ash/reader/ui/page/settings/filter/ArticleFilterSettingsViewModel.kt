package me.ash.reader.ui.page.settings.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.infrastructure.filter.ArticleFilterStats
import me.ash.reader.infrastructure.filter.FilteredArticleRecord

data class ArticleFilterSettingsUiState(
    val rules: List<ArticleFilterRule> = emptyList(),
    val stats: ArticleFilterStats = ArticleFilterStats(),
    val filteredArticles: List<FilteredArticleRecord> = emptyList(),
    val filteredArticlesLoading: Boolean = false,
)

@HiltViewModel
class ArticleFilterSettingsViewModel @Inject constructor(
    private val repository: ArticleFilterRepository,
    private val articleFilterEngine: ArticleFilterEngine,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val accountService: AccountService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ArticleFilterSettingsUiState())
    val uiState: StateFlow<ArticleFilterSettingsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _uiState.update { it.copy(rules = repository.getAll(), stats = repository.getStats()) }
    }

    fun addGlobalRule(pattern: String, type: ArticleFilterRuleType): Result<Unit> =
        runCatching {
            repository.add(pattern, type = type)
            reload()
        }

    fun setEnabled(rule: ArticleFilterRule, enabled: Boolean) {
        repository.setEnabled(rule, enabled)
        reload()
    }

    fun delete(rule: ArticleFilterRule) {
        repository.delete(rule)
        reload()
    }

    fun importRules(bytes: ByteArray): Result<Int> =
        runCatching {
            repository.importRules(String(bytes, Charsets.UTF_8)).also { reload() }
        }

    fun exportRules(): String = repository.exportRules()

    /**
     * 合并两类结果：仍在数据库里但被当前规则隐藏的文章，以及抓取时直接被丢弃的最近记录。
     * 最终只给设置页展示来源和标题。
     */
    fun loadFilteredArticles() {
        _uiState.update { it.copy(filteredArticlesLoading = true) }
        viewModelScope.launch(ioDispatcher) {
            val saved = repository.getFilteredArticles()
            val combined =
                runCatching {
                    val accountId = accountService.getCurrentAccountId()
                    val feeds = feedDao.queryAll(accountId).associateBy { it.id }
                    val hiddenExisting =
                        articleDao.queryAllByAccountId(accountId).mapNotNull { article ->
                            val match = articleFilterEngine.match(article) ?: return@mapNotNull null
                            FilteredArticleRecord(
                                articleId = article.id,
                                feedId = article.feedId,
                                sourceName =
                                    feeds[article.feedId]?.name
                                        ?: match.rule.feedName
                                        ?: article.feedId,
                                title = article.title,
                                matchedRule = match.rule.keyword,
                                filteredAt = article.date.time,
                            )
                        }
                    (saved + hiddenExisting)
                        .distinctBy { it.feedId to it.articleId }
                        .sortedByDescending { it.filteredAt }
                        .take(200)
                }.getOrElse { saved }
            _uiState.update {
                it.copy(
                    filteredArticles = combined,
                    filteredArticlesLoading = false,
                )
            }
        }
    }
}
