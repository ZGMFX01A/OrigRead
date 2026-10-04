package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 分页快照沿用 Genesis 生命周期，固定视图和 Tail 激活不因传输格式改变。 */
class SyncGenesisExecution @Inject constructor(
    private val database: AndroidDatabase,
    private val coordinator: SyncRuntimeCoordinator,
    private val auth: AndroidSyncAuthLedgerService,
) {
    private val generation = SyncPeerSessions()
    @Inject lateinit var operationBuilder: ReaderOperationBuilder
    @Inject lateinit var extensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension>
    @Inject lateinit var store: SyncPagedSnapshotStore
    @Inject lateinit var owners: SyncSnapshotSpaceOwner
    @Inject lateinit var frozenReaderFactory: SyncFrozenReaderFactory
    data class Capture(val accountId: Int, val session: SyncGenesisSessionEntity, val cut: SyncGenesisCut)
    data class Options(val accountId: Int, val space: String? = null, val sessionId: String = UUID.randomUUID().toString(),
        val now: Long = System.currentTimeMillis(), val prepare: suspend (String) -> Unit = {},
        val capture: suspend (Capture) -> Unit = {}, val build: suspend (Capture) -> String)
    private data class Preparation(val cut: SyncGenesisCut, val bundleId: String, val alreadyActive: Boolean,
        val source: Capture? = null)

    /** 同一持久会话仅捕获一次，失败明确记录并传播；完成前保持 Genesis mutation barrier。 */
    suspend fun run(options: Options): SyncGenesisCutoverResult {
        val binding = coordinator.prepareSpace(options.accountId, options.space, options.now)
        val session = database.syncRuntimeDao().findBinding(options.accountId)?.genesisSessionId ?: options.sessionId
        return owners.run(SyncSnapshotSpaceOwner.Input(binding.syncSpaceId, "capture:$session", "CAPTURE")) {
            generation.run(options.accountId.toString()) { execute(options.copy(sessionId = session)) }
        }
    }

    /** 同一账户的冻结/发布由单个生成任务拥有，不占用网络会话的全局锁。 */
    private suspend fun execute(options: Options): SyncGenesisCutoverResult {
        val prepared = coordinator.prepareSpace(options.accountId, options.space, options.now)
        try {
            auth.ensureLocalSpaceRoot(prepared.syncSpaceId, options.now)
            resumeCompleteSource(options.accountId)
            // 先建立可恢复会话与 mutation 捕获 authority，再执行可能失败的文件准备。
            coordinator.beginGenesisCapture(options.accountId, options.sessionId, options.now)
            options.prepare(prepared.syncSpaceId)
            val preparation = coordinator.captureGenesisCutWithBarrier(options.accountId, options.now) { cut, session ->
                prepare(options, cut, session)
            }
            preparation.source?.let { source ->
                check(options.build(source) == preparation.bundleId) { "SNAPSHOT_CONFLICT: publication changed frozen identity" }
            }
            val tail = if (preparation.alreadyActive) 0 else activateTail(options, preparation)
            store.lifecycle.completed(preparation.bundleId)
            val cut = preparation.cut
            return SyncGenesisCutoverResult(syncSpaceId = cut.syncSpaceId, genesisSessionId = cut.genesisSessionId,
                genesisBaselineId = cut.genesisBaselineId, crossDbCutId = cut.crossDbCutId,
                snapshotBundleId = preparation.bundleId, tailOperationsBuilt = tail)
        } catch (error: Throwable) {
            // journal 写入失败作为原始异常的附加错误暴露，不能伪装成已经记录完成。
            try { recordFailure(options, error) } catch (journalError: Throwable) { error.addSuppressed(journalError) }
            throw error
        }
    }

    /** 两库源集或私有记录已完整时保留原 cut；残缺失败仍由协调器显式创建新捕获尝试。 */
    private suspend fun resumeCompleteSource(accountId: Int) {
        val binding = database.syncRuntimeDao().findBinding(accountId) ?: return
        val session = binding.genesisSessionId?.let { database.syncGenesisDao().findSession(it) } ?: return
        if (session.state != "FAILED" || session.snapshotBundleId != null) return
        val cut = session.crossDbCutId ?: return
        val complete = SyncRawSnapshotFreeze.complete(database, cut) && extensions.all { it.rawGenesisReady(cut) }
        if (!complete) {
            // 残缺 cut 不再有消费者；分批回收两份旧来源，协调器随后创建全新的共同 cut。
            SyncRawSnapshotFreeze.retire(database, cut)
            frozenReaderFactory.retire(cut)
            for (extension in extensions) extension.retireRawGenesis(cut)
            return
        }
        database.withTransaction {
            database.syncGenesisDao().upsertSession(session.copy(state = SyncGenesisStage.CUT_CAPTURED.name))
        }
    }

    /** 扩展拥有自己的 writer frontier；真实 cut 更新后才允许构造任何格式的固定视图。 */
    private suspend fun prepare(options: Options, cut: SyncGenesisCut, session: SyncGenesisSessionEntity): Preparation {
        val persisted = store.find("genesis:paged:${cut.genesisBaselineId}")?.takeIf { it.state == "VERIFIED" }
            ?.let { Json.decodeFromString<SyncPagedSnapshotManifest>(it.manifestJson) }
        // 已完成私有页的固定 frontier 不允许被重试时新产生的 writer 序号抬高。
        val frozen = store.find("genesis:paged:${cut.genesisBaselineId}")?.takeIf { it.state == "FROZEN" }
        val effective = persisted?.let { cut.copy(laneFrontiers = it.coverage, capturedAt = it.capturedAt) }
            ?: frozen?.let { cut.copy(laneFrontiers = SyncGenesisCodec.decodeFrontiers(it.manifestJson)) } ?: effectiveCut(cut)
        val updated = if (effective.laneFrontiers == cut.laneFrontiers) session else session.copy(
            cutFrontierJson = SyncGenesisCodec.encodeFrontiers(effective.laneFrontiers), updatedAt = options.now).also {
            database.withTransaction { database.syncGenesisDao().upsertSession(it) }
        }
        val active = updated.state == SyncGenesisStage.ACTIVE.name
        if (active) database.withTransaction {
            val binding = requireNotNull(database.syncRuntimeDao().findBinding(options.accountId))
            database.syncRuntimeDao().upsertBinding(binding.copy(lifecycleState = SyncSpaceLifecycleState.ACTIVE.name,
                genesisSessionId = null, updatedAt = options.now))
        }
        val existing = active || updated.state in setOf(SyncGenesisStage.SNAPSHOT_BUILT.name, SyncGenesisStage.TAIL_REPLAY.name)
        val source = if (existing) null else Capture(options.accountId, updated, effective)
        source?.let { options.capture(it) }
        val bundleId = if (existing) requireNotNull(updated.snapshotBundleId) else "genesis:paged:${effective.genesisBaselineId}"
        return Preparation(effective, bundleId, active, source)
    }

    /** 合并的都是连续 writer 前缀，不能拿页面数或物化行数冒充覆盖度。 */
    private suspend fun effectiveCut(cut: SyncGenesisCut): SyncGenesisCut {
        val actors = database.syncRuntimeDao().listActors(cut.syncSpaceId).map { it.actorIncarnationId }.toSet()
        val frontiers = cut.laneFrontiers.mapValues { (_, values) -> values.toMutableMap() }.toMutableMap()
        for (extension in extensions) {
            val owned = extension.genesisLaneFrontiers(syncSpaceId = cut.syncSpaceId, crossDbCutId = cut.crossDbCutId,
                actorIncarnationIds = actors, capturedAt = cut.capturedAt)
            for ((lane, values) in owned) {
                val target = frontiers.getOrPut(lane) { linkedMapOf() }
                for ((actor, sequence) in values) target[actor] = maxOf(target[actor] ?: 0L, sequence)
            }
        }
        return cut.copy(laneFrontiers = frontiers.mapValues { (_, values) -> values.toSortedMap() })
    }

    /** Tail 按固定批次生成，直到两库均无待构建 mutation 后才提交 ACTIVE。 */
    private suspend fun activateTail(options: Options, preparation: Preparation): Int {
        var total = 0
        coordinator.withGenesisBarrier(options.accountId) { session ->
            if (session.state == SyncGenesisStage.ACTIVE.name) return@withGenesisBarrier
            database.withTransaction { database.syncGenesisDao().upsertSession(session.copy(
                state = SyncGenesisStage.TAIL_REPLAY.name, updatedAt = options.now)) }
            while (true) {
                var built = operationBuilder.buildPending(preparation.cut.syncSpaceId, TAIL_BATCH_SIZE, options.now)
                for (extension in extensions) built += extension.buildPendingOperations(preparation.cut.syncSpaceId, TAIL_BATCH_SIZE, options.now)
                total += built
                if (built == 0) break
            }
            database.withTransaction {
                check(database.syncOutboxDao().listPending(preparation.cut.syncSpaceId, SINGLE_PENDING_CHECK).isEmpty()) {
                    "Genesis tail replay left pending local mutations"
                }
                check(extensions.none { it.hasPendingOutbox(preparation.cut.syncSpaceId) }) { "Genesis tail replay left pending extension-owned mutations" }
                val binding = requireNotNull(database.syncRuntimeDao().findBinding(options.accountId))
                database.syncRuntimeDao().upsertBinding(binding.copy(lifecycleState = SyncSpaceLifecycleState.ACTIVE.name,
                    genesisSessionId = null, updatedAt = options.now))
                database.syncGenesisDao().upsertSession(session.copy(state = SyncGenesisStage.ACTIVE.name,
                    snapshotBundleId = session.snapshotBundleId ?: preparation.bundleId, updatedAt = options.now))
            }
        }
        return total
    }

    /** 失败属于当前绑定的真实会话，截断仅用于已有失败原因字段的展示长度。 */
    private suspend fun recordFailure(options: Options, error: Throwable) = database.withTransaction {
        val binding = database.syncRuntimeDao().findBinding(options.accountId) ?: return@withTransaction
        val sessionId = binding.genesisSessionId ?: return@withTransaction
        val session = database.syncGenesisDao().findSession(sessionId) ?: return@withTransaction
        database.syncGenesisDao().upsertSession(session.copy(state = SyncGenesisStage.FAILED.name,
            failureReason = error.message?.take(FAILURE_REASON_LENGTH), updatedAt = options.now))
    }

    companion object {
        /** 只限制一次构建批次，Tail 总量不设上限。 */
        private const val TAIL_BATCH_SIZE = 500
        /** 存在性检查只需读取一个轻量 Outbox 条目。 */
        private const val SINGLE_PENDING_CHECK = 1
        /** 沿用持久错误字段的最大展示长度。 */
        private const val FAILURE_REASON_LENGTH = 2_000
    }
}
