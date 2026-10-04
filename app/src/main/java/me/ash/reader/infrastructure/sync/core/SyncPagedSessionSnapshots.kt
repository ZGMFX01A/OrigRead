package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 正文只适配真实拥有者索引，不构造虚假的旧格式 lane。 */
data class SyncSnapshotBlobIndex(val replicationLaneId: String, val blobManifestIndexJson: String, val blobReferenceIndexJson: String) {
    companion object {
        /** 旧非 LAN 接口的正文索引保持原来的显式契约。 */
        fun fromShard(shard: SyncSnapshotShardWire) = SyncSnapshotBlobIndex(shard.replicationLaneId,
            shard.blobManifestIndexJson ?: "[]", shard.blobReferenceIndexJson ?: "[]")
    }
}

/** 正式 LAN 会话调度完整分页、业务 Blob、因果合并和不可变 Recovery 晋升。 */
class SyncPagedSessionSnapshots @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val transfer: SyncPagedSnapshotTransfer,
) {
    @Inject lateinit var genesis: SyncGenesisSnapshotService
    @Inject lateinit var exporter: SyncPagedSnapshotExport
    @Inject lateinit var installer: SyncPagedSnapshotInstaller
    @Inject lateinit var validation: SyncPagedInstallValidation
    @Inject lateinit var merger: SyncPagedRecoveryMerge
    @Inject lateinit var promotion: SyncPagedSnapshotPromotion
    @Inject lateinit var runtime: SyncRuntimeCoordinator
    data class Context(val session: SyncEndpointSession, val now: Long, val authorize: suspend () -> Unit,
        val fetchBlobs: suspend (SyncSnapshotBlobIndex) -> Unit, val uploadBlobs: suspend (SyncSnapshotBlobIndex) -> Unit) {
        // 队列归属单次 Peer 会话，失败时随 Context 释放，不跨并发会话共享可变状态。
        internal val lazyDownloads = linkedMapOf<String, SyncPagedSnapshotManifest>()
        internal val lazyUploads = linkedMapOf<String, SyncPagedSnapshotManifest>()
        internal val leases = linkedMapOf<String, String>()
    }
    data class Baseline(val accountId: Int, val space: String, val lanes: Set<String>, val target: SyncPagedSnapshotManifest? = null)
    data class Recovery(val baseline: Baseline, val pushOperations: suspend () -> Unit,
        val retained: suspend () -> SyncCoverage, val accept: suspend (String, SyncCoverage?) -> SyncAuthProtocolObject)
    private val json = Json { encodeDefaults = true }

    /** 回收只消费完整 main-db journal 引用，当前 Genesis 与恢复 capsule 持有的根禁止删除。 */
    suspend fun collectUnused(space: String, now: Long) {
        val protected = database.openHelper.writableDatabase.query("SELECT targetSnapshotBundleId FROM sync_recovery_capsule").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }.toMutableSet()
        }
        val binding = database.syncRuntimeDao().findBindingBySpace(space)
        val active = binding?.genesisSessionId?.let { database.syncGenesisDao().findSession(it) }
        if (active != null) protected.add("genesis:paged:${active.genesisBaselineId}")
        store.lifecycle.collect(now, protected)
    }

    /** 作者先验签，再收缺页，完整索引通过后按实际拥有者读取正文。 */
    suspend fun latest(context: Context, lanes: Set<String>): SyncPagedSnapshotManifest? {
        context.authorize()
        val manifest = context.session.getLatestPagedSnapshot("WORKING", lanes.toList())
            ?: context.session.getLatestPagedSnapshot("GC_BASELINE", lanes.toList()) ?: return null
        check(manifest.lanes.map { it.replicationLaneId }.toSet() == lanes) { "SNAPSHOT_INCOMPATIBLE: remote paged scope differs" }
        validation.verifyAuthor(manifest)
        hold(context, manifest.snapshotBundleId)
        transfer.receive(SyncPagedSnapshotTransfer.Options(manifest, context.session, context.authorize, context.now))
        verifyForSession(manifest)
        context.lazyDownloads[manifest.snapshotBundleId] = manifest
        for (index in blobIndexes(manifest)) if (index.replicationLaneId != "ARTICLE_STATE") context.fetchBlobs(index)
        return manifest
    }

    /** 页面 commit 是真实业务安装，不能用发送页计数冒充完成。 */
    suspend fun push(context: Context, scope: SyncPagedSnapshotExport.Scope): String {
        val manifest = exporter.manifest(scope)
        hold(context, manifest.snapshotBundleId)
        verifyForSession(manifest)
        for (index in blobIndexes(manifest)) {
            if (index.replicationLaneId == "ARTICLE_STATE") {
                context.lazyUploads[manifest.snapshotBundleId] = manifest
                continue
            }
            context.authorize()
            context.uploadBlobs(index)
        }
        transfer.push(SyncPagedSnapshotTransfer.Options(manifest, context.session, context.authorize, context.now))
        return manifest.snapshotBundleId
    }

    /** 网络收发已完成后单独固定 AUTH；验证结束即释放屏障，正文传输不占用此锁。 */
    private suspend fun verifyForSession(manifest: SyncPagedSnapshotManifest) = database.syncProjectionMutex.withLock {
        validation.verifyWithinProjectionBarrier(manifest)
    }

    /** 每个 Peer 使用自己的队列；正文引用仍从持久索引逐条读取。 */
    suspend fun flushLazyBlobs(context: Context) {
        for (manifest in context.lazyUploads.values) for (index in blobIndexes(manifest)) {
            if (index.replicationLaneId == "ARTICLE_STATE") { context.authorize(); context.uploadBlobs(index) }
        }
        context.lazyUploads.clear()
        for (manifest in context.lazyDownloads.values) for (index in blobIndexes(manifest)) {
            if (index.replicationLaneId == "ARTICLE_STATE") { context.authorize(); context.fetchBlobs(index) }
        }
        context.lazyDownloads.clear()
    }

    /** 崩溃后的原清单优先恢复，不能让普通 remote apply 覆盖尚未完成的 baseline。 */
    suspend fun resume(accountId: Int, space: String, now: Long): AndroidSnapshotInstallResult? {
        val started = SyncSnapshotInstallJournal.readStarted(database, space) ?: return null
        val stored = checkNotNull(store.find(started.targetSnapshotBundleId)) { "REBASE_UNSAFE: unfinished paged Snapshot missing" }
        return installer.install(SyncPagedSnapshotInstaller.Options(accountId, json.decodeFromString(stored.manifestJson), now))
    }

    /** Genesis 可以具有零前缀，是否已经观察必须读取轻量基线身份。 */
    suspend fun hasUnobservedGenesis(manifest: SyncPagedSnapshotManifest): Boolean {
        val observed = runtime.observedGenesisBaselines(manifest.syncSpaceId)
        return manifest.lanes.any { lane -> store.records(SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId,
            lane.replicationLaneId, "GENESIS")).any {
            it.value.getValue("genesisBaselineId").jsonPrimitive.content !in observed[lane.replicationLaneId].orEmpty()
        } }
    }

    /** 两侧已有库都进入完整固定视图，保留因果候选、删除见证与 Genesis 观察。 */
    suspend fun mergeLocal(context: Context, input: Baseline): SyncPagedSnapshotManifest {
        context.authorize()
        val cut = genesis.runPaged(SyncGenesisSnapshotService.PagedRunOptions(input.accountId, input.space, now = context.now))
        hold(context, cut.snapshotBundleId)
        input.target?.let { hold(context, it.snapshotBundleId) }
        val local = exporter.manifest(SyncPagedSnapshotExport.Scope(cut.snapshotBundleId, input.lanes))
        return input.target?.let { merger.merge(SyncPagedRecoveryMerge.Options(local, it, context.now)) } ?: local
    }

    /** 安装调用真实跨库 journal，调用方仍需完成现有 STAGING tail 激活。 */
    suspend fun install(context: Context, input: Baseline, manifest: SyncPagedSnapshotManifest): String {
        hold(context, manifest.snapshotBundleId)
        context.authorize()
        return installer.install(SyncPagedSnapshotInstaller.Options(input.accountId, manifest, context.now)).snapshotBundleId
    }

    /** 操作已全部保留时直接发送；真实历史缺口才由 OWNER 接受最终不可变身份。 */
    suspend fun recover(context: Context, input: Recovery) {
        val preview = mergeLocal(context, input.baseline)
        install(context, input.baseline, preview)
        input.pushOperations()
        if (coverageDominates(input.retained(), preview.coverage)) {
            push(context, SyncPagedSnapshotExport.Scope(preview.snapshotBundleId, input.baseline.lanes))
            return
        }
        val id = promotion.variantId(preview, SyncSnapshotClass.BOOTSTRAP_RECOVERY.name)
        val acceptance = input.accept(id, input.baseline.target?.coverage)
        val recovery = promotion.promote(SyncPagedSnapshotPromotion.Options(preview,
            SyncSnapshotClass.BOOTSTRAP_RECOVERY.name, acceptance.authObjectId, context.now))
        push(context, SyncPagedSnapshotExport.Scope(recovery.snapshotBundleId, input.baseline.lanes))
        context.authorize()
        context.session.acceptRecoverySnapshot(recovery.snapshotBundleId, acceptance)
    }

    /** GC 晋升同样使用固定 scope 和新 ID，失败必须显式暴露。 */
    suspend fun promoteGc(bundleId: String, lanes: Set<String>, checkpoint: Pair<String, Long>): String {
        val source = exporter.manifest(SyncPagedSnapshotExport.Scope(bundleId, lanes))
        return promotion.promote(SyncPagedSnapshotPromotion.Options(source, SyncSnapshotClass.GC_BASELINE.name,
            checkpoint.first, checkpoint.second)).snapshotBundleId
    }

    /** finally 释放本轮全部消费根；失败队列不会遗留给其他会话。 */
    fun release(context: Context) {
        context.leases.forEach { (bundle, owner) -> store.lifecycle.release(bundle, owner) }
        context.leases.clear(); context.lazyDownloads.clear(); context.lazyUploads.clear()
    }

    /** 每个根的租约覆盖页面、安装、合并以及延迟正文消费。 */
    private fun hold(context: Context, bundle: String) {
        context.leases.getOrPut(bundle) { store.lifecycle.hold(bundle) }
    }

    /** 每个拥有者单独沿用正文的授权接口，内存与当前记录有关而不与总库大小有关。 */
    private fun blobIndexes(manifest: SyncPagedSnapshotManifest): Sequence<SyncSnapshotBlobIndex> = sequence {
        for (lane in manifest.lanes) {
            for (reference in store.records(SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, lane.replicationLaneId, "BLOB_REFERENCE"))) {
                val hash = reference.value.getValue("hash").jsonPrimitive.content
                val blob = checkNotNull(store.records(SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId,
                    lane.replicationLaneId, "BLOB_MANIFEST", blobHash = hash)).firstOrNull()) { "SNAPSHOT_CORRUPTED: Blob reference lacks manifest" }
                yield(SyncSnapshotBlobIndex(lane.replicationLaneId, JsonArray(listOf(blob.value)).toString(), JsonArray(listOf(reference.value)).toString()))
            }
        }
    }
}
