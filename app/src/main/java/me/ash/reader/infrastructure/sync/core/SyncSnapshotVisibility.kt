package me.ash.reader.infrastructure.sync.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** 界面直接观察持久围栏；进程重启、作业暂停和失败也不能展示半安装数据。 */
@Singleton
class SyncSnapshotVisibility @Inject constructor(@ApplicationContext context: Context) {
    private val control = SyncSnapshotJobsDatabase(context).writableDatabase

    /** 只读独立控制库，不等待 Reader/Chat；空账户尚未确定时检查全部围栏。 */
    fun observe(account: Int?): Flow<Boolean> = flow {
        val condition = if (account == null) "" else "WHERE account=?"
        val bindings = if (account == null) emptyArray() else arrayOf(account.toString())
        while (true) {
            val installing = control.rawQuery("SELECT 1 FROM snapshot_install_fence $condition LIMIT 1", bindings).use { it.moveToFirst() }
            emit(installing)
            delay(VISIBILITY_POLL_MS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    companion object {
        /** 状态轮询不操作业务库，界面只在围栏改变时重组。 */
        private const val VISIBILITY_POLL_MS = 250L
    }
}
