package me.ash.reader.infrastructure.sync.core

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import me.ash.reader.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 阶段只记录固定身份、计数和查询摘要，不记录正文、签名、SQL 或绑定值。 */
internal object SyncSnapshotTrace {
    data class Run(val runId: String, val ownerGeneration: Long, val inputRootDigest: String)
    data class Work(val rows: Long = 0L, val bytes: Long = 0L, val uniqueSources: Long = 0L, val signatureChecks: Long = 0L)
    private val sequence = AtomicLong()
    private val run = ThreadLocal<Run?>()
    private val current = ThreadLocal<Phase?>()

    class Phase internal constructor(
        private val id: Long,
        private val name: String,
        private val bundle: String,
    ) {
        private val started = System.nanoTime()
        private val parent = current.get()
        private val identity = run.get()
        private val children = AtomicLong()
        private val rows = AtomicLong()
        private val bytes = AtomicLong()
        private val sources = AtomicLong()
        private val signatureChecks = AtomicLong()
        private val locks = AtomicLong()
        private val transactions = AtomicLong()
        private val queries = ConcurrentHashMap<String, AtomicLong>()
        private val batchCount = AtomicLong()
        private val batchTransactions = AtomicLongArray(TRANSACTION_BUCKETS_MS.size)
        private val maxBatchTransaction = AtomicLong()
        private val maxBatchLock = AtomicLong()
        private val lastBatchLog = AtomicLong()

        /** 旧调用传累计行数，新增批次通过 add 提供字节和来源增量。 */
        fun progress(records: Long) {
            rows.set(records)
            val now = System.nanoTime()
            if (now - lastBatchLog.get() >= BATCH_LOG_INTERVAL_NANOS) { lastBatchLog.set(now); event("progress", records, null) }
        }
        fun add(work: Work) {
            rows.addAndGet(work.rows); bytes.addAndGet(work.bytes); sources.addAndGet(work.uniqueSources); signatureChecks.addAndGet(work.signatureChecks)
        }
        fun lockWait(nanos: Long) { locks.addAndGet(nanos) }
        fun transactionHold(nanos: Long) { transactions.addAndGet(nanos) }
        fun sql(fingerprint: String) { queries.computeIfAbsent(fingerprint) { AtomicLong() }.incrementAndGet() }
        fun finish(error: Throwable? = null) {
            parent?.children?.addAndGet(System.nanoTime() - started)
            parent?.add(Work(rows.get(), bytes.get(), sources.get(), signatureChecks.get()))
            queries.forEach { (digest, count) -> parent?.queries?.computeIfAbsent(digest) { AtomicLong() }?.addAndGet(count.get()) }
            if (name.startsWith("batch.") && parent != null) {
                parent.recordBatch(this)
                val now = System.nanoTime()
                if (error == null && transactions.get() <= SLOW_TRANSACTION_NANOS && now - parent.lastBatchLog.get() < BATCH_LOG_INTERVAL_NANOS) return
                parent.lastBatchLog.set(now)
            }
            event(if (error == null) "end" else "failed", null, error)
        }
        internal fun start() { if (!name.startsWith("batch.")) event("begin", null, null) }
        /** 所有事务都进入固定直方图，限频日志不参与 p95 的样本选择。 */
        private fun recordBatch(batch: Phase) {
            batchCount.incrementAndGet()
            val millis = batch.transactions.get().toDouble() / NANOS_PER_MS
            batchTransactions.incrementAndGet(TRANSACTION_BUCKETS_MS.indexOfFirst { millis <= it })
            maxBatchTransaction.accumulateAndGet(batch.transactions.get(), ::maxOf)
            maxBatchLock.accumulateAndGet(batch.locks.get(), ::maxOf)
        }
        private fun event(state: String, records: Long?, error: Throwable?) {
            if (!BuildConfig.DEBUG) return
            val elapsed = System.nanoTime() - started
            val data = JSONObject().put("runId", identity?.runId).put("spanId", id).put("parentSpanId", parent?.id)
                .put("phase", name).put("bundleDigest", bundle).put("ownerGeneration", identity?.ownerGeneration)
                .put("inputRootDigest", identity?.inputRootDigest).put("event", state)
                .put("processedRows", records ?: rows.get()).put("processedBytes", bytes.get()).put("uniqueSourceCount", sources.get())
                .put("signatureVerificationCount", signatureChecks.get())
                .put("inclusiveMs", elapsed / NANOS_PER_MS).put("exclusiveMs", maxOf(0L, elapsed - children.get()) / NANOS_PER_MS)
                .put("lockWaitMs", locks.get() / NANOS_PER_MS).put("transactionHoldMs", transactions.get() / NANOS_PER_MS)
                .put("sqlFingerprints", JSONObject(queries.mapValues { it.value.get() })).put("error", error?.javaClass?.simpleName)
                .put("batchTransactions", JSONObject().put("count", batchCount.get())
                    .put("maxTransactionMs", maxBatchTransaction.get().toDouble() / NANOS_PER_MS)
                    .put("maxLockMs", maxBatchLock.get().toDouble() / NANOS_PER_MS)
                    .put("transactionBuckets", org.json.JSONArray(TRANSACTION_BUCKETS_MS.indices.map { batchTransactions.get(it) })))
            // 可选诊断输出失败不改变提交结果；关键作业回执仍由业务层直接抛错。
            try { Log.i("R11Snapshot", data.toString()) }
            catch (failure: Exception) {
                // 诊断输出错误明确暴露，不能静默丢掉性能证据；不改变已经发生的数据库结果。
                System.err.println("R11 snapshot diagnostic emission failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun begin(name: String, bundle: String): Phase = Phase(sequence.incrementAndGet(), name,
        SyncOperationCanonicalizer.sha256Hex(bundle)).also { it.start() }

    /** owner 身份随协程传播；重试代次与固定输入共同确定一次运行。 */
    suspend fun <T> owned(identity: Run, action: suspend () -> T): T = withContext(run.asContextElement(identity)) { action() }

    fun <T> phase(name: String, bundle: String, block: () -> T): T {
        val phase = begin(name, bundle)
        val parent = current.get()
        current.set(phase)
        try { return block().also { phase.finish() } }
        catch (error: Throwable) { phase.finish(error); throw error }
        finally { current.set(parent) }
    }

    suspend fun <T> suspendPhase(name: String, bundle: String, block: suspend () -> T): T {
        val phase = begin(name, bundle)
        return withContext(current.asContextElement(phase)) {
            try { block().also { phase.finish() } }
            catch (error: Throwable) { phase.finish(error); throw error }
        }
    }

    fun add(work: Work) { current.get()?.add(work) }
    fun lockWait(nanos: Long) { current.get()?.lockWait(nanos) }
    fun transactionHold(nanos: Long) { current.get()?.transactionHold(nanos) }

    /** 查询指纹消除 SQL 字面值，日志不暴露绑定或原 SQL。 */
    fun sql(sql: String) {
        if (!BuildConfig.DEBUG || current.get() == null) return
        val normalized = sql.replace(SQL_LITERAL, "?").replace(SQL_WHITESPACE, " ").trim().uppercase()
        current.get()?.sql(SyncOperationCanonicalizer.sha256Hex(normalized))
    }

    /** 纳秒仅在输出时转换为毫秒。 */
    private const val NANOS_PER_MS = 1_000_000L
    /** 批次与进度日志每秒采样，失败和慢事务保留独立事件。 */
    private const val BATCH_LOG_INTERVAL_NANOS = 1_000_000_000L
    /** 超过文档 p95 的批次不能被日志采样隐藏。 */
    private const val SLOW_TRANSACTION_NANOS = 500_000_000L
    /** 固定门槛包含文档 500 ms 和 2 s，尾部另保存真实最大值。 */
    private val TRANSACTION_BUCKETS_MS = listOf(10.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2000.0, Double.POSITIVE_INFINITY)
    /** 字面值先替换后再生成查询摘要。 */
    private val SQL_LITERAL = Regex("'(?:''|[^'])*'|\\b[0-9]+(?:\\.[0-9]+)?\\b")
    /** 消除排版差异用于归并同类查询。 */
    private val SQL_WHITESPACE = Regex("\\s+")
}
