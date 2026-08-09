package me.ash.reader.ui.page.settings.filter

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.infrastructure.filter.ArticleFilterStats

data class ArticleFilterSettingsUiState(
    val rules: List<ArticleFilterRule> = emptyList(),
    val stats: ArticleFilterStats = ArticleFilterStats(),
)

@HiltViewModel
class ArticleFilterSettingsViewModel @Inject constructor(
    private val repository: ArticleFilterRepository,
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
}
