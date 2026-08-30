package me.ash.reader.ui.page.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiCapabilityOverrideMode
import me.ash.reader.infrastructure.ai.AiSettings
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.infrastructure.ai.AiSummaryService
import me.ash.reader.infrastructure.ai.normalizeAiModelName

data class AiProviderTestResult(
    val success: Boolean,
    val message: String,
)

data class AiSettingsUiState(
    val settings: AiSettings = AiSettings(),
    val selectedProviderId: String = settings.defaultProviderId,
    val modelDraft: String = settings.defaultProvider()?.defaultModel.orEmpty(),
    val apiKeyDraft: String = "",
    val hasApiKey: Boolean = false,
    val testingProviderId: String? = null,
    val testResult: AiProviderTestResult? = null,
    val loadingModelsProviderId: String? = null,
    val modelLoadError: String? = null,
) {
    val selectedProvider: AiProviderProfile?
        get() = settings.providers.firstOrNull { it.id == selectedProviderId }
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val repository: AiSettingsRepository,
    private val summaryService: AiSummaryService,
) : ViewModel() {
    private val initialSettings = repository.current()
    private val initialProvider = initialSettings.defaultProvider() ?: initialSettings.providers.first()
    private val _uiState =
        MutableStateFlow(
            AiSettingsUiState(
                settings = initialSettings,
                selectedProviderId = initialProvider.id,
                modelDraft = initialProvider.defaultModel,
                // 明文只存在设置页内存中，界面始终使用密码转换显示。
                apiKeyDraft = repository.getApiKey(initialProvider.id),
                hasApiKey = repository.hasApiKey(initialProvider.id),
            )
        )
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update { state ->
                    val selectedId =
                        state.selectedProviderId.takeIf { id -> settings.providers.any { it.id == id } }
                            ?: settings.defaultProvider()?.id
                            ?: settings.providers.first().id
                    state.copy(
                        settings = settings,
                        selectedProviderId = selectedId,
                        hasApiKey = repository.hasApiKey(selectedId),
                    )
                }
            }
        }
    }

    fun setEnabled(value: Boolean) = repository.setEnabled(value)

    fun setOutputLanguage(value: String) = repository.setOutputLanguage(value)

    fun setSummaryLength(value: AiSummaryLength) = repository.setSummaryLength(value)

    fun selectProvider(providerId: String, makeDefault: Boolean = false) {
        val current = repository.current()
        val target = current.providers.firstOrNull { it.id == providerId } ?: return
        if (makeDefault && target.enabled) {
            repository.setDefaultProvider(providerId)
        }
        val settings = repository.current()
        val profile = settings.providers.firstOrNull { it.id == providerId } ?: return
        _uiState.update {
            it.copy(
                settings = settings,
                selectedProviderId = profile.id,
                modelDraft = profile.defaultModel,
                apiKeyDraft = repository.getApiKey(profile.id),
                hasApiKey = repository.hasApiKey(profile.id),
                testResult = null,
                modelLoadError = null,
            )
        }
    }

    fun addProvider() {
        val providerId = repository.addProvider()
        selectProvider(providerId)
    }

    fun removeSelectedProvider() {
        val providerId = _uiState.value.selectedProviderId
        repository.removeProvider(providerId)
        val next = repository.current().defaultProvider() ?: repository.current().providers.first()
        selectProvider(next.id)
    }

    fun setSelectedAsDefault() {
        repository.setDefaultProvider(_uiState.value.selectedProviderId)
    }

    fun setProviderEnabled(value: Boolean) {
        repository.setProviderEnabled(_uiState.value.selectedProviderId, value)
    }

    fun setStreamingCapability(value: AiCapabilityOverrideMode) {
        repository.setProviderStreamingCapability(_uiState.value.selectedProviderId, value)
    }

    fun setToolCallingCapability(value: AiCapabilityOverrideMode) {
        repository.setProviderToolCallingCapability(_uiState.value.selectedProviderId, value)
    }

    fun setReasoningCapability(value: AiCapabilityOverrideMode) {
        repository.setProviderReasoningCapability(_uiState.value.selectedProviderId, value)
    }

    fun setProviderName(value: String) {
        repository.setProviderName(_uiState.value.selectedProviderId, value)
    }

    fun setEndpoint(value: String) {
        repository.setProviderEndpoint(_uiState.value.selectedProviderId, value)
        _uiState.update { it.copy(modelLoadError = null, testResult = null) }
    }

    fun setModel(value: String) {
        val normalized = normalizeAiModelName(value)
        // 模型输入使用独立草稿，避免持久化 Flow 回灌干扰输入法组合状态。
        _uiState.update { it.copy(modelDraft = normalized, testResult = null) }
        repository.setProviderDefaultModel(_uiState.value.selectedProviderId, normalized)
    }

    /** 使用当前选中供应商和设置页里的 Key 草稿自动获取模型列表。 */
    fun loadModels() {
        val state = _uiState.value
        val providerId = state.selectedProviderId
        if (state.loadingModelsProviderId != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(loadingModelsProviderId = providerId, modelLoadError = null)
            }
            val result =
                summaryService.listModels(
                    providerId = providerId,
                    apiKeyOverride = _uiState.value.apiKeyDraft,
                )
            result.getOrNull()?.let { models ->
                repository.setProviderModels(providerId, models)
            }
            _uiState.update {
                it.copy(
                    loadingModelsProviderId = null,
                    modelLoadError = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun selectModel(model: String) {
        val normalized = normalizeAiModelName(model)
        repository.setProviderDefaultModel(_uiState.value.selectedProviderId, normalized)
        _uiState.update {
            it.copy(modelDraft = normalized, modelLoadError = null, testResult = null)
        }
    }

    fun updateApiKeyDraft(value: String) {
        _uiState.update { it.copy(apiKeyDraft = value, modelLoadError = null, testResult = null) }
    }

    fun saveApiKey() {
        val providerId = _uiState.value.selectedProviderId
        repository.setApiKey(providerId, _uiState.value.apiKeyDraft)
        _uiState.update {
            it.copy(
                apiKeyDraft = repository.getApiKey(providerId),
                hasApiKey = repository.hasApiKey(providerId),
            )
        }
    }

    fun testProvider(successText: String, failurePrefix: String) {
        val state = _uiState.value
        val providerId = state.selectedProviderId
        if (state.testingProviderId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(testingProviderId = providerId, testResult = null) }
            val result =
                summaryService.testProvider(
                    providerId = providerId,
                    modelOverride = _uiState.value.modelDraft,
                )
            _uiState.update {
                it.copy(
                    testingProviderId = null,
                    testResult =
                        result.fold(
                            onSuccess = { response ->
                                AiProviderTestResult(true, "$successText：$response")
                            },
                            onFailure = { error ->
                                AiProviderTestResult(
                                    false,
                                    "$failurePrefix ${error.message.orEmpty()}",
                                )
                            },
                        ),
                )
            }
        }
    }

    fun restoreDefaults() {
        repository.restoreDefaults()
        val settings = repository.current()
        val profile = settings.defaultProvider() ?: settings.providers.first()
        _uiState.value =
            AiSettingsUiState(
                settings = settings,
                selectedProviderId = profile.id,
                modelDraft = profile.defaultModel,
            )
    }
}
