package me.ash.reader.ui.page.settings.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.infrastructure.translation.DeepLUsage
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationService
import me.ash.reader.infrastructure.translation.TranslationSettings
import me.ash.reader.infrastructure.translation.TranslationSettingsRepository

data class TranslationProviderTestResult(
    val success: Boolean,
    val message: String,
)

data class TranslationSettingsUiState(
    val settings: TranslationSettings = TranslationSettings(),
    val apiKeyDrafts: Map<TranslationProviderType, String> = emptyMap(),
    val hasApiKeys: Map<TranslationProviderType, Boolean> = emptyMap(),
    val testingProvider: TranslationProviderType? = null,
    val testResults: Map<TranslationProviderType, TranslationProviderTestResult> = emptyMap(),
    val deepLUsage: DeepLUsage? = null,
    val deepLUsageError: String? = null,
    val loadingDeepLUsage: Boolean = false,
)

@HiltViewModel
class TranslationSettingsViewModel @Inject constructor(
    private val repository: TranslationSettingsRepository,
    private val translationService: TranslationService,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            TranslationSettingsUiState(
                settings = repository.current(),
                // 仅在设置页内存中保留明文，界面始终通过密码转换显示为黑点。
                apiKeyDrafts =
                    TranslationProviderType.entries.associateWith(repository::getApiKey),
                hasApiKeys =
                    TranslationProviderType.entries.associateWith(repository::hasApiKey),
            )
        )
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        settings = settings,
                        hasApiKeys =
                            TranslationProviderType.entries.associateWith(repository::hasApiKey),
                    )
                }
            }
        }
    }

    /** 手动刷新 DeepL 当前周期字符额度。 */
    fun refreshDeepLUsage() {
        if (_uiState.value.loadingDeepLUsage) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingDeepLUsage = true, deepLUsageError = null) }
            val result = translationService.getDeepLUsage()
            _uiState.update {
                it.copy(
                    loadingDeepLUsage = false,
                    deepLUsage = result.getOrNull() ?: it.deepLUsage,
                    deepLUsageError = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun setDefaultProvider(type: TranslationProviderType) = repository.setDefaultProvider(type)

    fun setTargetLanguage(value: String) = repository.setTargetLanguage(value)

    fun setDisplayMode(mode: TranslationDisplayMode) = repository.setDisplayMode(mode)

    fun setEnabled(type: TranslationProviderType, enabled: Boolean) =
        repository.setProviderEnabled(type, enabled)

    fun setEndpoint(type: TranslationProviderType, value: String) =
        repository.setProviderEndpoint(type, value)

    fun setMicrosoftRegion(value: String) = repository.setMicrosoftRegion(value)

    fun updateApiKeyDraft(type: TranslationProviderType, value: String) {
        _uiState.update { it.copy(apiKeyDrafts = it.apiKeyDrafts + (type to value)) }
    }

    fun saveApiKey(type: TranslationProviderType) {
        val value = _uiState.value.apiKeyDrafts[type].orEmpty()
        repository.setApiKey(type, value)
        _uiState.update {
            it.copy(
                apiKeyDrafts = it.apiKeyDrafts + (type to repository.getApiKey(type)),
                hasApiKeys = it.hasApiKeys + (type to repository.hasApiKey(type)),
            )
        }
    }

    fun testProvider(type: TranslationProviderType, successPrefix: String, failurePrefix: String) {
        if (_uiState.value.testingProvider != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(testingProvider = type, testResults = it.testResults - type)
            }
            val result = translationService.testProvider(type)
            _uiState.update {
                it.copy(
                    testingProvider = null,
                    testResults =
                        it.testResults +
                            (type to
                                result.fold(
                                    onSuccess = { translated ->
                                        TranslationProviderTestResult(
                                            true,
                                            "$successPrefix $translated",
                                        )
                                    },
                                    onFailure = { error ->
                                        TranslationProviderTestResult(
                                            false,
                                            "$failurePrefix ${error.message.orEmpty()}",
                                        )
                                    },
                                )),
                )
            }
        }
    }

    fun restoreDefaults() {
        repository.restoreDefaults()
        _uiState.update {
            TranslationSettingsUiState(settings = repository.current())
        }
    }
}

