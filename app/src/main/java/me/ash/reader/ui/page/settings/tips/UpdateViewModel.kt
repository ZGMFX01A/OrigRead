package me.ash.reader.ui.page.settings.tips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.ash.reader.domain.service.AppService
import me.ash.reader.infrastructure.net.Download
import me.ash.reader.ui.ext.isGitHub
import me.ash.reader.ui.ext.isLlmEdition
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val appService: AppService,
) : ViewModel() {

    private val _updateUiState = MutableStateFlow(UpdateUiState())
    val updateUiState: StateFlow<UpdateUiState> = _updateUiState.asStateFlow()

    var updateJob: Job? = null

    fun checkUpdate(
        preProcessor: suspend () -> Unit = {},
        postProcessor: suspend (Boolean) -> Unit = {},
    ) {
        if (!isGitHub) return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            preProcessor()
            appService.checkUpdate().let {
                it?.let {
                    if (it) {
                        showDialog()
                    } else {
                        hideDialog()
                    }
                    postProcessor(it)
                }
            }
        }
    }

    fun showDialog() {
        _updateUiState.update {
            it.copy(
                updateDialogVisible = true
            )
        }
    }

    fun hideDialog() {
        _updateUiState.update {
            it.copy(
                updateDialogVisible = false
            )
        }
    }

    /** 启动 GitHub Release APK 下载；同一时间只保留一个下载状态流。 */
    fun downloadUpdate(url: String, version: String) {
        _updateUiState.update {
            it.copy(
                downloadFlow =
                    appService.downloadUpdate(
                        url = url,
                        filename =
                            if (isLlmEdition) {
                                "OrigRead-LLM-$version.apk"
                            } else {
                                "OrigRead-$version.apk"
                            },
                    ),
            )
        }
    }
}

data class UpdateUiState(
    val updateDialogVisible: Boolean = false,
    val downloadFlow: Flow<Download> = emptyFlow(),
)
