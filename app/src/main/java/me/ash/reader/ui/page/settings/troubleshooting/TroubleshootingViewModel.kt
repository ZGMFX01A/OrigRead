package me.ash.reader.ui.page.settings.troubleshooting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.Log
import me.ash.reader.domain.data.SyncLogger

@HiltViewModel
class TroubleshootingViewModel
@Inject
constructor(
    private val syncLogger: SyncLogger,
) : ViewModel() {

    suspend fun getSyncLogs(): List<Log> = syncLogger.list()

    fun clearSyncLogs() = viewModelScope.launch { syncLogger.clear() }
}
