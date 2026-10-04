package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 两个完整分页视图在持久索引中合并，正文、全部候选和实际 Blob 引用从不汇总为 lane 数组。 */
class SyncPagedRecoveryMerge @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val keys: SyncDeviceSigningKeyStore,
) {
    @Inject lateinit var validation: SyncPagedInstallValidation
    @Inject lateinit var entities: SyncPagedRecoveryEntities
    @Inject lateinit var blobs: SyncPagedRecoveryBlobs
    @Inject lateinit var writers: SyncSnapshotPageWriterFactory
    @Inject lateinit var graph: SyncPagedRecoveryGraph
    @Inject lateinit var aliasDeletes: SyncPagedRecoveryAliasDeletes
    @Inject lateinit var owners: SyncSnapshotSpaceOwner
    data class Options(val local: SyncPagedSnapshotManifest, val target: SyncPagedSnapshotManifest, val now: Long)
    private data class Output(val bundleId: String, val writer: SyncSnapshotPageWriter)
    private val json = Json { encodeDefaults = true }

    /** 私有不可变输出先构造；只有按当前 authority 复核成功后才原子发布 Reader 目录。 */
    suspend fun merge(options: Options): SyncPagedSnapshotManifest = owners.run(
        SyncSnapshotSpaceOwner.Input(options.local.syncSpaceId, "merge:" + mergeIdentity(options), "MERGE")) {
        check(!database.inTransaction()) { "Snapshot merge must not inherit a Reader transaction" }
        requireSameScope(options)
        val bundleId = MERGE_BUNDLE_PREFIX + mergeIdentity(options)
        store.lifecycle.protectInputs(bundleId, setOf(options.local.snapshotBundleId, options.target.snapshotBundleId))
        val lease = store.lifecycle.hold(bundleId)
        try {
            run {
                SyncSnapshotTrace.suspendPhase("merge.verify_local", options.local.snapshotBundleId) {
                    validation.verifyWithinProjectionBarrier(options.local)
                }
                SyncSnapshotTrace.suspendPhase("merge.verify_target", options.target.snapshotBundleId) {
                    validation.verifyWithinProjectionBarrier(options.target)
                }
            }
            // 不把 CPU/私有库写入放在 Reader 事务中；该同步构建没有挂起点或 Reader 访问。
            val manifest = SyncSnapshotTrace.suspendPhase("merge.detached_build", bundleId) {
                withContext(Dispatchers.Default) { build(options, bundleId) }
            }
            publish(options, manifest)
        } finally {
            store.lifecycle.release(bundleId, lease)
        }
    }

    /** 权威变化时退出写事务重准备，完整字段校验不占用公共投影锁。 */
    private suspend fun publish(options: Options, manifest: SyncPagedSnapshotManifest): SyncPagedSnapshotManifest {
        repeat(SNAPSHOT_PUBLICATION_ATTEMPTS) {
            SyncSnapshotCancellation.checkpoint()
            try {
                for (input in listOf(options.local, options.target, manifest)) validation.verifyWithinProjectionBarrier(input)
                val origin = checkNotNull(database.syncGenesisDao().findBundle(options.local.sourceSnapshotBundleId))
                val published = origin.copy(snapshotBundleId = manifest.snapshotBundleId,
                    snapshotClass = SyncSnapshotClass.WORKING.name, authStabilityCheckpointId = null,
                    rootHash = manifest.rootHash, replicationPolicyHash = manifest.policyHash,
                    shardDescriptorsJson = json.encodeToString(manifest.lanes), createdAt = manifest.capturedAt)
                database.syncProjectionMutex.withLock {
                    database.withTransaction {
                        for (input in listOf(options.local, options.target, manifest)) validation.verifyWithinDatabaseTransaction(input)
                        database.syncGenesisDao().upsertBundle(published)
                    }
                }
                return manifest
            } catch (error: IllegalStateException) {
                // 仅修订竞争重试，签名、授权、取消和存储失败继续暴露。
                if (!snapshotRevisionConflict(error)) throw error
            }
        }
        return snapshotNeedsStableInput()
    }

    /** 工作索引仅私有可见，所有业务合并成功后输出字节页并原子发布清单。 */
    private fun build(options: Options, bundleId: String): SyncPagedSnapshotManifest {
        val index = SyncPagedRecoveryIndex(options.local.snapshotBundleId, options.target.snapshotBundleId, "$bundleId:index",
            options.local.lanes.map { it.replicationLaneId })
        val existing = store.find(bundleId)
        if (existing?.state == "VERIFIED") return json.decodeFromString(existing.manifestJson)
        if (store.find(index.workId) == null) {
            store.database.beginTransaction()
            try {
                store.beginCapture(SyncPagedSnapshotStore.Capture(index.workId, options.local.syncSpaceId, options.now))
                store.database.setTransactionSuccessful()
            } finally {
                // 初始化只创建私有工作身份，不包含字段合并或正文转换。
                store.database.endTransaction()
            }
        }
        SyncSnapshotTrace.phase("merge.entities", bundleId) { entities.merge(index) }
        SyncSnapshotTrace.phase("merge.shared", bundleId) { copySharedRecords(index) }
        SyncSnapshotTrace.phase("merge.alias_deletes", bundleId) { aliasDeletes.reconcile(index) }
        SyncSnapshotTrace.phase("merge.graph", bundleId) { graph.reconcile(index) }
        SyncSnapshotTrace.phase("merge.blobs", bundleId) { blobs.merge(index) }
        SyncSnapshotCancellation.checkpoint()
        // 恢复保留输出记录和复制断点；重写的完整页面必须通过既有摘要相等检查。
        if (existing == null) store.beginCapture(SyncPagedSnapshotStore.Capture(bundleId, options.local.syncSpaceId, options.now))
        else check(existing.state == "CAPTURING") { "SNAPSHOT_CONFLICT: recovery output is not a private capture" }
        val writer = writers.create(bundleId)
        SyncSnapshotTrace.phase("merge.write_pages", bundleId) {
            SyncSnapshotOutputRecords.write(SyncSnapshotOutputRecords.Options(store = store, index = index, outputBundleId = bundleId, writer = writer))
        }
        val fixed = options.copy(now = store.captureTime(index.workId))
        val manifest = SyncSnapshotTrace.phase("merge.finish_pages", bundleId) { finish(fixed, Output(bundleId, writer)) }
        SyncSnapshotTrace.phase("merge.publish_pages", bundleId) { store.publish(manifest, options.now) }
        store.discardCapture(index.workId)
        return manifest
    }

    /** 本机 CORE/AUTH 不引入未经本机账本验证的新权限，Genesis 观察及别名边取并集。 */
    private fun copySharedRecords(index: SyncPagedRecoveryIndex) {
        val buffered = SyncBufferedSnapshotCapture(store)
        for (lane in index.lanes) {
            // 共享副本只使用 AUTH/CORE；其他 lane 的正文与候选已由实体合并负责。
            val shared = if (lane == "AUTH" || lane == "CORE_META")
                store.records(SyncPagedSnapshotStore.RecordFilter(index.localId, lane)) else emptySequence()
            for (record in shared) {
                if (lane == "AUTH" || (lane == "CORE_META" && record.kind !in setOf("ALIAS_EDGE", "GENESIS", "TOMBSTONE"))) {
                    buffered.writeRecord(index.workId, lane, record)
                }
            }
            for (id in listOf(index.localId, index.targetId)) for (kind in listOf("GENESIS", "ALIAS_EDGE")) {
                for (record in store.records(SyncPagedSnapshotStore.RecordFilter(id, lane, kind))) buffered.writeRecord(index.workId, lane, record)
            }
        }
        buffered.flush()
    }

    /** 合并前缀都由某个完整输入支配，不凭页面数或最大操作号制造连续知识。 */
    private fun finish(options: Options, output: Output): SyncPagedSnapshotManifest {
        val local = options.local
        val target = options.target
        val coverage = local.lanes.associate { descriptor ->
            val lane = descriptor.replicationLaneId
            lane to (local.coverage[lane].orEmpty().keys + target.coverage[lane].orEmpty().keys).associateWith { actor ->
                maxOf(local.coverage[lane]?.get(actor) ?: 0L, target.coverage[lane]?.get(actor) ?: 0L)
            }
        }
        val lanes = output.writer.finish(coverage.mapValues { (lane, actors) -> SyncGenesisCodec.encodeFrontiers(mapOf(lane to actors)) })
        // 策略与选定域沿用本地已验证捕获，具体内容只影响 bundle/root，不影响同策略后继回收。
        val unsigned = local.copy(snapshotBundleId = output.bundleId,
            capturedAt = options.now, coverage = coverage, lanes = lanes, authStabilityCheckpoint = null, coverageCommitment = null,
            rootHash = "", authorSignature = "")
        val rooted = unsigned.copy(rootHash = SyncPagedSnapshotWire.rootHash(unsigned))
        return rooted.copy(authorSignature = keys.signBase64(rooted.authorDeviceId, SyncPagedSnapshotWire.signingMaterial(rooted).toByteArray(Charsets.UTF_8)))
    }

    /** 根身份绑定两个固定输入和完整选定范围，重复重试只恢复同一个已验证输出。 */
    private fun mergeIdentity(options: Options): String = SyncGenesisCodec.hashCanonicalJson(buildJsonObject {
        put("localRootHash", options.local.rootHash); put("targetRootHash", options.target.rootHash)
        put("pipelineRevision", PIPELINE_REVISION)
        put("lanes", JsonArray(options.local.lanes.map { it.replicationLaneId }.sorted().map(::JsonPrimitive)))
    }.toString())

    /** 每个选定域必须在两侧同时存在，缺失不能被解释成空库。 */
    private fun requireSameScope(options: Options) {
        check(options.local.syncSpaceId == options.target.syncSpaceId && options.local.snapshotClass == SyncSnapshotClass.WORKING.name &&
            options.local.lanes.map { it.replicationLaneId }.toSet() == options.target.lanes.map { it.replicationLaneId }.toSet()) {
            "REBASE_UNSAFE: paged recovery requires both inputs for every selected lane"
        }
    }

    companion object {
        /** 新输出不复用旧版内容派生策略的签名对象，历史清单保持不变。 */
        /** 派生表示与批次恢复算法版本，旧私有索引不能被新流水线接管。 */
        private const val PIPELINE_REVISION = 3
        private const val MERGE_BUNDLE_PREFIX = "snapshot:merge:stable-policy:"
    }
}
