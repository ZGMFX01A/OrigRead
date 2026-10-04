package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.LibraryGenesisIdentityBackfill

/** 分页捕获持有既有 Genesis barrier，Reader 与扩展逐记录参与同一个逻辑固定视图。 */
class SyncPagedGenesisBuilder @Inject constructor(
    database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val keys: SyncDeviceSigningKeyStore,
) {
    private val liveDatabase = database
    private val database get() = SyncFrozenSourceContext.database(liveDatabase)
    @Inject lateinit var frozenReaderFactory: SyncFrozenReaderFactory
    @Inject lateinit var backfill: LibraryGenesisIdentityBackfill
    @Inject lateinit var reader: SyncReaderSnapshotSource
    @Inject lateinit var config: SyncConfigSnapshotSource
    @Inject lateinit var content: SyncSnapshotArticleContent
    @Inject lateinit var blobs: SyncLocalBlobStore
    @Inject lateinit var capture: SyncPagedSnapshotCapture
    @Inject lateinit var metadata: SyncSnapshotMetadataSource
    @Inject lateinit var publication: SyncPagedGenesisPublication
    @Inject lateinit var signer: SyncOperationSigner
    @Inject lateinit var feedIconRepair: SyncFeedIconRepair
    @Inject internal lateinit var originalSource: SyncSnapshotOriginalSource
    @Inject lateinit var extensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension>
    data class Options(val accountId: Int, val session: SyncGenesisSessionEntity, val cut: SyncGenesisCut)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /** 旧缓存迁移先于 cut，正常 mutation 的引用已与业务写入同事务提交。 */
    suspend fun prepare(input: SyncSnapshotArticleContent.Preparation) {
        // 先恢复原签名承诺的可空字段，固定 cut 不得捕获旧版误编码的业务值。
        feedIconRepair.repair(input.space)
        val report = backfill.backfill(input.space, input.accountId, input.now)
        check(report.conflicts.isEmpty()) { "Genesis identity conflicts require resolution" }
        content.prepare(input)
        for (extension in extensions) extension.prepareRawGenesis(input.space, input.now)
        while (signer.signPending(input.space) > 0) { /* 原始操作先签名，再取得共同 cut。 */ }
    }

    /** 页面先完整落盘，发布失败可从不可变的已验证清单继续提交 Reader journal。 */
    suspend fun build(options: Options): String {
        val bundleId = "genesis:paged:${options.cut.genesisBaselineId}"
        val existing = store.find(bundleId)
        val manifest = if (existing?.state == "VERIFIED") json.decodeFromString<SyncPagedSnapshotManifest>(existing.manifestJson)
            else publishFrozen(options, bundleId)
        check(manifest.syncSpaceId == options.cut.syncSpaceId && manifest.crossDbCutId == options.cut.crossDbCutId &&
            manifest.genesisBaselineId == options.cut.genesisBaselineId) { "SNAPSHOT_CONFLICT: capture retry names another fixed view" }
        retireSources(options.cut.crossDbCutId)
        store.lifecycle.budget.sourceRetired(bundleId)
        publication.publish(SyncPagedGenesisPublication.Options(accountId = options.accountId, session = options.session,
            cut = options.cut, manifest = manifest))
        return bundleId
    }

    /** 当前记录可横跨任意页面，不保留业务实体、历史候选或 AUTH 的全库数组。 */
    suspend fun capture(options: Options) {
        val bundleId = "genesis:paged:${options.cut.genesisBaselineId}"
        if (store.find(bundleId)?.state in setOf("FROZEN", "VERIFIED")) return
        store.lifecycle.budget.capture(bundleId)
        val cut = options.cut.crossDbCutId
        val readerReady = SyncRawSnapshotFreeze.complete(database, cut)
        val receipts = extensions.map { it.rawGenesisReady(cut) }
        val complete = readerReady && receipts.all { it }
        check(complete || (!readerReady && receipts.none { it })) { "SNAPSHOT_RAW_INCOMPLETE: cross-database cut requires recapture" }
        if (complete) return
        try {
            SyncSnapshotTrace.suspendPhase("capture.raw_reader", bundleId) { SyncRawSnapshotFreeze.capture(database, cut) }
            for (extension in extensions) SyncSnapshotTrace.suspendPhase("capture.raw_edition", bundleId) { extension.freezeRawGenesis(options.cut) }
        } catch (error: Exception) {
            // Chat 没有同一完成回执时，已冻结 Reader 不能日后拼接另一时刻的 Chat。
            withContext(NonCancellable) { SyncRawSnapshotFreeze.invalidate(database, cut) }
            throw error
        }
    }

    /** barrier 内只复制固定记录，页面生成、root hash 和清单签名由后续阶段完成。 */
    private suspend fun captureRecords(options: Options, bundleId: String) {
            store.beginCapture(SyncPagedSnapshotStore.Capture(bundleId, options.cut.syncSpaceId, options.cut.capturedAt))
            val sink = SyncBufferedSnapshotCapture(store)
            val writer = SyncSnapshotPageWriter(SyncSnapshotPageWriter.Options(bundleId, sink, deferPages = true))
            val context = SyncPagedSnapshotCapture.Context(options.cut, writer)
            SyncSnapshotTrace.suspendPhase("capture.history", bundleId) { capture.retainHistory(context) }
            SyncSnapshotTrace.suspendPhase("capture.business", bundleId) { appendBusiness(options, context) }
            SyncSnapshotTrace.suspendPhase("capture.core", bundleId) { appendCore(options, context) }
            sink.flush()
            SyncSnapshotTrace.suspendPhase("capture.metadata", bundleId) { metadata.append(SyncSnapshotMetadataSource.Options(context, bundleId, sink::flush)) }
            sink.flush()
            SyncSnapshotTrace.phase("capture.sources", bundleId) {
                store.associateCapturedSources(SyncSnapshotCapturedSources.Input(bundleId, options.cut.syncSpaceId) { token ->
                    originalSource.read(options.cut.syncSpaceId, token)
                })
            }
            store.freezeCapture(bundleId, SyncGenesisCodec.encodeFrontiers(options.cut.laneFrontiers))
    }

    /** 只读取已冻结索引；重试不会重新读取已发生新写入的业务库。 */
    private suspend fun publishFrozen(options: Options, bundleId: String): SyncPagedSnapshotManifest {
        if (store.find(bundleId)?.state != "FROZEN") convertRaw(options, bundleId)
        check(store.find(bundleId)?.state == "FROZEN") { "SNAPSHOT_CONFLICT: immutable record cut is missing" }
        SyncSnapshotTrace.phase("capture.verify_blobs", bundleId) { verifyRequiredBlobs(bundleId) }
        val lanes = SyncSnapshotTrace.suspendPhase("capture.pages", bundleId) { SyncFrozenSnapshotPages.build(SyncFrozenSnapshotPages.Options(store, bundleId, options.cut)) }
        val unsigned = unsignedManifest(options, bundleId, lanes)
        val rooted = unsigned.copy(rootHash = SyncPagedSnapshotWire.rootHash(unsigned))
        val signed = rooted.copy(authorSignature = keys.signBase64(rooted.authorDeviceId,
            SyncPagedSnapshotWire.signingMaterial(rooted).toByteArray(Charsets.UTF_8)))
        SyncSnapshotTrace.phase("capture.publish", bundleId) { store.publish(signed, options.cut.capturedAt) }
        return signed
    }

    /** VERIFIED 重入也继续清理，部分取消不能使源 staging 与已关闭副本永久滞留。 */
    private suspend fun retireSources(cut: String) {
        SyncRawSnapshotFreeze.retire(liveDatabase, cut)
        frozenReaderFactory.retire(cut)
        for (extension in extensions) extension.retireRawGenesis(cut)
    }

    /** 固定事实转换在共同屏障之外进行，所有来源和 CONFIG 仓库明确绑定冻结副本。 */
    private suspend fun convertRaw(options: Options, bundleId: String) = withContext(Dispatchers.IO) {
        val budget = store.lifecycle.budget
        budget.sourceProgress(bundleId, SyncSnapshotResourceBudget.SourceProgress(frozen = true))
        val progress = SyncSourceCopyProgress(beforeBatch = { budget.requireRemaining(bundleId, 0L) },
            committed = { source, bytes -> budget.sourceProgress(bundleId, SyncSnapshotResourceBudget.SourceProgress(source = source, copiedBytes = bytes)) })
        val reader = frozenReaderFactory.open(options.cut.crossDbCutId)
        val sources = mutableMapOf<String, androidx.room.RoomDatabase>("reader" to reader)
        try {
            SyncRawSnapshotFreeze.copy(SyncRawSnapshotFreeze.Copy(liveDatabase, reader, options.cut.crossDbCutId, progress))
            for (extension in extensions) extension.openFrozenGenesis(options.cut.crossDbCutId, progress)?.let { (name, source) ->
                check(sources.put(name, source) == null) { "Snapshot Edition source name conflicts: $name" }
            }
            budget.sourceProgress(bundleId, SyncSnapshotResourceBudget.SourceProgress(ready = true))
            SyncFrozenSourceContext.run(sources) { captureRecords(options, bundleId) }
        } finally {
            // 无论转换成功、失败或取消，真正关闭全部副本执行连接后才返回。
            for (source in sources.values) source.close()
        }
    }

    /** 产品来源按父实体顺序输出，AI 扩展使用正式逐记录 API 而非旧 prepareGenesis 列表。 */
    private suspend fun appendBusiness(options: Options, context: SyncPagedSnapshotCapture.Context) {
        reader.forEach(SyncReaderSnapshotSource.Options(options.cut.syncSpaceId, options.accountId) { entity ->
            val value = content.attachRegistered(SyncSnapshotArticleContent.Options(accountId = options.accountId,
                space = options.cut.syncSpaceId, entity = entity, now = options.cut.capturedAt))
            capture.appendEntity(SyncPagedSnapshotCapture.Entity(context, entity.lane, value))
        })
        config.forEach(SyncConfigSnapshotSource.Options(options.cut.syncSpaceId, options.accountId) { entity ->
            capture.appendEntity(SyncPagedSnapshotCapture.Entity(context, "CONFIG", entity))
        })
        for (extension in extensions) extension.streamGenesis(SyncProjectionGenesisStream(syncSpaceId = options.cut.syncSpaceId,
            genesisBaselineId = options.cut.genesisBaselineId, now = options.cut.capturedAt, deferBlobVerification = true) { entity ->
            capture.appendEntity(SyncPagedSnapshotCapture.Entity(context, "AI_HISTORY", entity))
        })
    }

    /** 必需文件验证仅消费固定索引，不在 barrier 内重新读取或 hash 正文。 */
    private fun verifyRequiredBlobs(bundle: String) {
        for (record in store.records(SyncPagedSnapshotStore.RecordFilter(bundle, "AI_HISTORY", "BLOB_MANIFEST"))) {
            val hash = record.value.getValue("hash").jsonPrimitive.content
            val file = checkNotNull(blobs.getBlobFile(hash)) { "Genesis AI_HISTORY Blob is missing: $hash" }
            check(file.length() == record.value.getValue("totalBytes").jsonPrimitive.long && blobs.verifyFile(hash, file)) {
                "Genesis AI_HISTORY Blob size or digest mismatch: $hash"
            }
        }
    }

    /** CORE 只描述当前空间来源；业务身份由每条 ENTITY 的类型、同步 ID 和代次明确携带。 */
    private suspend fun appendCore(options: Options, context: SyncPagedSnapshotCapture.Context) {
        val binding = requireNotNull(database.syncRuntimeDao().findBinding(options.accountId))
        val device = requireNotNull(database.syncRuntimeDao().findDeviceIdentity())
        val fields = buildJsonObject {
            put("localAccountId", options.accountId); put("syncSpaceId", options.cut.syncSpaceId)
            put("lifecycleState", binding.lifecycleState); put("deviceId", device.deviceId); put("witnessId", device.witnessId)
        }
        capture.appendEntity(SyncPagedSnapshotCapture.Entity(context, "CORE_META",
            SyncGenesisProjectionEntity("sync_core", "${options.cut.syncSpaceId}:core", INITIAL_CORE_GENERATION, fields.toString())))
    }

    /** 签名清单只持有页索引与逻辑覆盖度，不把页面 ID 当作新的 lane。 */
    private suspend fun unsignedManifest(options: Options, bundleId: String,
        lanes: List<SyncSnapshotLanePages>): SyncPagedSnapshotManifest {
        val names = SyncReplicationLane.entries.map { it.wireName }
        val policy = SyncGenesisCodec.hashCanonicalJson(SyncGenesisCodec.encodePolicy(names,
            mapOf("isStarred" to SyncGenesisMergePolicy.STARRED_WINS.name, "isUnread" to SyncGenesisMergePolicy.READ_WINS.name)))
        return SyncPagedSnapshotManifest(formatVersion = PAGED_SNAPSHOT_FORMAT, snapshotBundleId = bundleId,
            sourceSnapshotBundleId = bundleId, syncSpaceId = options.cut.syncSpaceId, snapshotClass = SyncSnapshotClass.WORKING.name,
            genesisBaselineId = options.cut.genesisBaselineId, crossDbCutId = options.cut.crossDbCutId,
            policyHash = policy, capturedAt = options.cut.capturedAt, lanes = lanes,
            coverage = names.associateWith { options.cut.laneFrontiers[it].orEmpty() }, requiredCoreShardIds = listOf("AUTH", "CORE_META"),
            authStabilityCheckpoint = null, coverageCommitment = null,
            authorDeviceId = requireNotNull(database.syncRuntimeDao().findDeviceIdentity()).deviceId, rootHash = "", authorSignature = "")
    }

    companion object {
        /** 冲突错误仅展示少量身份示例，完整冲突结果仍来自既有 backfill。 */
        private const val CONFLICT_EXAMPLE_COUNT = 3
        /** CORE 空间描述没有业务重建代次，使用共享初始代次。 */
        private const val INITIAL_CORE_GENERATION = 0L
    }
}
