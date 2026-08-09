package me.ash.reader.ui.page.settings.website

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
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiGeneratedRulePreview
import me.ash.reader.infrastructure.ai.AiRuleGenerationService
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

data class WebsiteRulesUiState(
    val rules: List<WebsiteRule> = emptyList(),
    val isLoading: Boolean = false,
    val aiGenerating: Boolean = false,
    val aiPreview: AiGeneratedRulePreview? = null,
    val aiError: String? = null,
)

@HiltViewModel
class WebsiteRulesViewModel @Inject constructor(
    private val repository: WebsiteRuleRepository,
    private val websiteHelper: WebsiteHelper,
    private val aiRuleGenerationService: AiRuleGenerationService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WebsiteRulesUiState())
    val uiState: StateFlow<WebsiteRulesUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _uiState.update {
            it.copy(
                rules = repository.listRules().filterNot { rule ->
                    rule.id == INTERNAL_ITHOME_RULE_ID
                }
            )
        }
    }

    /** 根据目标列表页生成 AI 候选规则；服务层会先真实抓取并完成本地试跑验证。 */
    fun generateAiRule(url: String) {
        if (_uiState.value.aiGenerating) return
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(aiGenerating = true, aiPreview = null, aiError = null) }
            val result = runCatching { aiRuleGenerationService.generateWebsiteRule(url) }
            withContext(mainDispatcher) {
                _uiState.update {
                    it.copy(
                        aiGenerating = false,
                        aiPreview = result.getOrNull(),
                        aiError = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    /** 用户确认后才把已经通过本地验证的 AI 候选写入规则文件。 */
    fun saveAiPreview() {
        val preview = _uiState.value.aiPreview ?: return
        runCatching { aiRuleGenerationService.save(preview) }
            .onSuccess {
                _uiState.update { it.copy(aiPreview = null, aiError = null) }
                reload()
            }
            .onFailure { error ->
                _uiState.update { it.copy(aiError = error.message ?: "AI 规则保存失败") }
            }
    }

    fun dismissAiPreview() {
        _uiState.update { it.copy(aiPreview = null) }
    }

    fun clearAiError() {
        _uiState.update { it.copy(aiError = null) }
    }

    fun importRules(bytes: ByteArray, callback: (Result<Int>) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching { repository.importRules(String(bytes, Charsets.UTF_8)) }
            withContext(mainDispatcher) {
                reload()
                callback(result)
            }
        }
    }

    fun exportRules(callback: (String) -> Unit) = callback(repository.exportRules())

    fun exportTemplate(callback: (String) -> Unit) = callback(repository.exportTemplate())

    fun setEnabled(rule: WebsiteRule, enabled: Boolean) {
        repository.setEnabled(rule.id, enabled)
        reload()
    }

    fun delete(rule: WebsiteRule) {
        repository.deleteRule(rule.id)
        reload()
    }

    fun test(url: String, callback: (Result<Int>) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            val result = runCatching { websiteHelper.testRule(url) }
            withContext(mainDispatcher) {
                _uiState.update { it.copy(isLoading = false) }
                callback(result)
            }
        }
    }

    private companion object {
        const val INTERNAL_ITHOME_RULE_ID = "ithome-home"
    }
}
