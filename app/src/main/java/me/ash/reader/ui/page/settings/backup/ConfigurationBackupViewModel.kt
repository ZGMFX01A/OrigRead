package me.ash.reader.ui.page.settings.backup

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
import me.ash.reader.infrastructure.backup.ConfigurationBackupService
import me.ash.reader.infrastructure.backup.ConfigurationBackupSummary
import me.ash.reader.infrastructure.backup.ConfigurationRestoreResult
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher

data class ConfigurationBackupUiState(
    val isWorking: Boolean = false,
    val pendingBackup: String? = null,
    val pendingSummary: ConfigurationBackupSummary? = null,
)

@HiltViewModel
class ConfigurationBackupViewModel @Inject constructor(
    private val backupService: ConfigurationBackupService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConfigurationBackupUiState())
    val uiState: StateFlow<ConfigurationBackupUiState> = _uiState.asStateFlow()

    /** 生成备份内容。密码只作为本次调用参数使用，不进入 ViewModel 状态。 */
    fun exportBackup(
        includeSecrets: Boolean,
        password: String,
        callback: (Result<ByteArray>) -> Unit,
    ) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch(ioDispatcher) {
            val result =
                runCatching {
                    backupService.exportBackup(includeSecrets, password).toByteArray(Charsets.UTF_8)
                }
            withContext(mainDispatcher) {
                _uiState.update { it.copy(isWorking = false) }
                callback(result)
            }
        }
    }

    /** 读取并校验文件头，成功后显示恢复确认信息。 */
    fun inspectBackup(content: ByteArray, callback: (Result<ConfigurationBackupSummary>) -> Unit) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch(ioDispatcher) {
            val raw = content.toString(Charsets.UTF_8)
            val result = runCatching { backupService.inspectBackup(raw) }
            withContext(mainDispatcher) {
                result.onSuccess { summary ->
                    _uiState.update {
                        it.copy(
                            isWorking = false,
                            pendingBackup = raw,
                            pendingSummary = summary,
                        )
                    }
                }.onFailure {
                    _uiState.update { it.copy(isWorking = false) }
                }
                callback(result)
            }
        }
    }

    fun dismissRestore() {
        _uiState.update { it.copy(pendingBackup = null, pendingSummary = null) }
    }

    /** 执行已经预览过的备份恢复。 */
    fun restoreBackup(password: String, callback: (Result<ConfigurationRestoreResult>) -> Unit) {
        val content = _uiState.value.pendingBackup ?: return
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching { backupService.restoreBackup(content, password) }
            withContext(mainDispatcher) {
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        pendingBackup = if (result.isSuccess) null else it.pendingBackup,
                        pendingSummary = if (result.isSuccess) null else it.pendingSummary,
                    )
                }
                callback(result)
            }
        }
    }
}
