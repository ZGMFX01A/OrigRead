package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 完整验证的记录索引直接安装，Reader 与 Chat/CONFIG 的跨库进度由持久 journal 管理。 */
class SyncPagedSnapshotInstaller @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val coordinator: SyncRuntimeCoordinator,
) {
    @Inject lateinit var validation: SyncPagedInstallValidation
    @Inject lateinit var identities: SyncPagedEntityIdentity
    @Inject lateinit var reader: SyncPagedReaderRestore
    @Inject lateinit var fields: SyncPagedFieldRestore
    @Inject lateinit var metadata: SyncPagedMetadataRestore
    @Inject lateinit var config: SyncPagedConfigRestore
    @Inject lateinit var coverage: SyncPagedInstallCoverage
    @Inject lateinit var tail: SyncPagedSnapshotTail
    @Inject lateinit var extensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension>
    @Inject lateinit var aliasProjection: SyncPagedAliasProjection
    @Inject lateinit var batches: SyncSnapshotReaderBatches
    @Inject lateinit var operationEvidence: SyncPagedOperationEvidence
    @Inject lateinit var sourceCompletion: SyncSnapshotSourceCompletion
    @Inject lateinit var owners: SyncSnapshotSpaceOwner
    @Inject lateinit var localBlobs: SyncLocalBlobStore
    data class Options(val accountId: Int, val manifest: SyncPagedSnapshotManifest, val now: Long)
    private val json = Json { encodeDefaults = true }

    /** 与本地 mutation 串行；重试同一 READY baseline 不把 ACTIVE 降回 STAGING。 */
    suspend fun install(options: Options): AndroidSnapshotInstallResult =
        SyncSnapshotTrace.suspendPhase("install.projection_barrier", options.manifest.snapshotBundleId) {
            val scope = options.manifest.lanes.map { it.replicationLaneId }.sorted().joinToString(",")
            owners.run(SyncSnapshotSpaceOwner.Input(options.manifest.syncSpaceId, "install:${options.manifest.rootHash}:$scope", "INSTALL")) {
                coordinator.withSnapshotInstallBarrier(options.accountId) { binding -> installLocked(options, binding) }
            }
        }

    /** Recovery 副本与 STARTED journal 先落盘，再执行可重入的跨库安装。 */
    private suspend fun installLocked(options: Options, binding: SyncLocalSpaceBindingEntity): AndroidSnapshotInstallResult {
        val manifest = options.manifest
        check(binding.syncSpaceId == manifest.syncSpaceId) { "Snapshot belongs to another Sync Space" }
        SyncSnapshotTrace.suspendPhase("install.verify", manifest.snapshotBundleId) { validation.verifyWithinProjectionBarrier(manifest) }
        val bundle = bundle(manifest)
        val lanes = manifest.lanes.map { it.replicationLaneId }.toSet()
        if (ready(binding, bundle, lanes)) {
            reserveBodies(manifest)
            owners.fence(options.accountId, manifest)
            drainWriters()
            requireBodies(manifest)
            owners.releaseFence(manifest)
            return result(manifest, 0)
        }
        SyncSnapshotTrace.suspendPhase("install.validate_entities", manifest.snapshotBundleId) { validateEntities(manifest) }
        reserveBodies(manifest)
        val started = SyncSnapshotInstallJournal.readStarted(database, manifest.syncSpaceId)
        requireSameStarted(started, manifest, lanes)
        val current = coverage.current(manifest.syncSpaceId)
        val previous = started?.let { coverage.merge(json.decodeFromString(it.coverageJson), current) } ?: current
        requireBuilt(manifest.syncSpaceId)
        coverage.requireRecoverable(manifest, previous)
        val recovery = SyncPagedSnapshotTail.Options(manifest, previous)
        owners.fence(options.accountId, manifest)
        drainWriters()
        SyncSnapshotTrace.suspendPhase("install.prepare_tail", manifest.snapshotBundleId) { tail.prepare(recovery) }
        if (started == null) database.withTransaction {
            database.syncGenesisDao().upsertBundle(bundle)
            SyncSnapshotInstallJournal.start(database, bundle, lanes, previous, options.now)
            database.syncRuntimeDao().upsertBinding(binding.copy(lifecycleState = "REBASE_PREPARE", updatedAt = options.now))
        }
        val count = if (started?.reason == "SNAPSHOT_BASELINE_READY") 0 else
            SyncSnapshotTrace.suspendPhase("install.records", manifest.snapshotBundleId) { installRecords(options) }
        SyncSnapshotTrace.suspendPhase("install.replay_tail", manifest.snapshotBundleId) { tail.replay(recovery) }
        requireBodies(manifest)
        publishCompleted(options, binding, previous)
        owners.releaseFence(manifest)
        store.lifecycle.completed(manifest.snapshotBundleId)
        return result(manifest, count)
    }

    /** 在 Reader 破坏性事务前校验全部代次及已安装 Edition 的类型能力。 */
    private suspend fun validateEntities(manifest: SyncPagedSnapshotManifest) {
        for (record in store.records(SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, kind = "ENTITY"))) {
            val entity = projection(record)
            val extension = extensions.firstOrNull { it.owns(entity.entityType) }
            if (SyncSnapshotRecordLane.entityLane(entity.entityType) == "AI_HISTORY") {
                checkNotNull(extension) { "REBASE_UNSAFE: Edition cannot materialize AI_HISTORY entity" }
                    .validateSnapshotEntity(manifest.syncSpaceId, entity)
            } else identities.validate(manifest.syncSpaceId, record.value)
        }
        check(manifest.lanes.none { it.replicationLaneId == "ARTICLE_STATE" } || manifest.lanes.any { it.replicationLaneId == "LIBRARY" }) {
            "SNAPSHOT_DEPENDENCY_MISSING: ARTICLE_STATE requires LIBRARY"
        }
    }

    /** 所有记录完整验证后按父优先安装；每次只持有当前实体/字段记录。 */
    private suspend fun installRecords(options: Options): Int {
        val manifest = options.manifest
        operationEvidence.stageSources(manifest, options.now)
        for (lane in manifest.lanes) metadata.restoreBlobs(SyncPagedMetadataRestore.Options(manifest, lane.replicationLaneId, options.now))
        metadata.restoreAliasGraph(SyncPagedMetadataRestore.Options(manifest, "CORE_META", options.now))
        var count = 0
        for (type in ENTITY_ORDER) {
            SyncSnapshotTrace.suspendPhase("install.entity.$type", manifest.snapshotBundleId) {
                val records = store.records(SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, kind = "ENTITY", entityType = type))
                // 别名投影需要挂起读取；在批次准备阶段完成，不能在 Sequence 或写事务中等待。
                val phase = SyncSnapshotReaderBatches.Phase(manifest, "entity:$type") {
                    aliasProjection.project(SyncPagedAliasProjection.Options(manifest, it))
                }
                val write: suspend (SyncSnapshotRecord) -> Unit = { record ->
                    materialize(options, record)
                    count++
                }
                if (type in READER_TYPES) batches.apply(phase, records, write) else batches.crossDatabase(phase, records, write)
                if (type == "article") materializeArticleAttachments(options)
            }
        }
        for (lane in manifest.lanes) SyncSnapshotTrace.suspendPhase("install.fields.${lane.replicationLaneId}", manifest.snapshotBundleId) {
            fields.restore(SyncPagedFieldRestore.Options(manifest, lane.replicationLaneId, options.now))
        }
        for (type in DELETE_ORDER) for (lane in manifest.lanes) metadata.restoreTombstones(
            SyncPagedMetadataRestore.Options(manifest, lane.replicationLaneId, options.now), type)
        metadata.restoreAliases(SyncPagedMetadataRestore.Options(manifest, "CORE_META", options.now))
        if (manifest.lanes.any { it.replicationLaneId == "CONFIG" }) config.restore(SyncPagedConfigRestore.Options(manifest.syncSpaceId, manifest.snapshotBundleId, options.now))
        database.withTransaction { SyncSnapshotInstallJournal.baselineReady(database, bundle(manifest), options.now) }
        return count
    }

    /** 只预约启用 AI 域中未在磁盘存在的正文，单个 hash 被多处引用也只计一次。 */
    private fun reserveBodies(manifest: SyncPagedSnapshotManifest) {
        store.lifecycle.budget.reserve(manifest.snapshotBundleId,
            manifest.lanes.sumOf { it.pageHashes.size.toLong() * SNAPSHOT_PAGE_BYTES })
        val seen = mutableSetOf<String>()
        var missing = 0L
        val filter = SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, "AI_HISTORY", "BLOB_MANIFEST")
        for (record in store.records(filter)) {
            val hash = record.value.getValue("hash").jsonPrimitive.content
            val bytes = record.value.getValue("totalBytes").jsonPrimitive.long
            if (seen.add(hash) && localBlobs.getBlobFile(hash)?.length() != bytes) missing += bytes
        }
        store.lifecycle.budget.bodies(manifest.snapshotBundleId, missing)
    }

    /** 修订变化只重做事务外证明；短发布事务失败回滚后重新准备，不在写连接内扫描。 */
    private suspend fun publishCompleted(options: Options, binding: SyncLocalSpaceBindingEntity, previous: SyncCoverageVector) {
        val manifest = options.manifest
        repeat(SNAPSHOT_PUBLICATION_ATTEMPTS) {
            SyncSnapshotCancellation.checkpoint()
            try {
                validation.verifyWithinProjectionBarrier(manifest)
                sourceCompletion.complete(manifest, options.now)
                database.withTransaction {
                    validation.verifyWithinDatabaseTransaction(manifest)
                    val currentBinding = checkNotNull(database.syncRuntimeDao().findBinding(options.accountId))
                    check(currentBinding.syncSpaceId == binding.syncSpaceId) { "SPACE_MISMATCH: install binding changed" }
                    coverage.finish(SyncPagedInstallCoverage.Finish(manifest, previous, options.now))
                    SyncSnapshotInstallJournal.record(database, bundle(manifest), manifest.lanes.map { it.replicationLaneId }.toSet(), options.now)
                    database.syncRuntimeDao().upsertBinding(currentBinding.copy(lifecycleState = "STAGING", updatedAt = options.now))
                }
                return
            } catch (error: IllegalStateException) {
                // 仅当前权威修订竞争可重准备；签名、权限和持久化错误照常失败。
                if (!snapshotRevisionConflict(error)) throw error
            }
        }
        snapshotNeedsStableInput()
    }

    /** 围栏已拒绝新写入，按库依次等先前 writer 退出；Reader 写事务不会等待 Chat。 */
    private suspend fun drainWriters() {
        database.withTransaction { Unit }
        for (extension in extensions) extension.drainSnapshotWriters()
    }

    /** 文件读取和 Reader 缓存写入在业务批次事务退出后执行，不占用写连接等待磁盘。 */
    private suspend fun materializeArticleAttachments(options: Options) {
        val filter = SyncPagedSnapshotStore.RecordFilter(options.manifest.snapshotBundleId, kind = "ENTITY", entityType = "article")
        for (record in store.records(filter)) {
            SyncSnapshotCancellation.checkpoint()
            reader.materializeAttachment(SyncPagedReaderRestore.Options(options.accountId, options.manifest.syncSpaceId, record.value, options.now))
        }
    }

    /** 全部跨库 receipt 完成仍须逐正文核对当前 winner、长度和摘要。 */
    private suspend fun requireBodies(manifest: SyncPagedSnapshotManifest) {
        if (manifest.lanes.none { it.replicationLaneId == "AI_HISTORY" }) return
        for (extension in extensions) extension.requirePagedBodies(manifest.syncSpaceId, manifest.snapshotBundleId)
    }

    /** Edition 投影携带当前 bundle/root 身份，跨库完成记录不能由页面顺序推断。 */
    private suspend fun materialize(options: Options, record: SyncSnapshotRecord) {
        val entity = projection(record)
        if (entity.entityType in READER_TYPES) {
            reader.materialize(SyncPagedReaderRestore.Options(options.accountId, options.manifest.syncSpaceId, record.value, options.now))
            return
        }
        val extension = checkNotNull(extensions.firstOrNull { it.owns(entity.entityType) })
        check(extension.materializePagedEntity(SyncPagedProjectionEntity(options.manifest.syncSpaceId,
            options.manifest.snapshotBundleId, options.manifest.rootHash, record.key, entity, options.now))) {
            "REBASE_UNSAFE: Edition did not materialize Snapshot entity"
        }
    }

    /** 所属数据库的 pending Outbox 也必须已构建，不能只检查 Reader 的状态。 */
    private suspend fun requireBuilt(space: String) {
        coverage.requireBuiltOutbox(space)
        check(extensions.none { it.hasPendingOutbox(space) }) { "REBASE_UNSAFE: build and sign Edition Outbox before installation" }
    }

    /** READY 标志同时约束 root 和安装 scope；ACTIVE 重复请求只返回真实完成状态。 */
    private suspend fun ready(binding: SyncLocalSpaceBindingEntity, bundle: SyncSnapshotBundleEntity, lanes: Set<String>): Boolean {
        if (binding.lifecycleState !in setOf("STAGING", "ACTIVE")) return false
        val completed = SyncSnapshotInstallJournal.read(database, bundle.syncSpaceId) ?: return false
        val stored = database.syncGenesisDao().findBundle(bundle.snapshotBundleId) ?: return false
        val scope = SyncSnapshotInstallJournal.scope(completed)
        return completed.targetSnapshotBundleId == bundle.snapshotBundleId && stored.rootHash == bundle.rootHash &&
            scope.rootHash == bundle.rootHash && scope.installedLanes.toSet() == lanes
    }

    /** 已启动的 baseline 不允许中途改变 root 或 scope，恢复必须使用原来的不可变清单。 */
    private fun requireSameStarted(started: SyncRecoveryCapsuleEntity?, manifest: SyncPagedSnapshotManifest, lanes: Set<String>) {
        if (started == null) return
        val scope = SyncSnapshotInstallJournal.scope(started)
        check(started.targetSnapshotBundleId == manifest.snapshotBundleId && scope.rootHash == manifest.rootHash && scope.installedLanes.toSet() == lanes) {
            "REBASE_UNSAFE: finish persisted Snapshot installation before changing baseline"
        }
    }

    /** ENTITY 只转换当前记录为 Edition 的正式投影载荷。 */
    private fun projection(record: SyncSnapshotRecord): SyncGenesisProjectionEntity = SyncGenesisProjectionEntity(
        record.value.getValue("entityType").jsonPrimitive.content, record.value.getValue("entitySyncId").jsonPrimitive.content,
        record.value.getValue("generation").jsonPrimitive.long, record.value.getValue("fields").jsonObject.toString())

    /** 主库只保存逻辑 lane 清单，不创建伪造的整 lane shard。 */
    private fun bundle(manifest: SyncPagedSnapshotManifest): SyncSnapshotBundleEntity = SyncSnapshotBundleEntity(
        snapshotBundleId = manifest.snapshotBundleId, syncSpaceId = manifest.syncSpaceId, snapshotClass = manifest.snapshotClass,
        schemaVersion = manifest.formatVersion, snapshotEpoch = INITIAL_EPOCH, crossDbCutId = manifest.crossDbCutId,
        replicationPolicyHash = manifest.policyHash, requiredCoreShardIdsJson = json.encodeToString(manifest.requiredCoreShardIds),
        shardDescriptorsJson = json.encodeToString(manifest.lanes), authStabilityCheckpointId = manifest.authStabilityCheckpoint,
        rootHash = manifest.rootHash, createdByDeviceId = manifest.authorDeviceId, createdAt = manifest.capturedAt)

    private fun result(manifest: SyncPagedSnapshotManifest, count: Int): AndroidSnapshotInstallResult =
        AndroidSnapshotInstallResult(manifest.snapshotBundleId, manifest.syncSpaceId, count, manifest.lanes.map { it.replicationLaneId })

    companion object {
        /** 分页 schema 使用初始逻辑 epoch，页面数和应用版本不参与 epoch。 */
        private const val INITIAL_EPOCH = 0L
        /** Reader 的直接业务投影类型，CONFIG 在外部仓库恢复阶段处理。 */
        private val READER_TYPES = setOf("group", "feed", "article")
        /** 所有依赖父实体先于子实体，CORE 只描述同步上下文而不覆写本机绑定。 */
        private val ENTITY_ORDER = listOf("group", "feed", "article", "conversation", "message", "tool_call", "context_ref",
            "evidence_block", "citation_ref", "citation_annotation", "conversation_article", "citation_annotation_ref")
        /** 删除按反向依赖执行，包含所有 CONFIG 摘要及别名边删除见证。 */
        private val DELETE_ORDER = ENTITY_ORDER.reversed() + listOf("filter_rule", "website_rule", "json_rule", "rsshub_settings",
            "website_parse_preference", "rsshub_subscription_source", "alias_edge")
    }
}
