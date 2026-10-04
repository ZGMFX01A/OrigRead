package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 将作业取消传到不挂起的 SQLite/编码循环；退出事务后才释放调用方持有的空间锁。 */
internal object SyncSnapshotCancellation {
    /** 仅随当前协程恢复线程绑定，不能把上一作业的令牌留给下一写入者。 */
    private val owner = ThreadLocal<Job?>()

    /** 同步循环在领取下一记录前检查真实拥有者；不停止或关闭共享数据库线程。 */
    fun checkpoint() { owner.get()?.ensureActive() }

    /** 覆盖内部线程切换；异常照常传播，由各数据库 finally 回滚并关闭游标。 */
    suspend fun <T> run(action: suspend () -> T): T =
        withContext(owner.asContextElement(currentCoroutineContext()[Job])) { action() }
}
