package me.ash.reader.infrastructure.sync.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap

/** capture/merge/install 共用持久空间所有权，嵌套步骤继承当前真实执行器，不另开写入者。 */
@Singleton
class SyncSnapshotSpaceOwner @Inject constructor(@ApplicationContext context: Context) {
    private val database = SyncSnapshotJobsDatabase(context).writableDatabase
    private val current = SyncSnapshotAccess.ownedSpace
    private val executors = ConcurrentHashMap<String, Job>()
    data class Input(val space: String, val identity: String, val phase: String)

    init {
        database.execSQL("UPDATE snapshot_space_owner SET state='PAUSED' WHERE state IN ('RUNNING','CANCELLING')")
    }

    /** 异步调用者返回或取消不能提前释放拥有权；finally 等事务及子工作实际退出。 */
    suspend fun <T> run(input: Input, action: suspend () -> T): T {
        if (current.get() == input.space) return action()
        val generation = claim(input)
        executors[input.space] = checkNotNull(currentCoroutineContext()[Job]) { "SNAPSHOT_JOB_OWNER_MISSING" }
        var state = "COMPLETED"
        try {
            val digest = SyncOperationCanonicalizer.sha256Hex(input.identity)
            return SyncSnapshotTrace.owned(SyncSnapshotTrace.Run("$digest:$generation", generation, digest)) {
                withContext(current.asContextElement(input.space)) { SyncSnapshotCancellation.run { action() } }
            }
        } catch (error: CancellationException) {
            // 保留固定输入及断点，下一代执行器只能恢复同一作业。
            state = "PAUSED"
            throw error
        } catch (error: Exception) {
            // 错误不释放持久恢复身份，实际执行器退出以后才登记 FAILED。
            state = if (error.message?.let { it.startsWith("INSUFFICIENT_SPACE:") || it.startsWith("MORE_WORK:") } == true) "PAUSED" else "FAILED"
            throw error
        } finally {
            try {
                withContext(NonCancellable) {
                    database.execSQL("UPDATE snapshot_space_owner SET state=? WHERE space=? AND generation=?",
                        arrayOf(state, input.space, generation))
                }
            } finally { executors.remove(input.space) }
        }
    }

    /** 控制库原子受理，只包含小型 SQL；不持有 Reader/Chat/Page 锁或 JVM monitor。 */
    private fun claim(input: Input): Long {
        database.beginTransaction()
        try {
            val previous = database.rawQuery("SELECT identity,generation,state FROM snapshot_space_owner WHERE space=?", arrayOf(input.space)).use {
                if (!it.moveToFirst()) null else Triple(it.getString(0), it.getLong(1), it.getString(2))
            }
            check(previous?.third !in setOf("RUNNING", "CANCELLING")) { "SNAPSHOT_JOB_BUSY: Space has a live capture/merge/install executor" }
            check(previous == null || previous.third == "COMPLETED" || previous.first == input.identity) {
                "SNAPSHOT_JOB_CONFLICT: resume the original fixed input before replacing the Space job"
            }
            val generation = (previous?.second ?: 0L) + 1L
            database.execSQL("INSERT OR REPLACE INTO snapshot_space_owner VALUES(?,?,?,?,'RUNNING')",
                arrayOf(input.space, input.identity, input.phase, generation))
            database.setTransactionSuccessful()
            return generation
        } finally {
            // 所有受理失败都回滚，旧执行器身份不会被覆盖。
            database.endTransaction()
        }
    }

    /** 显式暂停通知真正拥有者；等待者退出与取消请求都不立即释放空间。 */
    fun requestCancel(space: String) {
        val executor = executors[space] ?: return
        database.execSQL("UPDATE snapshot_space_owner SET state='CANCELLING',phase='CANCEL_REQUESTED' WHERE space=? AND state='RUNNING'", arrayOf(space))
        executor.cancel(CancellationException("Snapshot paused by user"))
    }

    /** 开始破坏性安装之前持久化围栏，重试只能使用相同 root 与 scope。 */
    suspend fun fence(account: Int, manifest: SyncPagedSnapshotManifest) {
        check(current.get() == manifest.syncSpaceId) { "SNAPSHOT_JOB_OWNER_MISSING" }
        val scope = manifest.lanes.map { it.replicationLaneId }.sorted().joinToString(",")
        val generation = database.rawQuery("SELECT generation FROM snapshot_space_owner WHERE space=? AND state='RUNNING'", arrayOf(manifest.syncSpaceId))
            .use { check(it.moveToFirst()); it.getLong(0) }
        database.rawQuery("SELECT root,scope FROM snapshot_install_fence WHERE space=?", arrayOf(manifest.syncSpaceId)).use {
            check(!it.moveToFirst() || (it.getString(0) == manifest.rootHash && it.getString(1) == scope)) { "SNAPSHOT_JOB_CONFLICT: unfinished install fence differs" }
        }
        database.execSQL("INSERT OR REPLACE INTO snapshot_install_fence VALUES(?,?,?,?,?)",
            arrayOf(manifest.syncSpaceId, account, manifest.rootHash, scope, generation))
    }

    /** READY、尾部、正文与来源完成回执全部成立以后解除围栏。 */
    fun releaseFence(manifest: SyncPagedSnapshotManifest) {
        database.delete("snapshot_install_fence", "space=? AND root=?", arrayOf(manifest.syncSpaceId, manifest.rootHash))
    }
}
