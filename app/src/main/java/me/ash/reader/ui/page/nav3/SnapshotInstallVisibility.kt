package me.ash.reader.ui.page.nav3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.ash.reader.R
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.sync.core.SyncSnapshotVisibility

/** 围栏期间遮住业务图并保留同步设置入口，观察查询在后台等待完整安装。 */
@Composable
fun SnapshotInstallVisibility(options: SnapshotVisibilityOptions, content: @Composable () -> Unit) {
    val account by options.accounts.currentAccountFlow.collectAsState(initial = null)
    val installing by remember(account?.id) { options.visibility.observe(account?.id) }.collectAsState(initial = true)
    Box(Modifier.fillMaxSize()) {
        if (!installing || options.showingSyncSettings) content()
        if (installing && !options.showingSyncSettings) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.sync_snapshot_installing))
                Button(onClick = options.openSyncSettings) { Text(stringResource(R.string.sync_snapshot_status_recovery)) }
            }
        }
    }
}

/** 可见性依赖和导航操作由 Activity 注入，不访问或缓存半完成的业务数据。 */
data class SnapshotVisibilityOptions(val accounts: AccountService, val visibility: SyncSnapshotVisibility,
    val showingSyncSettings: Boolean, val openSyncSettings: () -> Unit)
