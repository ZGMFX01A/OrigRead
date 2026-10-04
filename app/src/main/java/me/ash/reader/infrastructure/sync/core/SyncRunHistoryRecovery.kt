package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 新进程首次访问历史或启动同步前，显式结束上一进程遗留的运行记录。 */
@Singleton
class SyncRunHistoryRecovery @Inject constructor(private val database: AndroidDatabase) {
    private val lock = Mutex()
    private var recovered = false

    /** 并发入口必须等待同一次恢复完成；之后的新任务绝不能被当作旧任务结束。 */
    suspend fun ensureRecovered() = lock.withLock {
        if (recovered) return@withLock
        database.syncRunHistoryDao().finishInterrupted(System.currentTimeMillis(), INTERRUPTED_CODE, INTERRUPTED_MESSAGE)
        recovered = true
    }

    companion object {
        /** 区分进程死亡与普通网络失败，不将中断记录伪装为同步成功。 */
        private const val INTERRUPTED_CODE = "PROCESS_INTERRUPTED"
        /** 历史页保留真实中断原因，原始阶段与传输计数不变。 */
        private const val INTERRUPTED_MESSAGE = "应用进程已中断，此次同步未完成"
    }
}
