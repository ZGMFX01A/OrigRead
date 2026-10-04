package me.ash.reader.infrastructure.sync.core

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 真实执行器持有空间所有权；请求断开不取消作业，取消也必须等 finally 执行完才释放。 */
@Singleton
class SyncSnapshotJobs @Inject constructor(@ApplicationContext context: Context) {
    private val database = SyncSnapshotJobsDatabase(context).writableDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val owners = mutableMapOf<String, Job>()
    private val admission = ReentrantLock()
    @Inject lateinit var spaceOwners: SyncSnapshotSpaceOwner
    private data class Transition(val space: String, val generation: Long, val state: String,
        val phase: String, val error: String? = null)

    init {
        // 新进程没有旧执行器，保留原输入与断点，将未退出的运行标记为可恢复暂停。
        database.execSQL("UPDATE snapshot_job SET state='PAUSED',phase='PROCESS_RESTARTED' WHERE state IN ('RUNNING','CANCELLING')")
    }

    /** 原子受理同一固定输入，重复 POST 返回同一代次，不启动第二个写入者。 */
    internal fun submit(input: SyncSnapshotJobInput, action: suspend () -> Unit): SyncSnapshotJobStatus = admission.withLock {
        val current = matching(input)
        val owner = owners[input.space]
        if (owner != null) {
            check(current != null && current.state in ACTIVE_STATES) { "SNAPSHOT_JOB_BUSY: Space has another executor" }
            return@withLock current
        }
        if (current?.state == "COMPLETED") return@withLock current
        val generation = (current?.generation ?: latestGeneration(input.space)) + 1L
        saveRunning(input, generation)
        val executor = scope.launch(start = CoroutineStart.LAZY) { execute(input, generation, action) }
        owners[input.space] = executor
        executor.start()
        checkNotNull(matching(input))
    }

    /** 来源约束来自已经认证的 transport peer，不访问 Reader/Chat 或重型页库。 */
    internal fun status(space: String, peer: String, bundle: String): SyncSnapshotJobStatus =
        database.rawQuery("SELECT peer,bundle,root,generation,state,phase,error FROM snapshot_job WHERE space=?", arrayOf(space)).use {
            check(it.moveToFirst() && it.getString(0) == peer && it.getString(1) == bundle) { "SNAPSHOT_CONFLICT: job has another transport source" }
            SyncSnapshotJobStatus(it.getString(1), it.getString(2), it.getLong(3), it.getString(4), it.getString(5), it.getString(6))
        }

    /** 持久化取消请求后通知真实执行器，状态仍为 CANCELLING 直到事务及 finally 已退出。 */
    internal fun cancel(space: String, peer: String, bundle: String): SyncSnapshotJobStatus = admission.withLock {
        val current = status(space, peer, bundle)
        if (current.state == "RUNNING") {
            update(Transition(space, current.generation, "CANCELLING", "CANCEL_REQUESTED"))
            checkNotNull(owners[space]) { "SNAPSHOT_JOB_OWNER_MISSING: running job has no executor" }.cancel()
        }
        status(space, peer, bundle)
    }

    /** 记录当前阶段；取消请求不会被阶段更新重新改回 RUNNING。 */
    fun cancelForSpace(space: String) = admission.withLock {
        val current = database.rawQuery("SELECT peer,bundle FROM snapshot_job WHERE space=? AND state='RUNNING'", arrayOf(space)).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        if (current != null) cancel(space, current.first, current.second)
    }

    /** 记录当前阶段；取消请求不会被阶段更新重新改回 RUNNING。 */
    internal fun phase(space: String, generation: Long, phase: String) {
        database.execSQL("UPDATE snapshot_job SET phase=?,updated_at=? WHERE space=? AND generation=? AND state='RUNNING'",
            arrayOf(phase, System.currentTimeMillis(), space, generation))
    }

    /** 同步取消检查传播到同步 SQLite 循环，终态在实际执行完成以后写入。 */
    private suspend fun execute(input: SyncSnapshotJobInput, generation: Long, action: suspend () -> Unit) {
        var state = "COMPLETED"
        var failure: String? = null
        try {
            spaceOwners.run(SyncSnapshotSpaceOwner.Input(input.space,
                "install:${input.manifest.rootHash}:${lanes(input)}", "VERIFY_AND_INSTALL")) { action() }
        } catch (cancelled: CancellationException) {
            // 取消保持已提交批次，明确暂停，不伪装成完成或丢弃原固定输入。
            state = "PAUSED"
            failure = cancelled.message
        } catch (error: Exception) {
            // 后台失败必须保留原始错误并暴露给轮询端，Supervisor 只隔离其他空间。
            state = if (error.message?.let { it.startsWith("INSUFFICIENT_SPACE:") || it.startsWith("MORE_WORK:") } == true) "PAUSED" else "FAILED"
            failure = error.stackTraceToString()
            Log.e(TAG, "Snapshot job ${input.space}/$generation failed", error)
        } finally {
            withContext(NonCancellable) {
                admission.withLock {
                    update(Transition(input.space, generation, state, "EXECUTOR_EXITED", failure))
                    owners.remove(input.space)
                }
            }
        }
    }

    /** 当前作业必须同时绑定 root、lane scope 和流水线版本。 */
    private fun matching(input: SyncSnapshotJobInput): SyncSnapshotJobStatus? {
        val manifest = input.manifest
        return database.rawQuery("SELECT peer,bundle,root,scope,pipeline,generation,state,phase,error FROM snapshot_job WHERE space=?", arrayOf(input.space)).use {
            if (!it.moveToFirst()) return@use null
            val same = it.getString(0) == input.peer && it.getString(1) == manifest.snapshotBundleId &&
                it.getString(2) == manifest.rootHash && it.getString(3) == lanes(input) && it.getInt(4) == PIPELINE_VERSION
            check(same || it.getString(6) == "COMPLETED") { "SNAPSHOT_JOB_CONFLICT: resume requires original root and scope" }
            if (!same) null else SyncSnapshotJobStatus(it.getString(1), it.getString(2), it.getLong(5), it.getString(6), it.getString(7), it.getString(8))
        }
    }

    /** 保存受理身份和代次，主键提供持久化的单空间作业所有权。 */
    private fun saveRunning(input: SyncSnapshotJobInput, generation: Long) {
        database.execSQL("INSERT OR REPLACE INTO snapshot_job VALUES(?,?,?,?,?,?,?,'RUNNING','ACCEPTED',NULL,?)",
            arrayOf(input.space, input.peer, input.manifest.snapshotBundleId, input.manifest.rootHash,
                lanes(input), PIPELINE_VERSION, generation, System.currentTimeMillis()))
    }

    /** 仅当前代次可以更新状态，旧完成回调不能释放后继作业。 */
    private fun update(value: Transition) {
        database.execSQL("UPDATE snapshot_job SET state=?,phase=?,error=?,updated_at=? WHERE space=? AND generation=?",
            arrayOf(value.state, value.phase, value.error, System.currentTimeMillis(), value.space, value.generation))
    }

    private fun latestGeneration(space: String): Long = database.rawQuery("SELECT generation FROM snapshot_job WHERE space=?", arrayOf(space)).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    private fun lanes(input: SyncSnapshotJobInput): String = input.manifest.lanes.map { it.replicationLaneId }.sorted().joinToString(",")

    companion object {
        /** 原始输入、派生来源池和恢复状态的处理版本。 */
        private const val PIPELINE_VERSION = 2
        /** 活动状态只在真正执行器退出后解除。 */
        private val ACTIVE_STATES = setOf("RUNNING", "CANCELLING")
        /** 后台错误的诊断标签。 */
        private const val TAG = "SyncSnapshotJobs"
    }
}
