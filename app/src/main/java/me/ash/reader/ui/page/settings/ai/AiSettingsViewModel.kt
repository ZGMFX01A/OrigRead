package me.ash.reader.ui.page.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiOutputTokenLimitStyle
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
    @me.ash.reader.infrastructure.di.IODispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {
    private var requestRevision = 0L
    private var modelJob: Job? = null
    private var testJob: Job? = null
    private var credentialsJob: Job? = null
    private var credentialsReady = false
    private val credentialWrites = kotlinx.coroutines.sync.Mutex()
    private val removingProviderIds = mutableSetOf<String>()
    private var credentialResetInFlight = false
    private var credentialResetRevision = 0L

    private fun credentialSaveBlocked(providerId: String): Boolean =
        credentialResetInFlight || providerId in removingProviderIds

    private fun showConfigurationBusy() {
        _uiState.update { it.copy(modelLoadError = "AI 配置正在更新，请稍后重试") }
    }

    private fun invalidateRequests() {
        requestRevision++
        modelJob?.cancel()
        testJob?.cancel()
        _uiState.update { it.copy(loadingModelsProviderId = null, testingProviderId = null) }
    }

    private fun loadCredentials(providerId: String) {
        credentialsJob?.cancel()
        credentialsReady = false
        credentialsJob = viewModelScope.launch {
            val key = try {
                credentialWrites.withLock {
                    withContext(ioDispatcher) { repository.getApiKey(providerId) }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                timber.log.Timber.e(error, "Unable to read AI credentials")
                _uiState.update { it.copy(modelLoadError = error.message ?: "Unable to read credentials") }
                return@launch
            }
            credentialsReady = true
            _uiState.update { state ->
                if (state.selectedProviderId != providerId) state
                else state.copy(apiKeyDraft = key, hasApiKey = key.isNotBlank())
            }
        }
    }

    private val initialSettings = repository.current()
    private val initialProvider = initialSettings.defaultProvider() ?: initialSettings.providers.first()
    private val _uiState =
        MutableStateFlow(
            AiSettingsUiState(
                settings = initialSettings,
                selectedProviderId = initialProvider.id,
                modelDraft = initialProvider.defaultModel,
                // 明文只存在设置页内存中，界面始终使用密码转换显示。
                apiKeyDraft = "",
                hasApiKey = false,
            )
        )
    val uiState = _uiState.asStateFlow()

    init {
        loadCredentials(initialProvider.id)
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val previousProviderId = _uiState.value.selectedProviderId
                _uiState.update { state ->
                    val selectedId =
                        state.selectedProviderId.takeIf { id -> settings.providers.any { it.id == id } }
                            ?: settings.defaultProvider()?.id
                            ?: settings.providers.first().id
                    state.copy(
                        settings = settings,
                        selectedProviderId = selectedId,
                        modelDraft = if (selectedId == state.selectedProviderId) state.modelDraft
                            else settings.providers.first { it.id == selectedId }.defaultModel,
                        apiKeyDraft = if (selectedId == state.selectedProviderId) state.apiKeyDraft else "",
                        hasApiKey = selectedId == state.selectedProviderId && state.hasApiKey,
                    )
                }
                if (_uiState.value.selectedProviderId != previousProviderId) {
                    invalidateRequests()
                    loadCredentials(_uiState.value.selectedProviderId)
                }
            }
        }
    }

    fun setEnabled(value: Boolean) = repository.setEnabled(value)

    fun setOutputLanguage(value: String) = repository.setOutputLanguage(value)

    fun setSummaryLength(value: AiSummaryLength) = repository.setSummaryLength(value)

    fun selectProvider(providerId: String, makeDefault: Boolean = false) {
        invalidateRequests()
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
                apiKeyDraft = "",
                hasApiKey = false,
                testResult = null,
                modelLoadError = null,
            )
        }
        loadCredentials(profile.id)
    }

    fun addProvider() {
        val providerId = repository.addProvider()
        selectProvider(providerId)
    }

    fun removeSelectedProvider() {
        val providerId = _uiState.value.selectedProviderId
        if (credentialSaveBlocked(providerId)) {
            showConfigurationBusy()
            return
        }
        removingProviderIds += providerId
        invalidateRequests()
        viewModelScope.launch {
            try {
                val next = credentialWrites.withLock {
                    withContext(ioDispatcher) {
                        repository.removeProvider(providerId)
                        val settings = repository.current()
                        settings.defaultProvider() ?: settings.providers.first()
                    }
                }
                if (_uiState.value.selectedProviderId == providerId) selectProvider(next.id)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                timber.log.Timber.e(error, "Unable to remove AI provider")
                _uiState.update { it.copy(modelLoadError = error.message ?: "Unable to remove AI provider") }
            } finally {
                removingProviderIds -= providerId
            }
        }
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

    /** 设置 Chat/Summary 共用的输出 token 字段策略。 */
    fun setOutputTokenLimitStyle(value: AiOutputTokenLimitStyle) {
        repository.setProviderOutputTokenLimitStyle(_uiState.value.selectedProviderId, value)
    }

    fun setContextWindowTokens(value: String) {
        value.toIntOrNull()?.let { repository.setProviderContextWindowTokens(_uiState.value.selectedProviderId, it) }
    }

    fun setStrictStreamTermination(value: Boolean) {
        repository.setProviderStrictStreamTermination(_uiState.value.selectedProviderId, value)
    }

    fun setProviderName(value: String) {
        repository.setProviderName(_uiState.value.selectedProviderId, value)
    }

    fun setEndpoint(value: String) {
        invalidateRequests()
        repository.setProviderEndpoint(_uiState.value.selectedProviderId, value)
        _uiState.update { it.copy(modelLoadError = null, testResult = null) }
    }

    fun setModel(value: String) {
        invalidateRequests()
        val normalized = normalizeAiModelName(value)
        // 模型输入使用独立草稿，避免持久化 Flow 回灌干扰输入法组合状态。
        _uiState.update { it.copy(modelDraft = normalized, testResult = null) }
        repository.setProviderDefaultModel(_uiState.value.selectedProviderId, normalized)
    }

    /** 使用当前选中供应商和设置页里的 Key 草稿自动获取模型列表。 */
    fun loadModels() {
        val state = _uiState.value
        val providerId = state.selectedProviderId
        invalidateRequests()
        val revision = requestRevision
        _uiState.update { it.copy(loadingModelsProviderId = providerId, modelLoadError = null) }
        modelJob = viewModelScope.launch {
            credentialsJob?.join()
            if (revision != requestRevision) return@launch
            if (!credentialsReady) {
                _uiState.update { it.copy(loadingModelsProviderId = null) }
                return@launch
            }
            val result =
                summaryService.listModels(
                    providerId = providerId,
                    apiKeyOverride = _uiState.value.apiKeyDraft,
                )
            if (revision != requestRevision) return@launch
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
        invalidateRequests()
        val normalized = normalizeAiModelName(model)
        repository.setProviderDefaultModel(_uiState.value.selectedProviderId, normalized)
        _uiState.update {
            it.copy(modelDraft = normalized, modelLoadError = null, testResult = null)
        }
    }

    fun updateApiKeyDraft(value: String) {
        invalidateRequests()
        credentialsJob?.cancel()
        credentialsReady = true
        _uiState.update { it.copy(apiKeyDraft = value, modelLoadError = null, testResult = null) }
    }

    fun saveApiKey() {
        val providerId = _uiState.value.selectedProviderId
        if (credentialSaveBlocked(providerId)) {
            showConfigurationBusy()
            return
        }
        invalidateRequests()
        val resetRevision = credentialResetRevision
        val key = _uiState.value.apiKeyDraft
        viewModelScope.launch {
            try {
                val saved = credentialWrites.withLock {
                    if (resetRevision != credentialResetRevision || credentialSaveBlocked(providerId)) {
                        false
                    } else {
                        withContext(ioDispatcher) {
                            synchronized(repository) {
                                // Also coordinate with repository consumers outside this ViewModel.
                                if (repository.current().providers.none { it.id == providerId }) false
                                else {
                                    repository.setApiKey(providerId, key)
                                    true
                                }
                            }
                        }
                    }
                }
                if (!saved) {
                    if (_uiState.value.selectedProviderId == providerId) showConfigurationBusy()
                    return@launch
                }
                if (resetRevision == credentialResetRevision && !credentialSaveBlocked(providerId) &&
                    _uiState.value.selectedProviderId == providerId && _uiState.value.apiKeyDraft == key
                ) loadCredentials(providerId)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                timber.log.Timber.e(error, "Unable to save AI credentials")
                if (resetRevision == credentialResetRevision && !credentialSaveBlocked(providerId) &&
                    _uiState.value.selectedProviderId == providerId
                ) {
                    _uiState.update { it.copy(modelLoadError = error.message ?: "Unable to save credentials") }
                }
            }
        }
    }

    fun testProvider(successText: String, failurePrefix: String) {
        val state = _uiState.value
        val providerId = state.selectedProviderId
        invalidateRequests()
        val revision = requestRevision
        _uiState.update { it.copy(testingProviderId = providerId, testResult = null) }
        testJob = viewModelScope.launch {
            credentialWrites.withLock { /* Wait for preceding credential reads/writes. */ }
            if (revision != requestRevision) return@launch
            val result =
                summaryService.testProvider(
                    providerId = providerId,
                    modelOverride = state.modelDraft,
                )
            if (revision != requestRevision) return@launch
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
        if (credentialResetInFlight) {
            showConfigurationBusy()
            return
        }
        credentialResetInFlight = true
        credentialResetRevision++
        invalidateRequests()
        credentialsJob?.cancel()
        viewModelScope.launch {
            try {
                val settings = credentialWrites.withLock {
                    withContext(ioDispatcher) {
                        repository.restoreDefaults()
                        repository.current()
                    }
                }
                val profile = settings.defaultProvider() ?: settings.providers.first()
                _uiState.value = AiSettingsUiState(
                    settings = settings,
                    selectedProviderId = profile.id,
                    modelDraft = profile.defaultModel,
                )
                loadCredentials(profile.id)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                timber.log.Timber.e(error, "Unable to reset AI settings")
                _uiState.update { it.copy(modelLoadError = error.message ?: "Unable to reset AI settings") }
            } finally {
                credentialResetInFlight = false
            }
        }
    }
}
