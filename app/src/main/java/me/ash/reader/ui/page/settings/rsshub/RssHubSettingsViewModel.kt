package me.ash.reader.ui.page.settings.rsshub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.rsshub.RssHubInstance
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.sync.core.LibrarySyncMutationCapture

data class RssHubSettingsUiState(
    val enabled: Boolean = true,
    val instanceUrl: String = "",
    val instances: List<RssHubInstance> = emptyList(),
    val testingUrl: String? = null,
    val testResults: Map<String, RssHubInstanceTestResult> = emptyMap(),
)

data class RssHubInstanceTestResult(
    val success: Boolean,
    val message: String,
)

@HiltViewModel
class RssHubSettingsViewModel @Inject constructor(
    private val repository: RssHubSettingsRepository,
    private val resolver: RssHubResolver,
    private val accountService: AccountService,
    private val syncMutations: LibrarySyncMutationCapture,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RssHubSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update {
                    it.copy(enabled = settings.enabled, instances = settings.instances)
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            captureSettingsMutation { repository.setEnabled(enabled) }
        }
    }

    fun updateInstanceUrl(value: String) {
        _uiState.update { it.copy(instanceUrl = value) }
    }

    fun setInstanceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            captureSettingsMutation { repository.setInstanceEnabled(id, enabled) }
        }
    }

    fun deleteInstance(id: String) {
        viewModelScope.launch {
            captureSettingsMutation { repository.deleteInstance(id) }
        }
    }

    fun restoreDefault() {
        viewModelScope.launch {
            captureSettingsMutation { repository.restoreDefault() }
            _uiState.update { it.copy(instanceUrl = "", testResults = emptyMap()) }
        }
    }

    /** 测试实例基础连通性；自定义地址测试成功后自动加入列表。 */
    fun testConnection(
        instanceUrl: String,
        addOnSuccess: Boolean,
        successMessage: String,
        failurePrefix: String,
    ) {
        if (_uiState.value.testingUrl != null) return
        val normalized = RssHubSettingsRepository.normalizeInstanceUrl(instanceUrl)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    testingUrl = normalized,
                    testResults = it.testResults - normalized,
                )
            }
            val result = resolver.testConnection(normalized)
            if (result.isSuccess && addOnSuccess) {
                captureSettingsMutation { repository.addInstance(normalized) }
            }
            _uiState.update {
                it.copy(
                    testingUrl = null,
                    testResults =
                        it.testResults +
                            (normalized to
                                result.fold(
                                    onSuccess = {
                                        RssHubInstanceTestResult(true, successMessage)
                                    },
                                    onFailure = { error ->
                                        RssHubInstanceTestResult(
                                            false,
                                            "$failurePrefix${error.message.orEmpty()}",
                                        )
                                    },
                                )),
                )
            }
        }
    }

    private suspend fun <T> captureSettingsMutation(mutate: () -> T): T =
        syncMutations.captureRssHubSettingsMutation(
            accountId = accountService.getCurrentAccountId(),
            readSettings = repository::current,
            replaceSettings = { repository.replaceSyncSettings(it) },
            mutate = mutate,
        )
}
