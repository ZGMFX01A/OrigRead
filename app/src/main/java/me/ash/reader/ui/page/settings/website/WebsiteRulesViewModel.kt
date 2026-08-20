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
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiRuleGenerationService
import me.ash.reader.infrastructure.ai.AiRuleGenerationProgress
import me.ash.reader.infrastructure.ai.AiRuleGenerationStage
import me.ash.reader.infrastructure.ai.AiSettings
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.AiWebsiteDynamicRetryException
import me.ash.reader.infrastructure.ai.AiWebsiteGenerationMode
import me.ash.reader.infrastructure.ai.aiRuleGenerationUserMessage
import me.ash.reader.infrastructure.ai.resolvedDefaultModel
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

data class WebsiteRulesUiState(
    val rules: List<WebsiteRule> = emptyList(),
    val isLoading: Boolean = false,
    val aiGenerating: Boolean = false,
    val aiProgress: AiRuleGenerationProgress? = null,
    val aiPreview: AiGeneratedRulePreview? = null,
    val aiError: String? = null,
    val aiCanRetryWithDynamicRendering: Boolean = false,
    val aiTargetUrl: String? = null,
    val aiNotice: String? = null,
    val aiSettings: AiSettings = AiSettings(),
    val selectedAiProviderId: String = aiSettings.defaultProviderId,
    val selectedAiModel: String = aiSettings.defaultProvider()?.resolvedDefaultModel().orEmpty(),
)

@HiltViewModel
class WebsiteRulesViewModel @Inject constructor(
    private val repository: WebsiteRuleRepository,
    private val websiteHelper: WebsiteHelper,
    private val aiRuleGenerationService: AiRuleGenerationService,
    private val aiSettingsRepository: AiSettingsRepository,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WebsiteRulesUiState())
    val uiState: StateFlow<WebsiteRulesUiState> = _uiState.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            aiSettingsRepository.settings.collect { settings ->
                _uiState.update { state ->
                    val providerId =
                        state.selectedAiProviderId.takeIf { id -> settings.providers.any { it.id == id } }
                            ?: settings.defaultProviderId
                    val provider = settings.providers.firstOrNull { it.id == providerId }
                    state.copy(
                        aiSettings = settings,
                        selectedAiProviderId = providerId,
                        selectedAiModel =
                            when {
                                providerId != state.selectedAiProviderId -> provider?.resolvedDefaultModel().orEmpty()
                                state.selectedAiModel.isBlank() -> provider?.resolvedDefaultModel().orEmpty()
                                else -> state.selectedAiModel
                            },
                    )
                }
            }
        }
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
        generateAiRule(url, AiWebsiteGenerationMode.STATIC)
    }

    /** 用户确认后再使用浏览器渲染一次，不影响普通静态生成和后台同步。 */
    fun retryAiRuleWithDynamicRendering() {
        val url = _uiState.value.aiTargetUrl ?: return
        // 先清掉失败弹窗里的旧错误，避免 Compose 在重新启动任务前把生成对话框立即关掉。
        _uiState.update { it.copy(aiError = null, aiCanRetryWithDynamicRendering = false) }
        generateAiRule(url, AiWebsiteGenerationMode.DYNAMIC)
    }

    private fun generateAiRule(url: String, renderMode: AiWebsiteGenerationMode) {
        if (_uiState.value.aiGenerating) return
        val state = _uiState.value
        viewModelScope.launch(ioDispatcher) {
            _uiState.update {
                it.copy(
                    aiGenerating = true,
                    aiProgress = AiRuleGenerationProgress(AiRuleGenerationStage.PREPARING),
                    aiPreview = null,
                    aiError = null,
                    aiCanRetryWithDynamicRendering = false,
                    aiTargetUrl = url,
                    aiNotice = null,
                )
            }
            val result =
                runCatching {
                    aiRuleGenerationService.generateWebsiteRule(
                        url = url,
                        providerId = state.selectedAiProviderId,
                        modelOverride = state.selectedAiModel,
                        renderMode = renderMode,
                        onProgress = { progress -> _uiState.update { it.copy(aiProgress = progress) } },
                    )
                }
            withContext(mainDispatcher) {
                val error = result.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        aiGenerating = false,
                        aiPreview = result.getOrNull(),
                        aiProgress =
                            if (error == null) {
                                AiRuleGenerationProgress(
                                    AiRuleGenerationStage.COMPLETED,
                                    result.getOrNull()?.attempts ?: 1,
                                )
                            } else {
                                AiRuleGenerationProgress(
                                    AiRuleGenerationStage.FAILED,
                                    detail = error?.let(::aiRuleGenerationUserMessage),
                                )
                            },
                        aiError = error?.let(::aiRuleGenerationUserMessage),
                        aiCanRetryWithDynamicRendering = error is AiWebsiteDynamicRetryException,
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
                _uiState.update {
                    it.copy(
                        aiPreview = null,
                        aiError = null,
                        aiCanRetryWithDynamicRendering = false,
                        aiNotice = "规则已保存：${preview.name}",
                    )
                }
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
        _uiState.update { it.copy(aiError = null, aiCanRetryWithDynamicRendering = false) }
    }

    fun clearAiNotice() {
        _uiState.update { it.copy(aiNotice = null) }
    }

    fun selectAiProvider(providerId: String) {
        val provider = _uiState.value.aiSettings.providers.firstOrNull { it.id == providerId } ?: return
        _uiState.update {
            it.copy(
                selectedAiProviderId = providerId,
                selectedAiModel = provider.resolvedDefaultModel().orEmpty(),
            )
        }
    }

    fun setAiModel(model: String) {
        _uiState.update { it.copy(selectedAiModel = model) }
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
