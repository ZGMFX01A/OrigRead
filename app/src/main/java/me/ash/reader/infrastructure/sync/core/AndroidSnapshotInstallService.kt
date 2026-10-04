package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rsshub.RssHubInstance
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceUserSyncState
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

class SnapshotCorruptedError(message: String) : IllegalStateException(message)

class SnapshotDependencyMissingError(message: String) : IllegalStateException(message)

class SyncRebaseUnsafeException(message: String) : IllegalStateException(message)

class SyncLocalRecoverySnapshotRequiredException(message: String) : IllegalStateException(message)

data class LocalKnowledgeCapsule(
    val pendingOutboxCount: Int,
    val pendingOutboxIds: List<String>,
    val capturedAt: Long,
)

data class AndroidSnapshotInstallResult(
    val snapshotBundleId: String,
    val syncSpaceId: String,
    val materializedEntities: Int,
    val rebasedLanes: List<String>,
)

private val REQUIRED_SNAPSHOT_LANES = listOf(
    SyncReplicationLane.CORE_META.wireName,
    SyncReplicationLane.AUTH.wireName,
)
private val SUPPORTED_SNAPSHOT_LANES =
    SyncReplicationLane.entries.map(SyncReplicationLane::wireName).toSet()

/**
 * R10/R12 客户端快照安装与物化服务 (Android 端)。
 *
 * 职责：
 * 1. 严格完整性与合法性校验（ShardHash、RootHash、必需 Core Shard）；
 * 2. 在原子 Room 事务内解包物化；
 * 3. 对齐 Canonical Key，为新实体分配本地全新 LocalId 写入 mapping 表，彻底隔离远端 LocalId 污染；
 * 4. 依赖完整性校验（Article 依赖 Feed，Feed 依赖 Group），依赖缺失严禁静默吞掉，必须抛出 SnapshotDependencyMissingError 并回滚；
 * 5. 完整恢复 sync_field_version 与 sync_tombstone；
 * 6. 修正 Coverage 语义：严禁虚高 retainedPrefix，将 baseline 记录到 genesisCoverage。
 */
@Singleton
class AndroidSnapshotInstallService @Inject constructor(
    private val database: AndroidDatabase,
    private val filterRepository: ArticleFilterRepository,
    private val websiteRuleRepository: WebsiteRuleRepository,
    private val jsonRuleRepository: JsonRuleRepository,
    private val rssHubSettingsRepository: RssHubSettingsRepository,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    private val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
    private val signingKeys: SyncDeviceSigningKeyStore = SyncDeviceSigningKeyStore(),
    private val projectionExtensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension> = emptySet(),
    private val businessApplier: AndroidSyncBusinessApplier? = null,
) {
    @Inject lateinit var pagedInstaller: SyncPagedSnapshotInstaller

    /** 已完整验证的分页索引直接进入安装，LAN 调用方不得还原旧整包。 */
    suspend fun installPaged(options: SyncPagedSnapshotInstaller.Options): AndroidSnapshotInstallResult = pagedInstaller.install(options)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val aliasResolver =
        AndroidSyncAliasResolver(database).withFeedDeleteCleanup { localFeedId ->
            filterRepository.deleteByFeed(localFeedId)
            websiteParsePreferenceRepository.delete(localFeedId)
            rssHubSubscriptionRepository.replaceSyncSource(localFeedId, null)
        }
    private val blobState = SyncBlobStateService(database)

    private interface SnapshotShardSource {
        val descriptors: List<GenesisShardDescriptor>
        suspend fun load(lane: String): SyncSnapshotShardEntity
    }

    private class InMemorySnapshotShardSource(
        shards: List<SyncSnapshotShardEntity>,
    ) : SnapshotShardSource {
        private val byLane = shards.associateBy(SyncSnapshotShardEntity::replicationLaneId)
        override val descriptors: List<GenesisShardDescriptor> =
            shards.map {
                GenesisShardDescriptor(
                    it.replicationLaneId,
                    it.shardHash,
                    it.frontierByActorJson,
                )
            }

        override suspend fun load(lane: String): SyncSnapshotShardEntity =
            requireNotNull(byLane[lane]) { "Snapshot shard is missing for lane $lane" }
    }

    suspend fun installWire(
        localAccountId: Int,
        wire: SyncSnapshotBundleWire,
        trustedAuthorKey: SyncPeerKey,
        now: Long = System.currentTimeMillis(),
        selectedLanes: Set<String>? = null,
    ): AndroidSnapshotInstallResult {
        if (wire.hashSchemaVersion != SyncSnapshotWireCodec.HASH_SCHEMA_VERSION) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: legacy Snapshot wire does not carry merge-complete hash metadata",
            )
        }
        val author = wire.authorDeviceId
            ?: throw SyncRebaseUnsafeException("REBASE_UNSAFE: snapshot author is missing")
        if (trustedAuthorKey.status != "ACTIVE") {
            throw SyncRebaseUnsafeException("REBASE_UNSAFE: snapshot author is revoked")
        }
        if (wire.snapshotClass == SyncSnapshotClass.GC_BASELINE.name &&
            wire.authStabilityCheckpoint.isNullOrBlank()
        ) {
            throw SyncRebaseUnsafeException("REBASE_UNSAFE: GC baseline has no AuthStabilityCheckpoint")
        }
        if (wire.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name &&
            (wire.coverageCommitment.isNullOrBlank() || wire.authStabilityCheckpoint.isNullOrBlank())
        ) {
            throw SyncRebaseUnsafeException("REBASE_UNSAFE: recovery snapshot proof is incomplete")
        }
        validateStabilityProof(wire)
        try {
            SyncSnapshotWireCodec.verifyRichWire(wire, trustedAuthorKey.publicKeySpkiBase64, signingKeys)
            return install(
                localAccountId = localAccountId,
                bundle = SyncSnapshotWireCodec.toInternalBundle(wire),
                shards = SyncSnapshotWireCodec.toInternalShards(wire),
                selectedLanes = selectedLanes,
                now = now,
            )
        } catch (error: SnapshotCorruptedError) {
            throw error
        } catch (error: SyncRebaseUnsafeException) {
            throw error
        } catch (error: Throwable) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: snapshot manifest is incomplete: " + error.message,
            )
        }
    }

    suspend fun installStream(
        localAccountId: Int,
        manifest: SyncSnapshotStreamManifestWire,
        trustedAuthorKey: SyncPeerKey,
        shardLoader: (String) -> SyncSnapshotShardWire,
        now: Long = System.currentTimeMillis(),
        selectedLanes: Set<String>? = null,
    ): AndroidSnapshotInstallResult {
        if (manifest.hashSchemaVersion != SyncSnapshotWireCodec.HASH_SCHEMA_VERSION) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: streaming Snapshot requires the rich hash schema",
            )
        }
        if (manifest.authorDeviceId.isNullOrBlank()) {
            throw SyncRebaseUnsafeException("REBASE_UNSAFE: snapshot author is missing")
        }
        if (trustedAuthorKey.status != "ACTIVE") {
            throw SyncRebaseUnsafeException("REBASE_UNSAFE: snapshot author is revoked")
        }
        if (
            manifest.snapshotClass == SyncSnapshotClass.GC_BASELINE.name &&
                manifest.authStabilityCheckpoint.isNullOrBlank()
        ) {
            throw SyncRebaseUnsafeException("REBASE_UNSAFE: GC baseline has no AuthStabilityCheckpoint")
        }
        if (
            manifest.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name &&
                (manifest.coverageCommitment.isNullOrBlank() ||
                    manifest.authStabilityCheckpoint.isNullOrBlank())
        ) {
            throw SyncRebaseUnsafeException("REBASE_UNSAFE: recovery snapshot proof is incomplete")
        }

        val metadataWire = SyncSnapshotWireCodec.fromStreamManifest(manifest, emptyList())
        validateStabilityProof(metadataWire)
        try {
            SyncSnapshotWireCodec.verifyRichStream(
                manifest = manifest,
                publicKeySpkiBase64 = trustedAuthorKey.publicKeySpkiBase64,
                keyStore = signingKeys,
                shardLoader = shardLoader,
            )
            val source =
                object : SnapshotShardSource {
                    override val descriptors: List<GenesisShardDescriptor> =
                        manifest.shardDescriptors.map {
                            GenesisShardDescriptor(
                                it.replicationLaneId,
                                it.contentHash,
                                it.frontierJson,
                            )
                        }

                    override suspend fun load(lane: String): SyncSnapshotShardEntity =
                        SyncSnapshotWireCodec.toInternalShard(
                            snapshotBundleId = manifest.snapshotBundleId,
                            syncSpaceId = manifest.syncSpaceId,
                            shard = shardLoader(lane),
                        )
                }
            return installFromSource(
                localAccountId = localAccountId,
                bundle = SyncSnapshotWireCodec.toInternalBundle(manifest),
                source = source,
                now = now,
                selectedLanes = selectedLanes,
            )
        } catch (error: SnapshotCorruptedError) {
            throw error
        } catch (error: SyncRebaseUnsafeException) {
            throw error
        } catch (error: Throwable) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: streamed snapshot manifest is incomplete: " + error.message,
            )
        }
    }

    private suspend fun validateStabilityProof(wire: SyncSnapshotBundleWire) {
        if (
            wire.snapshotClass != SyncSnapshotClass.GC_BASELINE.name &&
            wire.snapshotClass != SyncSnapshotClass.BOOTSTRAP_RECOVERY.name
        ) {
            return
        }
        val history =
            database.syncAuthLedgerDao().list(wire.syncSpaceId)
                .map { SyncAuthWireCodec.decode(it.authObjectJson) }
                .sortedWith(AndroidSyncAuthLedgerService.AUTH_ORDER)
        val checkpoint =
            history.firstOrNull {
                it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT &&
                    it.authObjectId == wire.authStabilityCheckpoint && it.syncSpaceId == wire.syncSpaceId
            }
                ?: throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: stable Snapshot has no verified local AuthStabilityCheckpoint"
                )
        val payload =
            runCatching { json.parseToJsonElement(checkpoint.payloadJson).jsonObject }
                .getOrElse {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: AuthStabilityCheckpoint payload is invalid"
                    )
                }
        val acceptedElement =
            payload["acceptedPrefixByActorLane"]?.jsonObject
                ?: throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: AuthStabilityCheckpoint accepted coverage is missing"
                )
        val accepted =
            acceptedElement.entries.associate { (lane, actorsElement) ->
                lane to actorsElement.jsonObject.entries.associate { (actor, prefixElement) ->
                    actor to
                        (prefixElement.jsonPrimitive.longOrNull
                            ?: throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: AuthStabilityCheckpoint coverage is malformed"
                            ))
                }
            }
        if (!coverageDominates(accepted, wire.coverage)) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: Snapshot coverage exceeds the current stable authorization proof"
            )
        }
        if (wire.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name) {
            if (payload["acceptedSnapshotBundleId"]?.jsonPrimitive?.content != wire.snapshotBundleId) {
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: OWNER recovery acceptance does not name this Snapshot"
                )
            }
            if (wire.coverageCommitment != SyncSnapshotWireCodec.coverageCommitment(wire.coverage)) {
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: recovery Snapshot coverage commitment is invalid"
                )
            }
        }
    }

    private fun coverageDominates(left: SyncCoverage, right: SyncCoverage): Boolean =
        right.all { (lane, actors) ->
            actors.all { (actor, prefix) -> (left[lane]?.get(actor) ?: 0L) >= prefix }
        }

    suspend fun install(
        localAccountId: Int,
        bundle: SyncSnapshotBundleEntity,
        shards: List<SyncSnapshotShardEntity>,
        now: Long = System.currentTimeMillis(),
        selectedLanes: Set<String>? = null,
    ): AndroidSnapshotInstallResult =
        installFromSource(
            localAccountId = localAccountId,
            bundle = bundle,
            source = InMemorySnapshotShardSource(shards),
            now = now,
            selectedLanes = selectedLanes,
        )

    private suspend fun installFromSource(
        localAccountId: Int,
        bundle: SyncSnapshotBundleEntity,
        source: SnapshotShardSource,
        now: Long,
        selectedLanes: Set<String>?,
    ): AndroidSnapshotInstallResult {
        require(bundle.snapshotBundleId.isNotBlank()) { "Snapshot bundleId must not be blank" }
        require(bundle.syncSpaceId.isNotBlank()) { "Snapshot syncSpaceId must not be blank" }
        val descriptors = source.descriptors
        require(descriptors.map { it.replicationLaneId }.distinct().size == descriptors.size) {
            "Duplicate snapshot lane"
        }
        if (descriptors.any { it.replicationLaneId !in SUPPORTED_SNAPSHOT_LANES }) {
            throw SyncRebaseUnsafeException("REBASE_UNSAFE: snapshot contains an unsupported lane")
        }

        val binding = database.syncRuntimeDao().findBinding(localAccountId)
            ?: error("Local account $localAccountId has no sync space binding")
        if (binding.syncSpaceId != bundle.syncSpaceId) {
            error("Snapshot bundle syncSpaceId ${bundle.syncSpaceId} does not match binding ${binding.syncSpaceId}")
        }
        // 校验 REBASE 安全性
        if (bundle.rootHash.isBlank() || bundle.replicationPolicyHash.isBlank()) {
            throw SyncRebaseUnsafeException("Cannot rebase onto snapshot with empty rootHash or replicationPolicyHash")
        }

        // 1. 严格完整性校验（R10-04）
        val manifestLanes = descriptors.map { it.replicationLaneId }.toSet()
        for (requiredLane in REQUIRED_SNAPSHOT_LANES) {
            if (requiredLane !in manifestLanes) {
                throw SnapshotCorruptedError("Missing required core shard: $requiredLane")
            }
        }

        // 校验每个 shard 的 shardHash
        for (lane in manifestLanes) {
            val shard = source.load(lane)
            require(
                shard.syncSpaceId == bundle.syncSpaceId &&
                    shard.snapshotBundleId == bundle.snapshotBundleId
            ) {
                "Snapshot shard belongs to a different bundle or space"
            }
            val material = GenesisShardHashMaterial(
                replicationLaneId = shard.replicationLaneId,
                frontierByActorJson = shard.frontierByActorJson,
                entityStateJson = shard.entityStateJson,
                fieldVersionStateJson = shard.fieldVersionStateJson,
                causalMergeMetadataJson = shard.causalMergeMetadataJson,
                genesisCoverageJson = shard.genesisCoverageJson,
                deletionSummaryJson = shard.deletionSummaryJson,
                generationSummaryJson = shard.generationSummaryJson,
                blobManifestIndexJson = shard.blobManifestIndexJson,
                blobReferenceIndexJson = shard.blobReferenceIndexJson,
            )
            val expectedShardHash = SyncGenesisCodec.hashCanonicalJson(json.encodeToString(material))
            if (shard.shardHash != expectedShardHash) {
                throw SnapshotCorruptedError(
                    "Shard hash mismatch for lane ${shard.replicationLaneId}: expected $expectedShardHash, got ${shard.shardHash}",
                )
            }
        }

        // 校验 bundle 的 rootHash（descriptors 规范按 replicationLaneId 排序）
        val sortedDescriptors = descriptors.sortedBy(GenesisShardDescriptor::replicationLaneId)
        val descriptorsJson = json.encodeToString(sortedDescriptors)
        val bundleMaterial = GenesisBundleHashMaterial(
            schemaVersion = bundle.schemaVersion,
            snapshotEpoch = bundle.snapshotEpoch,
            crossDbCutId = bundle.crossDbCutId,
            replicationPolicyHash = bundle.replicationPolicyHash,
            requiredCoreShardIdsJson = bundle.requiredCoreShardIdsJson,
            shardDescriptorsJson = descriptorsJson,
        )
        val expectedRootHash = SyncGenesisCodec.hashCanonicalJson(json.encodeToString(bundleMaterial))
        if (bundle.rootHash != expectedRootHash) {
            throw SnapshotCorruptedError(
                "Bundle rootHash mismatch: expected $expectedRootHash, got ${bundle.rootHash}",
            )
        }

        val presentLanes = selectedLanes ?: manifestLanes
        if (!manifestLanes.containsAll(presentLanes)) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: selected Snapshot lanes are not present in the signed manifest"
            )
        }
        for (requiredLane in REQUIRED_SNAPSHOT_LANES) {
            if (requiredLane !in presentLanes) {
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: required core Snapshot lane is paused or unsupported: $requiredLane"
                )
            }
        }
        validateProjectionShards(
            syncSpaceId = bundle.syncSpaceId,
            source = source,
            presentLanes = presentLanes,
        )

        val existingStagedBundle = database.syncGenesisDao().findBundle(bundle.snapshotBundleId)
        val ready = SyncSnapshotInstallJournal.read(database, bundle.syncSpaceId)
        if (binding.lifecycleState == SyncSpaceLifecycleState.STAGING.name &&
            ready?.targetSnapshotBundleId == bundle.snapshotBundleId &&
            existingStagedBundle?.rootHash == bundle.rootHash &&
            SyncSnapshotInstallJournal.scope(ready).let { it.rootHash == bundle.rootHash && it.installedLanes.containsAll(presentLanes) }
        ) {
            return AndroidSnapshotInstallResult(bundle.snapshotBundleId, bundle.syncSpaceId, 0, presentLanes.toList())
        }
        val started = SyncSnapshotInstallJournal.readStarted(database, bundle.syncSpaceId)
        if (started != null) {
            val scope = SyncSnapshotInstallJournal.scope(started)
            check(started.targetSnapshotBundleId == bundle.snapshotBundleId && scope.rootHash == bundle.rootHash &&
                scope.installedLanes.toSet() == presentLanes) {
                "REBASE_UNSAFE: finish the persisted Snapshot install scope before changing the baseline"
            }
        }
        // R10 LocalKnowledgeCapsule: existing business rows are not themselves a reason to reject
        // rebase. The safety boundary is whether every target-uncovered known change can be
        // reconstructed from retained Operation state (plus fully built Outbox).
        val pendingOutbox = database.syncOutboxDao().listPending(bundle.syncSpaceId, Int.MAX_VALUE)
        val extensionHasPendingOutbox =
            projectionExtensions.any { it.hasPendingOutbox(bundle.syncSpaceId) }
        if (pendingOutbox.isNotEmpty() || extensionHasPendingOutbox) {
            persistRecoveryCapsule(
                bundle = bundle,
                targetCoverage = snapshotCoverage(descriptors, presentLanes),
                presentLanes = presentLanes,
                pendingOutboxIds = pendingOutbox.map { it.outboxId },
                reason = "PENDING_OUTBOX_REQUIRES_PREPARATION",
                now = now,
            )
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: local Outbox must be built and signed before Snapshot rebase",
            )
        }
        val currentCoverage = currentCoverageVector(bundle.syncSpaceId)
        val coverageBeforeRebase = if (started == null) currentCoverage else
            mergeRecoveryCoverage(json.decodeFromString(started.coverageJson), currentCoverage)
        val targetCoverage = snapshotCoverage(descriptors, presentLanes)
        if (
            snapshotIsBehindStableGc(
                targetCoverage = targetCoverage,
                stableGcCoverage = coverageBeforeRebase.stableGc,
                presentLanes = presentLanes,
            )
        ) {
            persistRecoveryCapsule(
                bundle = bundle,
                targetCoverage = targetCoverage,
                presentLanes = presentLanes,
                pendingOutboxIds = emptyList(),
                reason = "LOCAL_RECOVERY_SNAPSHOT_REQUIRED",
                now = now,
            )
            throw SyncLocalRecoverySnapshotRequiredException(
                "LOCAL_RECOVERY_REQUIRED: target Snapshot is behind locally compacted stable history"
            )
        }
        val retainedTail = mutableListOf<SyncOperationEntity>()
        for (operation in database.syncOperationDao().listAllForRecovery(bundle.syncSpaceId)) {
            if (operation.buildStatus != SyncOperationBuildStatus.SIGNED.name) continue
            if (operation.replicationLaneId !in presentLanes) continue
            if (
                operation.sequence <=
                    (targetCoverage[operation.replicationLaneId]
                        ?.get(operation.actorIncarnationId) ?: 0L)
            ) continue
            if (database.syncInboxDao().find(operation.operationId)?.state == "REJECTED") continue
            retainedTail += operation
        }
        assertRetainedTailIsComplete(
            targetCoverage = targetCoverage,
            retainedCoverage = coverageBeforeRebase.retained,
            operations = retainedTail,
            presentLanes = presentLanes,
        )
        val replayTail = mutableListOf<SyncOperationEntity>()
        for (operation in retainedTail) {
            if (database.syncInboxDao().find(operation.operationId)?.state in setOf(null, "APPLIED")) {
                replayTail += operation
            }
        }
        if (retainedTail.isNotEmpty() && started == null) {
            persistRecoveryCapsule(
                bundle = bundle,
                targetCoverage = targetCoverage,
                presentLanes = presentLanes,
                pendingOutboxIds = emptyList(),
                reason = "REBASE_PREPARE_RETAINED_TAIL",
                now = now,
            )
        }
        var materializedCount = 0
        val rebasedLanes = mutableListOf<String>()

        // REBASE_PREPARE must be durable before touching external CONFIG repositories. Those
        // repositories are not part of the Room transaction, so a later rollback must never
        // expose a partially materialized Snapshot as ACTIVE.
        runInTransaction {
            database.syncGenesisDao().upsertBundle(bundle)
            for (lane in manifestLanes) {
                database.syncGenesisDao().upsertShard(source.load(lane))
            }
            SyncSnapshotInstallJournal.start(database, bundle, presentLanes, coverageBeforeRebase, now)
            database.syncRuntimeDao().upsertBinding(
                binding.copy(
                    lifecycleState = SyncSpaceLifecycleState.REBASE_PREPARE.name,
                    updatedAt = now,
                )
            )
        }

            // 2. 在一个原子事务内完成 Room 状态解包与物化；外部 CONFIG 写失败时，
            // REBASE_PREPARE 仍保留在事务之外，下一次可安全重入。
            runInTransaction {
                database.syncGenesisDao().upsertBundle(bundle)
                for (lane in manifestLanes) {
                    database.syncGenesisDao().upsertShard(source.load(lane))
                }

            if (
                SyncReplicationLane.ARTICLE_STATE.wireName in presentLanes &&
                SyncReplicationLane.LIBRARY.wireName !in presentLanes
            ) {
                throw SnapshotDependencyMissingError(
                    "ARTICLE_STATE Snapshot requires LIBRARY in the same selected Snapshot scope"
                )
            }

            // Snapshot 只恢复 Blob metadata/reference；没有实际 bytes 时绝不能标 READY。
            for (lane in presentLanes) {
                val shard = source.load(lane)
                restoreBlobIndexes(bundle.syncSpaceId, shard, now)
            }

            // 3. 解析并物化 CORE_META
            run {
                val coreShard = source.load(SyncReplicationLane.CORE_META.wireName)
                runCatching { json.parseToJsonElement(coreShard.entityStateJson) }
                    .getOrElse {
                        throw SnapshotCorruptedError(
                            "Corrupted CORE_META entityStateJson: " + it.message
                        )
                    }
            }
            rebasedLanes.add(SyncReplicationLane.CORE_META.wireName)

            // AUTH bytes are exchanged and verified before Snapshot install. The AUTH shard must
            // describe only that already-verified ledger; Snapshot data cannot mint authorization.
            run {
                val authShard = source.load(SyncReplicationLane.AUTH.wireName)
                validateAuthShard(bundle.syncSpaceId, authShard.entityStateJson)
            }
            rebasedLanes.add(SyncReplicationLane.AUTH.wireName)

            // 4. 解析并物化 LIBRARY（Group 与 Feed）
            val groupMappingBySyncId = mutableMapOf<String, String>() // syncId -> localGroupId
            val feedMappingBySyncId = mutableMapOf<String, String>() // syncId -> localFeedId
            if (SyncReplicationLane.LIBRARY.wireName in presentLanes) {
                val libraryShard = source.load(SyncReplicationLane.LIBRARY.wireName)
                val libraryState = decodeLibraryState(libraryShard.entityStateJson)

            // 物化 Groups
            for (groupSnapshot in libraryState.groups) {
                if (groupSnapshot.syncId.isBlank()) {
                    throw SnapshotCorruptedError("Snapshot group has a blank syncId")
                }
                val groupGeneration =
                    generationFor(
                        libraryShard,
                        SyncEntityType.GROUP.wireName,
                        groupSnapshot.syncId,
                    )
                if (groupGeneration < 0L) {
                    throw SnapshotCorruptedError(
                        "Snapshot group ${groupSnapshot.syncId} has invalid generation"
                    )
                }
                var existingMapping = database.syncIdentityMappingDao().findBySyncId(
                    bundle.syncSpaceId, SyncEntityType.GROUP.wireName, groupSnapshot.syncId,
                )
                if (existingMapping != null) {
                    if (groupGeneration < existingMapping.generation) {
                        throw SyncLocalRecoverySnapshotRequiredException(
                            "LOCAL_RECOVERY_REQUIRED: Snapshot group ${groupSnapshot.syncId} generation " +
                                "$groupGeneration is behind local generation ${existingMapping.generation}"
                        )
                    }
                    if (groupGeneration > existingMapping.generation) {
                        existingMapping =
                            existingMapping.copy(generation = groupGeneration, updatedAt = now)
                                .also { database.syncIdentityMappingDao().update(it) }
                    }
                }
                val localGroupId = when {
                    existingMapping != null -> {
                        val existingGroup = database.groupDao().queryById(existingMapping.localId)
                        if (existingGroup != null) {
                            check(existingGroup.accountId == localAccountId) { "Snapshot group belongs to another account" }
                            database.groupDao().updateAll(listOf(existingGroup.copy(name = groupSnapshot.name)))
                        } else {
                            database.groupDao().insertAll(listOf(Group(id = existingMapping.localId,
                                name = groupSnapshot.name, accountId = localAccountId)))
                        }
                        existingMapping.localId
                    }
                    else -> {
                        val newLocalId = UUID.randomUUID().toString()
                        database.groupDao().insertAll(
                            listOf(Group(id = newLocalId, name = groupSnapshot.name, accountId = localAccountId))
                        )
                        database.syncIdentityMappingDao().insert(
                            SyncIdentityMappingEntity(
                                syncSpaceId = bundle.syncSpaceId,
                                entityType = SyncEntityType.GROUP.wireName,
                                localId = newLocalId,
                                syncId = groupSnapshot.syncId,
                                canonicalKey = null,
                                generation = groupGeneration,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                        newLocalId
                    }
                }
                groupMappingBySyncId[groupSnapshot.syncId] = localGroupId
                materializedCount++
            }

            // 物化 Feeds
            val localFeedsByUrl = database.feedDao().queryAll(localAccountId).associateBy { it.url }
            for (feedSnapshot in libraryState.feeds) {
                if (feedSnapshot.syncId.isBlank() || feedSnapshot.groupSyncId.isBlank()) {
                    throw SnapshotCorruptedError(
                        "Snapshot Feed identity or groupSyncId is blank"
                    )
                }
                val feedGeneration =
                    generationFor(
                        libraryShard,
                        SyncEntityType.FEED.wireName,
                        feedSnapshot.syncId,
                    )
                if (feedGeneration < 0L) {
                    throw SnapshotCorruptedError(
                        "Snapshot Feed ${feedSnapshot.syncId} has invalid generation"
                    )
                }
                if (feedSnapshot.groupGeneration != null && feedSnapshot.groupGeneration < 0L) {
                    throw SnapshotCorruptedError(
                        "Feed ${feedSnapshot.syncId} has invalid groupGeneration"
                    )
                }
                val targetLocalGroupId = groupMappingBySyncId[feedSnapshot.groupSyncId]
                    ?: throw SnapshotDependencyMissingError(
                        "Feed ${feedSnapshot.syncId} (${feedSnapshot.name}) missing dependent group mapping ${feedSnapshot.groupSyncId}",
                    )
                val parentGroupMapping =
                    database.syncIdentityMappingDao().findBySyncId(
                        bundle.syncSpaceId,
                        SyncEntityType.GROUP.wireName,
                        feedSnapshot.groupSyncId,
                    ) ?: throw SnapshotDependencyMissingError(
                        "Feed ${feedSnapshot.syncId} missing dependent group identity ${feedSnapshot.groupSyncId}",
                    )
                if (feedSnapshot.groupGeneration != null) {
                    if (parentGroupMapping.generation != feedSnapshot.groupGeneration) {
                        throw SnapshotCorruptedError(
                            "Feed ${feedSnapshot.syncId} references group ${feedSnapshot.groupSyncId} " +
                                "generation ${feedSnapshot.groupGeneration}, but mapping is generation ${parentGroupMapping.generation}"
                        )
                    }
                } else if (parentGroupMapping.generation > 0L) {
                    throw SnapshotCorruptedError(
                        "Feed ${feedSnapshot.syncId} omits groupGeneration for revived group ${feedSnapshot.groupSyncId}"
                    )
                }

                var existingMapping = database.syncIdentityMappingDao().findBySyncId(
                    bundle.syncSpaceId, SyncEntityType.FEED.wireName, feedSnapshot.syncId,
                )
                if (existingMapping != null) {
                    if (feedGeneration < existingMapping.generation) {
                        throw SyncLocalRecoverySnapshotRequiredException(
                            "LOCAL_RECOVERY_REQUIRED: Snapshot feed ${feedSnapshot.syncId} generation " +
                                "$feedGeneration is behind local generation ${existingMapping.generation}"
                        )
                    }
                    if (feedGeneration > existingMapping.generation) {
                        existingMapping =
                            existingMapping.copy(generation = feedGeneration, updatedAt = now)
                                .also { database.syncIdentityMappingDao().update(it) }
                    }
                }
                val resolvedSourceType =
                    runCatching { SourceType.valueOf(feedSnapshot.sourceType.uppercase()) }
                        .getOrElse {
                            throw SnapshotCorruptedError(
                                "Feed ${feedSnapshot.syncId} has unsupported sourceType ${feedSnapshot.sourceType}"
                            )
                        }
                val canonicalFeedKey =
                    SyncCanonicalIdentity.feedCandidateKey(resolvedSourceType, feedSnapshot.url)

                val localFeedId = when {
                    existingMapping != null -> {
                        if (existingMapping.canonicalKey != canonicalFeedKey) {
                            database.syncIdentityMappingDao().update(
                                existingMapping.copy(
                                    canonicalKey = canonicalFeedKey,
                                    updatedAt = now,
                                )
                            )
                        }
                        val existingFeed = database.feedDao().queryById(existingMapping.localId)
                        if (existingFeed != null) {
                            database.feedDao().updateAll(
                                listOf(
                                    existingFeed.copy(
                                        name = feedSnapshot.name,
                                        icon = feedSnapshot.icon ?: existingFeed.icon,
                                        groupId = targetLocalGroupId,
                                        isNotification = feedSnapshot.isNotification,
                                        isFullContent = feedSnapshot.isFullContent,
                                        isBrowser = feedSnapshot.isBrowser,
                                        sourceType = resolvedSourceType,
                                    )
                                )
                            )
                        }
                        existingMapping.localId
                    }
                    localFeedsByUrl.containsKey(feedSnapshot.url) -> {
                        val matched = localFeedsByUrl.getValue(feedSnapshot.url)
                        database.feedDao().updateAll(
                            listOf(
                                matched.copy(
                                    name = feedSnapshot.name,
                                    icon = feedSnapshot.icon ?: matched.icon,
                                    groupId = targetLocalGroupId,
                                    isNotification = feedSnapshot.isNotification,
                                    isFullContent = feedSnapshot.isFullContent,
                                    isBrowser = feedSnapshot.isBrowser,
                                    sourceType = resolvedSourceType,
                                )
                            )
                        )
                        database.syncIdentityMappingDao().insert(
                            SyncIdentityMappingEntity(
                                syncSpaceId = bundle.syncSpaceId,
                                entityType = SyncEntityType.FEED.wireName,
                                localId = matched.id,
                                syncId = feedSnapshot.syncId,
                                canonicalKey = canonicalFeedKey,
                                generation = feedGeneration,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                        matched.id
                    }
                    else -> {
                        val newLocalId = "${localAccountId}$${UUID.randomUUID()}"
                        val newFeed = Feed(
                            id = newLocalId,
                            name = feedSnapshot.name,
                            icon = feedSnapshot.icon,
                            url = feedSnapshot.url,
                            groupId = targetLocalGroupId,
                            accountId = localAccountId,
                            isNotification = feedSnapshot.isNotification,
                            isFullContent = feedSnapshot.isFullContent,
                            isBrowser = feedSnapshot.isBrowser,
                            sourceType = resolvedSourceType,
                        )
                        database.feedDao().insertAll(listOf(newFeed))
                        database.syncIdentityMappingDao().insert(
                            SyncIdentityMappingEntity(
                                syncSpaceId = bundle.syncSpaceId,
                                entityType = SyncEntityType.FEED.wireName,
                                localId = newLocalId,
                                syncId = feedSnapshot.syncId,
                                canonicalKey = canonicalFeedKey,
                                generation = feedGeneration,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                        newLocalId
                    }
                }
                feedMappingBySyncId[feedSnapshot.syncId] = localFeedId
                materializedCount++
            }
            rebasedLanes.add(SyncReplicationLane.LIBRARY.wireName)
            }

            // 5. 解析并物化 ARTICLE_STATE（Article）
            if (SyncReplicationLane.ARTICLE_STATE.wireName in presentLanes) {
                val articleShard = source.load(SyncReplicationLane.ARTICLE_STATE.wireName)
                val articleState = decodeArticleState(articleShard.entityStateJson)

            for (articleSnapshot in articleState.articles) {
                if (articleSnapshot.syncId.isBlank() || articleSnapshot.feedSyncId.isBlank()) {
                    throw SnapshotCorruptedError(
                        "Snapshot Article identity or feedSyncId is blank"
                    )
                }
                val articleGeneration =
                    generationFor(
                        articleShard,
                        SyncEntityType.ARTICLE.wireName,
                        articleSnapshot.syncId,
                    )
                if (articleGeneration < 0L) {
                    throw SnapshotCorruptedError(
                        "Snapshot Article ${articleSnapshot.syncId} has invalid generation"
                    )
                }
                if (articleSnapshot.feedGeneration != null && articleSnapshot.feedGeneration < 0L) {
                    throw SnapshotCorruptedError(
                        "Article ${articleSnapshot.syncId} has invalid feedGeneration"
                    )
                }
                val targetLocalFeedId = feedMappingBySyncId[articleSnapshot.feedSyncId]
                    ?: throw SnapshotDependencyMissingError(
                        "Article ${articleSnapshot.syncId} missing dependent feed mapping ${articleSnapshot.feedSyncId}",
                    )
                val parentFeedMapping =
                    database.syncIdentityMappingDao().findBySyncId(
                        bundle.syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        articleSnapshot.feedSyncId,
                    ) ?: throw SnapshotDependencyMissingError(
                        "Article ${articleSnapshot.syncId} missing dependent feed identity ${articleSnapshot.feedSyncId}",
                    )
                if (articleSnapshot.feedGeneration != null) {
                    if (parentFeedMapping.generation != articleSnapshot.feedGeneration) {
                        throw SnapshotCorruptedError(
                            "Article ${articleSnapshot.syncId} references feed ${articleSnapshot.feedSyncId} " +
                                "generation ${articleSnapshot.feedGeneration}, but mapping is generation ${parentFeedMapping.generation}"
                        )
                    }
                } else if (parentFeedMapping.generation > 0L) {
                    throw SnapshotCorruptedError(
                        "Article ${articleSnapshot.syncId} omits feedGeneration for revived feed ${articleSnapshot.feedSyncId}"
                    )
                }
                val targetFeed =
                    database.feedDao().queryById(targetLocalFeedId)
                        ?: throw SnapshotDependencyMissingError(
                            "Article ${articleSnapshot.syncId} missing local feed ${articleSnapshot.feedSyncId}",
                        )
                val canonicalArticleKey =
                    SyncCanonicalIdentity.articleCandidateKey(
                        SyncCanonicalIdentity.feedCandidateKey(
                            targetFeed.sourceType,
                            targetFeed.url,
                        ),
                        articleSnapshot.link,
                    )

                var existingMapping = database.syncIdentityMappingDao().findBySyncId(
                    bundle.syncSpaceId, SyncEntityType.ARTICLE.wireName, articleSnapshot.syncId,
                )
                if (existingMapping != null) {
                    if (articleGeneration < existingMapping.generation) {
                        throw SyncLocalRecoverySnapshotRequiredException(
                            "LOCAL_RECOVERY_REQUIRED: Snapshot article ${articleSnapshot.syncId} generation " +
                                "$articleGeneration is behind local generation ${existingMapping.generation}"
                        )
                    }
                    if (articleGeneration > existingMapping.generation) {
                        existingMapping =
                            existingMapping.copy(generation = articleGeneration, updatedAt = now)
                                .also { database.syncIdentityMappingDao().update(it) }
                    }
                }
                val localArticleId = if (existingMapping != null) {
                    if (existingMapping.canonicalKey != canonicalArticleKey) {
                        database.syncIdentityMappingDao().update(
                            existingMapping.copy(
                                canonicalKey = canonicalArticleKey,
                                updatedAt = now,
                            )
                        )
                    }
                    val existing = database.articleDao().queryById(existingMapping.localId)
                    if (existing != null) {
                        database.articleDao().update(
                            existing.article.copy(
                                feedId = targetLocalFeedId,
                                title = articleSnapshot.title,
                                author = articleSnapshot.author,
                                link = articleSnapshot.link,
                                date = Date(articleSnapshot.date),
                                shortDescription = articleSnapshot.description,
                                rawDescription = articleSnapshot.contentHtml,
                                img = articleSnapshot.imageUrl,
                                isUnread = articleSnapshot.isUnread,
                                isStarred = articleSnapshot.isStarred,
                                isReadLater = articleSnapshot.isReadLater,
                            )
                        )
                    }
                    existingMapping.localId
                } else {
                    val newLocalId = UUID.randomUUID().toString()
                    val newArticle = Article(
                        id = newLocalId,
                        date = Date(articleSnapshot.date),
                        title = articleSnapshot.title,
                        author = articleSnapshot.author,
                        rawDescription = articleSnapshot.contentHtml,
                        shortDescription = articleSnapshot.description,
                        img = articleSnapshot.imageUrl,
                        link = articleSnapshot.link,
                        feedId = targetLocalFeedId,
                        accountId = localAccountId,
                        isUnread = articleSnapshot.isUnread,
                        isStarred = articleSnapshot.isStarred,
                        isReadLater = articleSnapshot.isReadLater,
                    )
                    database.articleDao().insertList(listOf(newArticle))
                    database.syncIdentityMappingDao().insert(
                        SyncIdentityMappingEntity(
                            syncSpaceId = bundle.syncSpaceId,
                            entityType = SyncEntityType.ARTICLE.wireName,
                            localId = newLocalId,
                            syncId = articleSnapshot.syncId,
                            canonicalKey = canonicalArticleKey,
                            generation = articleGeneration,
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                    newLocalId
                }
                articleSnapshot.fullContentHash?.let { hash ->
                    businessApplier?.materializeSnapshotArticleFullContent(
                        syncSpaceId = bundle.syncSpaceId,
                        entitySyncId = articleSnapshot.syncId,
                        generation = articleGeneration,
                        localAccountId = localAccountId,
                        localArticleId = localArticleId,
                        hash = hash,
                    )
                }
                materializedCount++
            }
            rebasedLanes.add(SyncReplicationLane.ARTICLE_STATE.wireName)
            }

            // 6. CONFIG metadata is staged here; the external JSON file is replaced
            // idempotently after this Room transaction commits.
            if (SyncReplicationLane.CONFIG.wireName in presentLanes) {
                rebasedLanes.add(SyncReplicationLane.CONFIG.wireName)
            }

            // 7. 恢复 Field Versions（R10-06）- 严禁 fail-open
            for (lane in presentLanes) {
                val shard = source.load(lane)
                if (shard.fieldVersionStateJson.isNotBlank() && shard.fieldVersionStateJson != "[]") {
                    val fieldVersions = decodeFieldVersions(shard)
                    val genericEntityTypesBySyncId =
                        if (
                            shard.replicationLaneId == SyncReplicationLane.AI_HISTORY.wireName ||
                            shard.replicationLaneId == SyncReplicationLane.CONFIG.wireName
                        ) {
                            genericEntities(shard.entityStateJson)
                                .mapNotNull { entity ->
                                    val syncId = entity.stringValue("entitySyncId") ?: return@mapNotNull null
                                    val type = entity.stringValue("entityType") ?: return@mapNotNull null
                                    syncId to type
                                }
                                .groupBy({ it.first }, { it.second })
                                .mapValues { (_, types) -> types.toSet() }
                        } else {
                            emptyMap()
                        }

                    val entityType = when (shard.replicationLaneId) {
                        SyncReplicationLane.LIBRARY.wireName -> SyncEntityType.FEED.wireName // 或 GROUP
                        SyncReplicationLane.ARTICLE_STATE.wireName -> SyncEntityType.ARTICLE.wireName
                        else -> "entity"
                    }

                    fieldVersions.forEach { version ->
                        val inferredType =
                            if (shard.replicationLaneId == SyncReplicationLane.LIBRARY.wireName) {
                                when {
                                    groupMappingBySyncId.containsKey(version.entitySyncId) ->
                                        SyncEntityType.GROUP.wireName
                                    feedMappingBySyncId.containsKey(version.entitySyncId) ->
                                        SyncEntityType.FEED.wireName
                                    else ->
                                        throw SnapshotCorruptedError(
                                            "Snapshot field version has no matching LIBRARY entity: " +
                                                version.entitySyncId
                                        )
                                }
                            } else if (
                                shard.replicationLaneId == SyncReplicationLane.AI_HISTORY.wireName ||
                                shard.replicationLaneId == SyncReplicationLane.CONFIG.wireName
                            ) {
                                val candidates =
                                    genericEntityTypesBySyncId[version.entitySyncId].orEmpty()
                                when {
                                    version.entityType != null &&
                                        version.entityType in candidates -> version.entityType
                                    version.entityType != null ->
                                        throw SnapshotCorruptedError(
                                            "Snapshot field version entity type does not match lane state: " +
                                                version.entityType + "/" + version.entitySyncId
                                        )
                                    candidates.size == 1 -> candidates.single()
                                    candidates.isEmpty() ->
                                        throw SnapshotCorruptedError(
                                            "Snapshot field version has no matching entity: ${version.entitySyncId}"
                                        )
                                    else ->
                                        throw SnapshotCorruptedError(
                                            "Snapshot field version entity type is ambiguous for ${version.entitySyncId}"
                                        )
                                }
                            } else {
                                entityType
                            }
                        val effectiveType = version.entityType ?: inferredType
                        if (version.entityType != null && version.entityType != inferredType) {
                            throw SnapshotCorruptedError(
                                "Snapshot field version entity type does not match lane state: " +
                                    version.entityType + "/" + version.entitySyncId
                            )
                        }
                        val mappedGeneration =
                            if (
                                shard.replicationLaneId == SyncReplicationLane.AI_HISTORY.wireName ||
                                shard.replicationLaneId == SyncReplicationLane.CONFIG.wireName
                            ) {
                                generationFor(shard, effectiveType, version.entitySyncId)
                            } else {
                                database.syncIdentityMappingDao().findBySyncId(
                                    bundle.syncSpaceId,
                                    effectiveType,
                                    version.entitySyncId,
                                )?.generation
                                    ?: throw SnapshotCorruptedError(
                                        "Snapshot field version has no matching entity mapping: " +
                                            "$effectiveType/${version.entitySyncId}"
                                    )
                            }
                        if (
                            version.entityGeneration != null &&
                            version.entityGeneration != mappedGeneration
                        ) {
                            throw SnapshotCorruptedError(
                                "Snapshot field version generation does not match entity mapping: " +
                                    effectiveType + "/" + version.entitySyncId
                            )
                        }
                        val entityGeneration = version.entityGeneration ?: mappedGeneration
                        val importedVersion = SyncFieldVersionEntity(
                                syncSpaceId = bundle.syncSpaceId,
                                entityType = effectiveType,
                                entitySyncId = version.entitySyncId,
                                fieldId = version.fieldId,
                                entityGeneration = entityGeneration,
                                versionToken = version.versionToken,
                                sourceOperationId = null,
                                valueJson = version.valueJson,
                                updatedAt = now,
                                causalContextJson = version.causalContextJson,
                                logicalClock = version.logicalClock,
                            )
                        database.syncInboxDao().upsertFieldCandidate(SyncFieldCandidateEntity(importedVersion))
                        val candidates = database.syncInboxDao().listFieldCandidates(bundle.syncSpaceId).filter {
                            it.entityType == effectiveType && it.entitySyncId == version.entitySyncId &&
                                it.entityGeneration == entityGeneration && it.fieldId == version.fieldId &&
                                fieldVersions.any { row ->
                                    row.entitySyncId == it.entitySyncId &&
                                        row.fieldId == it.fieldId &&
                                        row.versionToken == it.versionToken &&
                                        (row.entityType == null || row.entityType == effectiveType) &&
                                        (row.entityGeneration == null || row.entityGeneration == entityGeneration)
                                }
                        }
                        val winner = SyncVersionResolver.resolve(candidates.map { row ->
                            SyncFieldCandidate(row.fieldId, row.valueJson, row.versionToken, SyncVersionToken.source(row.versionToken),
                                causalContextJson = row.causalContextJson, logicalClock = row.logicalClock ?: 0L)
                        }, when (version.fieldId) {
                            "isUnread" -> SyncGenesisMergePolicy.READ_WINS
                            "isStarred" -> SyncGenesisMergePolicy.STARRED_WINS
                            else -> SyncGenesisMergePolicy.DETERMINISTIC
                        })
                        database.syncInboxDao().upsertFieldVersion(candidates.first { it.versionToken == winner.token })
                    }
                }
            }

            run {
                val core = source.load(SyncReplicationLane.CORE_META.wireName)
                restoreAliasEdges(bundle.syncSpaceId, core.causalMergeMetadataJson, now)
            }

            for (lane in presentLanes.sortedBy(::tombstoneLanePriority)) {
                val shard = source.load(lane)
                restoreTombstones(bundle, shard, now)
            }

            // 8. 修正 Coverage 语义（R10-09, R10-10）：
            // 严禁将 retainedPrefix 虚高为 snapshotPrefix！严禁 fail-open 吞掉坏 Frontier
            for (descriptor in descriptors) {
                if (descriptor.replicationLaneId !in presentLanes) continue
                if (descriptor.frontierByActorJson.isBlank() || descriptor.frontierByActorJson == "{}") continue
                val frontiers = try {
                    SyncGenesisCodec.decodeFrontiers(descriptor.frontierByActorJson)[descriptor.replicationLaneId]
                        ?: emptyMap()
                } catch (e: Throwable) {
                    throw SnapshotCorruptedError(
                        "Corrupted frontierByActorJson in lane " + descriptor.replicationLaneId + ": " + e.message
                    )
                }

                for ((actor, seq) in frontiers) {
                    if (seq < 0 || actor.isBlank()) throw SnapshotCorruptedError("Invalid snapshot frontier")
                    val existingCoverage = database.syncInboxDao().findCoverage(
                        bundle.syncSpaceId, descriptor.replicationLaneId, actor,
                    )
                    database.syncInboxDao().upsertCoverage(
                        SyncCoverageEntity(
                            syncSpaceId = bundle.syncSpaceId,
                            replicationLaneId = descriptor.replicationLaneId,
                            actorIncarnationId = actor,
                            receivedPrefix = maxOf(existingCoverage?.receivedPrefix ?: 0L, seq),
                            appliedPrefix = maxOf(existingCoverage?.appliedPrefix ?: 0L, seq),
                            retainedPrefix = existingCoverage?.retainedPrefix ?: 0L, // 绝不虚高！
                            snapshotPrefix = seq,
                            stableGcPrefix = existingCoverage?.stableGcPrefix ?: 0L,
                            updatedAt = now,
                        )
                    )
                }
            }

            applyExternalConfigShard(
                syncSpaceId = bundle.syncSpaceId,
                shard =
                    if (SyncReplicationLane.CONFIG.wireName in presentLanes) {
                        source.load(SyncReplicationLane.CONFIG.wireName)
                    } else {
                        null
                    },
                now = now,
            )

            // Snapshot is only a baseline. Keep STAGING until the coordinator has
            // replayed and applied every retained Tail Operation after this frontier.
        }

        materializeProjectionTombstones(
            bundle = bundle,
            source = source,
            presentLanes = presentLanes,
            now = now,
        )
        val projectionResult =
            materializeProjectionShards(
                syncSpaceId = bundle.syncSpaceId,
                source = source,
                presentLanes = presentLanes,
                now = now,
            )
        materializedCount += projectionResult.first
        rebasedLanes += projectionResult.second

        replayRetainedTail(
            targetCoverage = targetCoverage,
            preservedAppliedCoverage = coverageBeforeRebase.applied,
            presentLanes = presentLanes,
            operations = replayTail,
        )
        restoreRecoverableCoverage(
            syncSpaceId = bundle.syncSpaceId,
            previous = coverageBeforeRebase,
            presentLanes = presentLanes,
            now = now,
        )

        // The baseline and local recovery state are now materialized consistently. Keep the
        // Space in STAGING until the SessionCoordinator has replayed the remote Tail and
        // activateAfterTail() can atomically publish ACTIVE.
        runInTransaction {
            SyncSnapshotInstallJournal.record(database, bundle, presentLanes, now)
            database.syncRuntimeDao().upsertBinding(binding.copy(lifecycleState = SyncSpaceLifecycleState.STAGING.name, updatedAt = now))
        }

        return AndroidSnapshotInstallResult(
            snapshotBundleId = bundle.snapshotBundleId,
            syncSpaceId = bundle.syncSpaceId,
            materializedEntities = materializedCount,
            rebasedLanes = rebasedLanes.distinct(),
        )
    }

    private suspend fun currentCoverageVector(syncSpaceId: String): SyncCoverageVector {
        val rows = database.syncInboxDao().listCoverage(syncSpaceId)
        fun coverage(selector: (SyncCoverageEntity) -> Long): SyncCoverage =
            rows.groupBy(SyncCoverageEntity::replicationLaneId)
                .mapValues { (_, values) ->
                    values.associate { value -> value.actorIncarnationId to selector(value) }
                        .filterValues { it > 0L }
                }
                .filterValues { it.isNotEmpty() }
        return SyncCoverageVector(
            received = coverage(SyncCoverageEntity::receivedPrefix),
            applied = coverage(SyncCoverageEntity::appliedPrefix),
            retained = coverage(SyncCoverageEntity::retainedPrefix),
            snapshot = coverage(SyncCoverageEntity::snapshotPrefix),
            stableGc = coverage(SyncCoverageEntity::stableGcPrefix),
        )
    }

    private fun mergeRecoveryCoverage(previous: SyncCoverageVector, current: SyncCoverageVector): SyncCoverageVector {
        fun merge(left: SyncCoverage, right: SyncCoverage): SyncCoverage =
            (left.keys + right.keys).associateWith { lane ->
                (left[lane].orEmpty().keys + right[lane].orEmpty().keys).associateWith { actor ->
                    maxOf(left[lane]?.get(actor) ?: 0L, right[lane]?.get(actor) ?: 0L)
                }
            }
        return SyncCoverageVector(
            received = merge(previous.received, current.received), applied = merge(previous.applied, current.applied),
            retained = merge(previous.retained, current.retained), snapshot = merge(previous.snapshot, current.snapshot),
            stableGc = merge(previous.stableGc, current.stableGc),
        )
    }

    private fun snapshotCoverage(
        descriptors: List<GenesisShardDescriptor>,
        presentLanes: Set<String>,
    ): SyncCoverage =
        descriptors
            .asSequence()
            .filter { it.replicationLaneId in presentLanes }
            .associate { descriptor ->
                val actors =
                    try {
                        SyncGenesisCodec.decodeFrontiers(descriptor.frontierByActorJson)[descriptor.replicationLaneId]
                            ?: emptyMap()
                    } catch (error: Throwable) {
                        throw SnapshotCorruptedError(
                            "Corrupted Snapshot frontier in lane ${descriptor.replicationLaneId}: ${error.message}"
                        )
                    }
                descriptor.replicationLaneId to actors
            }
            .filterValues { it.isNotEmpty() }

    private fun assertRetainedTailIsComplete(
        targetCoverage: SyncCoverage,
        retainedCoverage: SyncCoverage,
        operations: List<SyncOperationEntity>,
        presentLanes: Set<String>,
    ) {
        val byStream =
            operations.groupBy { it.replicationLaneId to it.actorIncarnationId }
                .mapValues { (_, values) -> values.map(SyncOperationEntity::sequence).distinct().sorted() }
        for ((stream, sequences) in byStream) {
            val (lane, actor) = stream
            var expected = (targetCoverage[lane]?.get(actor) ?: 0L) + 1L
            for (sequence in sequences) {
                if (sequence != expected) {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: retained Operation gap for $lane/$actor; expected $expected, got $sequence"
                    )
                }
                expected++
            }
        }
        for ((lane, actors) in retainedCoverage) {
            if (lane !in presentLanes) continue
            for ((actor, prefix) in actors) {
                val target = targetCoverage[lane]?.get(actor) ?: 0L
                if (prefix <= target) continue
                val maxRetained = byStream[lane to actor]?.lastOrNull() ?: target
                if (maxRetained < prefix) {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: local retained coverage $lane/$actor=$prefix is not reconstructable from retained Operations"
                    )
                }
            }
        }
    }

    private fun snapshotIsBehindStableGc(
        targetCoverage: SyncCoverage,
        stableGcCoverage: SyncCoverage,
        presentLanes: Set<String>,
    ): Boolean {
        for ((lane, actors) in stableGcCoverage) {
            if (lane !in presentLanes) continue
            for ((actor, prefix) in actors) {
                val target = targetCoverage[lane]?.get(actor) ?: 0L
                if (prefix > target) return true
            }
        }
        return false
    }

    private suspend fun replayRetainedTail(
        targetCoverage: SyncCoverage,
        preservedAppliedCoverage: SyncCoverage,
        presentLanes: Set<String>,
        operations: List<SyncOperationEntity>,
    ) {
        if (operations.isEmpty()) return
        val applier =
            businessApplier
                ?: throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: business projection is unavailable for retained-tail replay"
                )
        val progress = linkedMapOf<String, MutableMap<String, Long>>()
        preservedAppliedCoverage.forEach { (lane, actors) ->
            if (lane !in presentLanes) progress[lane] = actors.toMutableMap()
        }
        targetCoverage.forEach { (lane, actors) ->
            progress.getOrPut(lane) { linkedMapOf() }.putAll(actors)
        }
        val remaining = operations.toMutableList()
        while (remaining.isNotEmpty()) {
            var madeProgress = false
            val iterator = remaining.listIterator()
            while (iterator.hasNext()) {
                val operation = iterator.next()
                val prefix =
                    progress[operation.replicationLaneId]
                        ?.get(operation.actorIncarnationId) ?: 0L
                if (operation.sequence != prefix + 1L) continue
                if (!SyncApplyDependencies.satisfied(operation.dependencyDotsJson, progress)) continue
                runInTransaction { applier.apply(operation) }
                progress.getOrPut(operation.replicationLaneId) { linkedMapOf() }[
                    operation.actorIncarnationId
                ] = operation.sequence
                iterator.remove()
                madeProgress = true
            }
            if (!madeProgress) {
                val blocked = remaining.first()
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: retained tail cannot be causally replayed at " +
                        "${blocked.replicationLaneId}/${blocked.actorIncarnationId}/${blocked.sequence}"
                )
            }
        }
    }

    private suspend fun restoreRecoverableCoverage(
        syncSpaceId: String,
        previous: SyncCoverageVector,
        presentLanes: Set<String>,
        now: Long,
    ) {
        for (lane in presentLanes) {
            val actors =
                buildSet {
                    addAll(previous.received[lane].orEmpty().keys)
                    addAll(previous.applied[lane].orEmpty().keys)
                    addAll(previous.retained[lane].orEmpty().keys)
                    addAll(previous.stableGc[lane].orEmpty().keys)
                }
            for (actor in actors) {
                val current = database.syncInboxDao().findCoverage(syncSpaceId, lane, actor)
                database.syncInboxDao().upsertCoverage(
                    SyncCoverageEntity(
                        syncSpaceId = syncSpaceId,
                        replicationLaneId = lane,
                        actorIncarnationId = actor,
                        receivedPrefix =
                            maxOf(current?.receivedPrefix ?: 0L, previous.received[lane]?.get(actor) ?: 0L),
                        appliedPrefix =
                            maxOf(current?.appliedPrefix ?: 0L, previous.applied[lane]?.get(actor) ?: 0L),
                        retainedPrefix =
                            maxOf(current?.retainedPrefix ?: 0L, previous.retained[lane]?.get(actor) ?: 0L),
                        snapshotPrefix = current?.snapshotPrefix ?: 0L,
                        stableGcPrefix =
                            maxOf(current?.stableGcPrefix ?: 0L, previous.stableGc[lane]?.get(actor) ?: 0L),
                        updatedAt = now,
                    )
                )
            }
        }
    }

    private suspend fun validateProjectionShards(
        syncSpaceId: String,
        source: SnapshotShardSource,
        presentLanes: Set<String>,
    ) {
        for (lane in presentLanes) {
            val shard = source.load(lane)
            snapshotDeletionRows(shard).forEach tombstoneLoop@{ row ->
                val entityType =
                    row.stringValue("entityType")
                        ?: throw SnapshotCorruptedError("Tombstone entityType is missing")
                if (projectionExtensions.any { it.owns(entityType) }) return@tombstoneLoop
                val entitySyncId =
                    row.stringValue("entitySyncId")
                        ?: throw SnapshotCorruptedError("Tombstone entitySyncId is missing")
                val generation =
                    row.longValue("generation")
                        ?: generationFor(shard, entityType, entitySyncId).coerceAtLeast(1L)
                val mapping =
                    database.syncIdentityMappingDao()
                        .findBySyncId(syncSpaceId, entityType, entitySyncId)
                if (mapping != null && generation < mapping.generation) {
                    throw SyncLocalRecoverySnapshotRequiredException(
                        "LOCAL_RECOVERY_REQUIRED: Snapshot tombstone $entityType/$entitySyncId " +
                            "generation $generation is behind local generation ${mapping.generation}"
                    )
                }
            }
        }
        if (SyncReplicationLane.AI_HISTORY.wireName in presentLanes) {
            val shard = source.load(SyncReplicationLane.AI_HISTORY.wireName)
                genericEntities(shard.entityStateJson).forEach { entity ->
                    val entityType =
                        entity.stringValue("entityType")
                            ?: throw SnapshotCorruptedError(
                                "AI_HISTORY Snapshot entity has no entityType"
                            )
                    val entitySyncId =
                        entity.stringValue("entitySyncId")
                            ?: throw SnapshotCorruptedError(
                                "AI_HISTORY Snapshot entity has no entitySyncId"
                            )
                    val fields =
                        entity["fields"]?.jsonObject
                            ?: throw SnapshotCorruptedError(
                                "AI_HISTORY Snapshot entity has no fields"
                            )
                    val projection =
                        projectionExtensions.firstOrNull { it.owns(entityType) }
                            ?: throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: no installed projection can materialize $entityType"
                            )
                    projection.validateSnapshotEntity(
                        syncSpaceId = syncSpaceId,
                        entity =
                            SyncGenesisProjectionEntity(
                                entityType = entityType,
                                entitySyncId = entitySyncId,
                                generation = entity.longValue("generation") ?: 0L,
                                fieldsJson =
                                    SyncOperationCanonicalizer.canonicalJson(fields.toString()),
                            ),
                    )
                }
                snapshotDeletionRows(shard).forEach { row ->
                    val entityType =
                        row.stringValue("entityType")
                            ?: throw SnapshotCorruptedError("Tombstone entityType is missing")
                    val entitySyncId =
                        row.stringValue("entitySyncId")
                            ?: throw SnapshotCorruptedError("Tombstone entitySyncId is missing")
                    val projection =
                        projectionExtensions.firstOrNull { it.owns(entityType) }
                            ?: throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: no installed projection can materialize tombstone $entityType"
                            )
                    projection.validateSnapshotTombstone(
                        syncSpaceId = syncSpaceId,
                        entityType = entityType,
                        entitySyncId = entitySyncId,
                        generation =
                            row.longValue("generation")
                                ?: generationFor(shard, entityType, entitySyncId).coerceAtLeast(1L),
                    )
                }
        }
    }

    private suspend fun materializeProjectionTombstones(
        bundle: SyncSnapshotBundleEntity,
        source: SnapshotShardSource,
        presentLanes: Set<String>,
        now: Long,
    ) {
        if (SyncReplicationLane.AI_HISTORY.wireName !in presentLanes) return
        val shard = source.load(SyncReplicationLane.AI_HISTORY.wireName)
                for (row in snapshotDeletionRows(shard)) {
                    val entityType =
                        row.stringValue("entityType")
                            ?: throw SnapshotCorruptedError("Tombstone entityType is missing")
                    val entitySyncId =
                        row.stringValue("entitySyncId")
                            ?: throw SnapshotCorruptedError("Tombstone entitySyncId is missing")
                    val generation =
                        row.longValue("generation")
                            ?: generationFor(shard, entityType, entitySyncId).coerceAtLeast(1L)
                    val versionToken =
                        row.stringValue("versionToken")
                            ?: "TOMBSTONE|" + bundle.snapshotBundleId + "|" +
                                entityType + "|" + entitySyncId
                    val projection =
                        projectionExtensions.firstOrNull { it.owns(entityType) }
                            ?: throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: no installed projection can materialize tombstone " +
                                    "$entityType/$entitySyncId"
                            )
                    val handled =
                        projection.materializeSnapshotTombstone(
                            syncSpaceId = bundle.syncSpaceId,
                            entityType = entityType,
                            entitySyncId = entitySyncId,
                            generation = generation,
                            versionToken = versionToken,
                            deletedAt = row.longValue("deletedAt") ?: now,
                        )
                    if (!handled) {
                        throw SyncRebaseUnsafeException(
                            "REBASE_UNSAFE: projection declined Snapshot tombstone " +
                                "$entityType/$entitySyncId"
                        )
                    }
                }
    }

    private suspend fun materializeProjectionShards(
        syncSpaceId: String,
        source: SnapshotShardSource,
        presentLanes: Set<String>,
        now: Long,
    ): Pair<Int, List<String>> {
        var count = 0
        val lanes = mutableListOf<String>()
        if (SyncReplicationLane.AI_HISTORY.wireName in presentLanes) {
            val shard = source.load(SyncReplicationLane.AI_HISTORY.wireName)
                val entities =
                    genericEntities(shard.entityStateJson)
                        .sortedWith(
                            compareBy<JsonObject> {
                                when (it.stringValue("entityType")) {
                                    SyncEntityType.CONVERSATION.wireName -> 1
                                    SyncEntityType.MESSAGE.wireName -> 2
                                    SyncEntityType.TOOL_CALL.wireName -> 3
                                    SyncEntityType.CONTEXT_REF.wireName -> 4
                                    SyncEntityType.EVIDENCE_BLOCK.wireName -> 5
                                    SyncEntityType.CITATION_REF.wireName -> 6
                                    SyncEntityType.CITATION_ANNOTATION.wireName -> 7
                                    SyncEntityType.CONVERSATION_ARTICLE.wireName -> 8
                                    SyncEntityType.CITATION_ANNOTATION_REF.wireName -> 9
                                    else -> 100
                                }
                            }.thenBy { it.stringValue("entitySyncId").orEmpty() }
                        )
                for (entity in entities) {
                    val entityType =
                        entity.stringValue("entityType")
                            ?: throw SnapshotCorruptedError("AI_HISTORY Snapshot entity has no entityType")
                    val entitySyncId =
                        entity.stringValue("entitySyncId")
                            ?: throw SnapshotCorruptedError("AI_HISTORY Snapshot entity has no entitySyncId")
                    val fields =
                        entity["fields"]?.jsonObject
                            ?: throw SnapshotCorruptedError("AI_HISTORY Snapshot entity has no fields")
                    val projection =
                        projectionExtensions.firstOrNull { it.owns(entityType) }
                            ?: throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: no installed projection can materialize $entityType"
                            )
                    val handled =
                        projection.materializeSnapshotEntity(
                            syncSpaceId = syncSpaceId,
                            entity =
                                SyncGenesisProjectionEntity(
                                    entityType = entityType,
                                    entitySyncId = entitySyncId,
                                    generation = entity.longValue("generation") ?: 0L,
                                    fieldsJson = SyncOperationCanonicalizer.canonicalJson(fields.toString()),
                                ),
                            now = now,
                        )
                    if (!handled) {
                        throw SyncRebaseUnsafeException(
                            "REBASE_UNSAFE: projection declined Snapshot entity $entityType/$entitySyncId"
                        )
                    }
                    count++
                }
                lanes += shard.replicationLaneId
        }
        return count to lanes.distinct()
    }

    private suspend fun applyExternalConfigShard(
        syncSpaceId: String,
        shard: SyncSnapshotShardEntity?,
        now: Long,
    ) {
        if (shard == null) return
        val supportedConfigTypes =
            setOf(
                SyncEntityType.FILTER_RULE.wireName,
                SyncEntityType.WEBSITE_RULE.wireName,
                SyncEntityType.JSON_RULE.wireName,
                SyncEntityType.RSSHUB_SETTINGS.wireName,
                SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName,
                SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName,
            )
        val configEntities = genericEntities(shard.entityStateJson)
        configEntities.forEach { entity ->
            val entityType =
                entity.stringValue("entityType")?.takeIf(String::isNotBlank)
                    ?: throw SnapshotCorruptedError("CONFIG Snapshot entity has no entityType")
            val entitySyncId =
                entity.stringValue("entitySyncId")?.takeIf(String::isNotBlank)
                    ?: throw SnapshotCorruptedError("CONFIG Snapshot entity has no entitySyncId")
            if (entityType !in supportedConfigTypes) {
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: unsupported CONFIG Snapshot entity type $entityType"
                )
            }
            val generation = entity.longValue("generation") ?: 0L
            if (generation < 0L) {
                throw SnapshotCorruptedError(
                    "CONFIG Snapshot entity $entityType/$entitySyncId has invalid generation"
                )
            }
            if (entity["fields"] !is JsonObject) {
                throw SnapshotCorruptedError(
                    "CONFIG Snapshot entity $entityType/$entitySyncId has no fields"
                )
            }
        }
        val liveConfigEntities =
            configEntities.filter { entity ->
                val entityType = checkNotNull(entity.stringValue("entityType"))
                val entitySyncId = checkNotNull(entity.stringValue("entitySyncId"))
                val generation = entity.longValue("generation") ?: 0L
                val tombstone =
                    database.syncInboxDao().findTombstone(
                        syncSpaceId,
                        entityType,
                        entitySyncId,
                    )
                tombstone == null || tombstone.entityGeneration < generation
            }
        val entities =
            liveConfigEntities
                .filter { it.stringValue("entityType") == SyncEntityType.FILTER_RULE.wireName }
                .sortedBy { it.stringValue("entitySyncId").orEmpty() }
        val rules =
            entities.map { entity ->
                val syncId =
                    entity.stringValue("entitySyncId")
                        ?: throw SnapshotCorruptedError("CONFIG filter rule has no entitySyncId")
                val generation = entity.longValue("generation") ?: 0L
                val fields =
                    entity["fields"]?.jsonObject
                        ?: throw SnapshotCorruptedError("CONFIG filter rule has no fields")
                val existing =
                    database.syncIdentityMappingDao().findBySyncId(
                        syncSpaceId,
                        SyncEntityType.FILTER_RULE.wireName,
                        syncId,
                    )
                val mapping =
                    if (existing == null) {
                        SyncIdentityMappingEntity(
                            syncSpaceId = syncSpaceId,
                            entityType = SyncEntityType.FILTER_RULE.wireName,
                            localId = UUID.randomUUID().toString(),
                            syncId = syncId,
                            canonicalKey = null,
                            generation = generation,
                            createdAt = now,
                            updatedAt = now,
                        ).also { database.syncIdentityMappingDao().insert(it) }
                    } else {
                        if (generation < existing.generation) {
                            throw SyncLocalRecoverySnapshotRequiredException(
                                "LOCAL_RECOVERY_REQUIRED: CONFIG filter rule $syncId generation " +
                                    "$generation is behind local generation ${existing.generation}"
                            )
                        }
                        if (generation > existing.generation) {
                            existing.copy(generation = generation, updatedAt = now)
                                .also { database.syncIdentityMappingDao().update(it) }
                        } else {
                            existing
                        }
                    }
                val feedSyncId = fields.stringValue("feedSyncId")
                val feedGeneration =
                    fields["feedGeneration"]
                        ?.takeIf { it != JsonNull }
                        ?.jsonPrimitive
                        ?.longOrNull
                if (feedSyncId == null && feedGeneration != null) {
                    throw SnapshotCorruptedError(
                        "CONFIG filter rule $syncId has feedGeneration without feedSyncId"
                    )
                }
                val feedId =
                    feedSyncId?.let {
                        requireSnapshotFeedParent(
                            syncSpaceId = syncSpaceId,
                            feedSyncId = it,
                            feedGeneration = feedGeneration,
                            label = "CONFIG filter rule $syncId",
                        ).localId
                    }
                ArticleFilterRule(
                    id = mapping.localId,
                    keyword =
                        fields.stringValue("keyword")
                            ?: throw SnapshotCorruptedError(
                                "CONFIG filter rule $syncId has no keyword"
                            ),
                    feedId = feedId,
                    feedName = fields.stringValue("feedName"),
                    type =
                        runCatching {
                            ArticleFilterRuleType.valueOf(
                                fields.stringValue("type") ?: ArticleFilterRuleType.KEYWORD.name
                            )
                        }.getOrElse {
                            throw SnapshotCorruptedError("CONFIG filter rule has invalid type")
                        },
                    enabled = fields["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }
        filterRepository.replaceRules(rules)

        val websiteRules =
            liveConfigEntities
                .filter { it.stringValue("entityType") == SyncEntityType.WEBSITE_RULE.wireName }
                .sortedBy { it.stringValue("entitySyncId").orEmpty() }
                .map { entity ->
                    val syncId =
                        entity.stringValue("entitySyncId")
                            ?: throw SnapshotCorruptedError("CONFIG website rule has no entitySyncId")
                    val generation = entity.longValue("generation") ?: 0L
                    val fields =
                        entity["fields"]?.jsonObject
                            ?: throw SnapshotCorruptedError("CONFIG website rule has no fields")
                    val rule =
                        runCatching {
                            json.decodeFromString<WebsiteRule>(
                                fields["rule"]?.toString()
                                    ?: throw SnapshotCorruptedError("CONFIG website rule has no rule field")
                            )
                        }.getOrElse {
                            throw SnapshotCorruptedError(
                                "CONFIG website rule payload is invalid: " + it.message.orEmpty()
                            )
                        }
                    ensureAtomicConfigMapping(
                        syncSpaceId,
                        SyncEntityType.WEBSITE_RULE,
                        syncId,
                        rule.id,
                        generation,
                        now,
                    )
                    rule
                }
        websiteRuleRepository.replaceSyncRules(websiteRules)

        val jsonRules =
            liveConfigEntities
                .filter { it.stringValue("entityType") == SyncEntityType.JSON_RULE.wireName }
                .sortedBy { it.stringValue("entitySyncId").orEmpty() }
                .map { entity ->
                    val syncId =
                        entity.stringValue("entitySyncId")
                            ?: throw SnapshotCorruptedError("CONFIG JSON rule has no entitySyncId")
                    val generation = entity.longValue("generation") ?: 0L
                    val fields =
                        entity["fields"]?.jsonObject
                            ?: throw SnapshotCorruptedError("CONFIG JSON rule has no fields")
                    val rule =
                        runCatching {
                            json.decodeFromString<JsonRule>(
                                fields["rule"]?.toString()
                                    ?: throw SnapshotCorruptedError("CONFIG JSON rule has no rule field")
                            )
                        }.getOrElse {
                            throw SnapshotCorruptedError(
                                "CONFIG JSON rule payload is invalid: " + it.message.orEmpty()
                            )
                        }
                    ensureAtomicConfigMapping(
                        syncSpaceId,
                        SyncEntityType.JSON_RULE,
                        syncId,
                        rule.id,
                        generation,
                        now,
                    )
                    rule
                }
        jsonRuleRepository.replaceSyncRules(jsonRules)

        val rssHubSettingsEntities =
            liveConfigEntities
                .filter { it.stringValue("entityType") == SyncEntityType.RSSHUB_SETTINGS.wireName }
        if (rssHubSettingsEntities.size != 1) {
            throw SnapshotCorruptedError(
                "CONFIG Snapshot must contain exactly one RSSHub settings entity"
            )
        }
        rssHubSettingsEntities.single().let { entity ->
                val syncId =
                    entity.stringValue("entitySyncId")
                        ?: throw SnapshotCorruptedError("CONFIG RSSHub settings has no entitySyncId")
                val generation = entity.longValue("generation") ?: 0L
                val fields =
                    entity["fields"]?.jsonObject
                        ?: throw SnapshotCorruptedError("CONFIG RSSHub settings has no fields")
                ensureAtomicConfigMapping(
                    syncSpaceId,
                    SyncEntityType.RSSHUB_SETTINGS,
                    syncId,
                    "rsshub-settings",
                    generation,
                    now,
                )
                rssHubSettingsRepository.replaceSyncSettings(
                    decodeRssHubSettings(
                        fields["settings"]?.toString()
                            ?: throw SnapshotCorruptedError("CONFIG RSSHub settings has no settings field")
                    )
                )
        }

        val preferenceEntities =
            liveConfigEntities
                .filter {
                    it.stringValue("entityType") ==
                        SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName
                }
                .sortedBy { it.stringValue("entitySyncId").orEmpty() }
        val incomingFeedSyncIds = linkedSetOf<String>()
        preferenceEntities.forEach { entity ->
            val syncId =
                entity.stringValue("entitySyncId")
                    ?: throw SnapshotCorruptedError(
                        "CONFIG website parse preference has no entitySyncId"
                    )
            val generation = entity.longValue("generation") ?: 0L
            val fields =
                entity["fields"]?.jsonObject
                    ?: throw SnapshotCorruptedError(
                        "CONFIG website parse preference has no fields"
                    )
            val preference =
                fields["preference"]?.jsonObject
                    ?: throw SnapshotCorruptedError(
                        "CONFIG website parse preference has no preference field"
                    )
            val feedSyncId =
                preference["feedSyncId"]?.jsonPrimitive?.content
                    ?: throw SnapshotCorruptedError(
                        "CONFIG website parse preference has no feedSyncId"
                    )
            val feedGeneration =
                preference["feedGeneration"]
                    ?.takeIf { it != JsonNull }
                    ?.jsonPrimitive
                    ?.longOrNull
            if (!incomingFeedSyncIds.add(feedSyncId)) {
                throw SnapshotCorruptedError(
                    "CONFIG Snapshot contains duplicate website parse preference for $feedSyncId"
                )
            }
            ensureAtomicConfigMapping(
                syncSpaceId,
                SyncEntityType.WEBSITE_PARSE_PREFERENCE,
                syncId,
                feedSyncId,
                generation,
                now,
            )
            val feedMapping =
                requireSnapshotFeedParent(
                    syncSpaceId = syncSpaceId,
                    feedSyncId = feedSyncId,
                    feedGeneration = feedGeneration,
                    label = "CONFIG website parse preference $syncId",
                )
            fun nullableText(field: String): String? =
                preference[field]?.let { value ->
                    if (value == JsonNull) null else value.jsonPrimitive.content
                }
            websiteParsePreferenceRepository.applyUserSyncState(
                feedMapping.localId,
                WebsiteParsePreferenceUserSyncState(
                    dynamicRenderingEnabled =
                        preference["dynamicRenderingEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    preferredRuleId = nullableText("preferredRuleId"),
                    preferredRuleName = nullableText("preferredRuleName"),
                ),
            )
        }
        database.syncIdentityMappingDao()
            .findByType(syncSpaceId, SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName)
            .filter { it.localId !in incomingFeedSyncIds }
            .forEach { preferenceMapping ->
                database.syncIdentityMappingDao()
                    .findBySyncId(
                        syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        preferenceMapping.localId,
                    )
                    ?.localId
                    ?.takeIf { localFeedId -> database.feedDao().queryById(localFeedId) != null }
                    ?.let { localFeedId ->
                        websiteParsePreferenceRepository.applyUserSyncState(localFeedId, null)
                    }
            }

        val rssHubSourceEntities =
            liveConfigEntities
                .filter {
                    it.stringValue("entityType") ==
                        SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName
                }
                .sortedBy { it.stringValue("entitySyncId").orEmpty() }
        val incomingRssHubFeedSyncIds = linkedSetOf<String>()
        rssHubSourceEntities.forEach { entity ->
            val syncId =
                entity.stringValue("entitySyncId")
                    ?: throw SnapshotCorruptedError(
                        "CONFIG RSSHub subscription source has no entitySyncId"
                    )
            val generation = entity.longValue("generation") ?: 0L
            val fields =
                entity["fields"]?.jsonObject
                    ?: throw SnapshotCorruptedError(
                        "CONFIG RSSHub subscription source has no fields"
                    )
            val source =
                fields["source"]?.jsonObject
                    ?: throw SnapshotCorruptedError(
                        "CONFIG RSSHub subscription source has no source field"
                    )
            val feedSyncId =
                source["feedSyncId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                    ?: throw SnapshotCorruptedError(
                        "CONFIG RSSHub subscription source has no feedSyncId"
                    )
            val feedGeneration =
                source["feedGeneration"]
                    ?.takeIf { it != JsonNull }
                    ?.jsonPrimitive
                    ?.longOrNull
            val sourceUrl =
                source["sourceUrl"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
                    ?: throw SnapshotCorruptedError(
                        "CONFIG RSSHub subscription source has no sourceUrl"
                    )
            if (!incomingRssHubFeedSyncIds.add(feedSyncId)) {
                throw SnapshotCorruptedError(
                    "CONFIG Snapshot contains duplicate RSSHub subscription source for $feedSyncId"
                )
            }
            ensureAtomicConfigMapping(
                syncSpaceId,
                SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE,
                syncId,
                feedSyncId,
                generation,
                now,
            )
            val feedMapping =
                requireSnapshotFeedParent(
                    syncSpaceId = syncSpaceId,
                    feedSyncId = feedSyncId,
                    feedGeneration = feedGeneration,
                    label = "CONFIG RSSHub subscription source $syncId",
                )
            rssHubSubscriptionRepository.replaceSyncSource(
                feedMapping.localId,
                sourceUrl,
            )
        }
        database.syncIdentityMappingDao()
            .findByType(syncSpaceId, SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName)
            .filter { it.localId !in incomingRssHubFeedSyncIds }
            .forEach { sourceMapping ->
                database.syncIdentityMappingDao()
                    .findBySyncId(
                        syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        sourceMapping.localId,
                    )
                    ?.localId
                    ?.let { localFeedId ->
                        rssHubSubscriptionRepository.replaceSyncSource(localFeedId, null)
                    }
            }
    }

    private suspend fun requireSnapshotFeedParent(
        syncSpaceId: String,
        feedSyncId: String,
        feedGeneration: Long?,
        label: String,
    ): SyncIdentityMappingEntity {
        val mapping =
            database.syncIdentityMappingDao().findBySyncId(
                syncSpaceId,
                SyncEntityType.FEED.wireName,
                feedSyncId,
            ) ?: throw SnapshotDependencyMissingError(
                "$label is waiting for feed $feedSyncId"
            )
        if (feedGeneration != null) {
            if (mapping.generation != feedGeneration) {
                throw SnapshotCorruptedError(
                    "$label references feed $feedSyncId generation $feedGeneration, " +
                        "but Snapshot mapping is generation ${mapping.generation}"
                )
            }
        } else if (mapping.generation > 0L) {
            throw SnapshotCorruptedError(
                "$label omits feedGeneration for revived feed $feedSyncId"
            )
        }
        if (database.feedDao().queryById(mapping.localId) == null) {
            throw SnapshotDependencyMissingError(
                "$label is waiting for materialized feed $feedSyncId"
            )
        }
        return mapping
    }

    private suspend fun ensureAtomicConfigMapping(
        syncSpaceId: String,
        entityType: SyncEntityType,
        syncId: String,
        localId: String,
        generation: Long,
        now: Long,
    ): SyncIdentityMappingEntity {
        val expectedSyncId = SyncCanonicalIdentity.configRuleSyncId(entityType, localId)
        if (syncId != expectedSyncId) {
            throw SnapshotCorruptedError(
                "CONFIG Snapshot identity mismatch for " + entityType.wireName + "/" + localId
            )
        }
        val existing =
            database.syncIdentityMappingDao().findBySyncId(
                syncSpaceId,
                entityType.wireName,
                syncId,
            )
        if (existing == null) {
            return SyncIdentityMappingEntity(
                syncSpaceId = syncSpaceId,
                entityType = entityType.wireName,
                localId = localId,
                syncId = syncId,
                canonicalKey = null,
                generation = generation,
                createdAt = now,
                updatedAt = now,
            ).also { database.syncIdentityMappingDao().insert(it) }
        }
        if (existing.localId != localId) {
            throw SnapshotCorruptedError(
                "CONFIG Snapshot mapping localId mismatch for " + entityType.wireName
            )
        }
        if (generation < existing.generation) {
            throw SyncLocalRecoverySnapshotRequiredException(
                "LOCAL_RECOVERY_REQUIRED: CONFIG " + entityType.wireName + "/" + syncId +
                    " generation $generation is behind local generation ${existing.generation}"
            )
        }
        return if (generation > existing.generation) {
            existing.copy(generation = generation, updatedAt = now)
                .also { database.syncIdentityMappingDao().update(it) }
        } else {
            existing
        }
    }

    private fun decodeRssHubSettings(valueJson: String): RssHubSettings {
        val root = json.parseToJsonElement(valueJson).jsonObject
        val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val instances =
            root["instances"]?.jsonArray?.map { value ->
                val item = value.jsonObject
                RssHubInstance(
                    id = item.getValue("id").jsonPrimitive.content,
                    url = item.getValue("url").jsonPrimitive.content,
                    location = item["location"]?.jsonPrimitive?.content.orEmpty(),
                    maintainer = item["maintainer"]?.jsonPrimitive?.content.orEmpty(),
                    enabled = item["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    builtIn = item["builtIn"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }.orEmpty()
        return RssHubSettings(enabled = enabled, instances = instances)
    }

    suspend fun activateAfterTail(
        localAccountId: Int,
        snapshotBundleId: String,
        now: Long = System.currentTimeMillis(),
        pausedLanes: List<String> = emptyList(),
    ) {
        val binding = database.syncRuntimeDao().findBinding(localAccountId)
            ?: error("Local account $localAccountId has no sync space binding")
        check(
            binding.lifecycleState in
                setOf(
                    SyncSpaceLifecycleState.STAGING.name,
                    SyncSpaceLifecycleState.REBASE_PREPARE.name,
                )
        ) {
            "Snapshot baseline is not in REBASE_PREPARE"
        }
        val bundle = database.syncGenesisDao().findBundle(snapshotBundleId)
            ?: error("Staged Snapshot $snapshotBundleId was not found")
        check(bundle.syncSpaceId == binding.syncSpaceId) { "Staged Snapshot belongs to another Sync Space" }
        val pending = if (pausedLanes.isEmpty()) database.syncInboxDao().listPending(binding.syncSpaceId, 1)
            else database.syncInboxDao().listPendingAllowed(binding.syncSpaceId, pausedLanes, 1)
        check(pending.isEmpty()) {
            "Snapshot Tail still has unapplied operations"
        }
        database.syncRuntimeDao().upsertBinding(
            binding.copy(
                lifecycleState = SyncSpaceLifecycleState.ACTIVE.name,
                genesisSessionId = null,
                updatedAt = now,
            )
        )
    }

    private fun decodeLibraryState(value: String): GenesisLibraryState {
        runCatching { json.decodeFromString<GenesisLibraryState>(value) }.getOrNull()?.let { return it }
        val entities = genericEntities(value)
        if (entities.isEmpty()) throw SnapshotCorruptedError("LIBRARY entityStateJson has no supported entities")
        val groups =
            entities.filter { it.stringValue("entityType") == SyncEntityType.GROUP.wireName }
                .map { entity ->
                    val fields = entity["fields"]?.jsonObject ?: JsonObject(emptyMap())
                    GenesisGroupSnapshot(
                        syncId = requireNotNull(entity.stringValue("entitySyncId")),
                        name = fields.stringValue("name") ?: "Group",
                    )
                }
        val feeds =
            entities.filter { it.stringValue("entityType") == SyncEntityType.FEED.wireName }
                .map { entity ->
                    val fields = entity["fields"]?.jsonObject ?: JsonObject(emptyMap())
                    GenesisFeedSnapshot(
                        syncId = requireNotNull(entity.stringValue("entitySyncId")),
                        groupSyncId =
                            fields.stringValue("groupSyncId")
                                ?: fields.stringValue("groupId")
                                ?: throw SnapshotDependencyMissingError("Feed Snapshot is missing groupSyncId"),
                        groupGeneration = fields.longValue("groupGeneration"),
                        name = fields.stringValue("name") ?: "Feed",
                        icon = fields.stringValue("icon"),
                        url = fields.stringValue("url") ?: "",
                        sourceType = fields.stringValue("sourceType")?.lowercase() ?: SourceType.RSS.name.lowercase(),
                        isNotification = fields.booleanValue("isNotification") ?: false,
                        isFullContent = fields.booleanValue("isFullContent") ?: false,
                        isBrowser = fields.booleanValue("isBrowser") ?: false,
                    )
                }
        return GenesisLibraryState(groups = groups, feeds = feeds)
    }

    private fun decodeArticleState(value: String): GenesisArticleState {
        runCatching { json.decodeFromString<GenesisArticleState>(value) }.getOrNull()?.let { return it }
        val entities = genericEntities(value)
            .filter { it.stringValue("entityType") == SyncEntityType.ARTICLE.wireName }
        if (entities.isEmpty()) throw SnapshotCorruptedError("ARTICLE_STATE entityStateJson has no supported articles")
        return GenesisArticleState(
            articles =
                entities.map { entity ->
                    val fields = entity["fields"]?.jsonObject ?: JsonObject(emptyMap())
                    GenesisArticleSnapshot(
                        syncId = requireNotNull(entity.stringValue("entitySyncId")),
                        feedSyncId =
                            fields.stringValue("feedSyncId")
                                ?: fields.stringValue("feedId")
                                ?: throw SnapshotDependencyMissingError("Article Snapshot is missing feedSyncId"),
                        feedGeneration = fields.longValue("feedGeneration"),
                        title = fields.stringValue("title") ?: "",
                        author = fields.stringValue("author"),
                        link = fields.stringValue("url") ?: fields.stringValue("link") ?: "",
                        date = fields.longValue("publishedAt") ?: fields.longValue("date") ?: 0L,
                        isUnread = fields.booleanValue("isUnread") ?: false,
                        isStarred = fields.booleanValue("isStarred") ?: false,
                        isReadLater = fields.booleanValue("isReadLater") ?: false,
                        fullContentHash = fields.stringValue("fullContentHash"),
                    )
                },
        )
    }

    private suspend fun validateAuthShard(
        syncSpaceId: String,
        entityStateJson: String,
    ) {
        val root =
            runCatching { json.parseToJsonElement(entityStateJson).jsonObject }
                .getOrElse {
                    throw SnapshotCorruptedError("Corrupted AUTH entityStateJson: " + it.message)
                }
        val ledgerEntity =
            root["entities"]?.jsonArray
                ?.map { it.jsonObject }
                ?.singleOrNull { it["entityType"]?.jsonPrimitive?.content == "auth_ledger" }
                ?: throw SnapshotCorruptedError("AUTH Snapshot must contain exactly one auth_ledger entity")
        val objects =
            ledgerEntity["fields"]?.jsonObject
                ?.get("objects")?.jsonArray
                ?: throw SnapshotCorruptedError("AUTH Snapshot is missing signed ledger objects")
        val snapshotObjects =
            objects.map { element ->
                runCatching {
                    json.decodeFromString<SyncAuthProtocolObject>(element.toString())
                }.getOrElse {
                    throw SnapshotCorruptedError("AUTH Snapshot contains an invalid protocol object")
                }
            }
        if (snapshotObjects.none { it.objectType == SyncAuthObjectType.SPACE_ROOT }) {
            throw SnapshotCorruptedError("AUTH Snapshot has no SPACE_ROOT")
        }
        if (snapshotObjects.any { it.syncSpaceId != syncSpaceId }) {
            throw SnapshotCorruptedError("AUTH Snapshot contains an object from another Sync Space")
        }
        val localById =
            database.syncAuthLedgerDao().list(syncSpaceId)
                .map { SyncAuthWireCodec.decode(it.authObjectJson) }
                .associateBy(SyncAuthProtocolObject::authObjectId)
        snapshotObjects.forEach { snapshot ->
            if (localById[snapshot.authObjectId] != snapshot) {
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: Snapshot AUTH history is not present in the verified local ledger",
                )
            }
        }
    }

    private fun decodeFieldVersions(shard: SyncSnapshotShardEntity): List<GenesisFieldVersionSnapshot> {
        runCatching {
            json.decodeFromString<List<GenesisFieldVersionSnapshot>>(shard.fieldVersionStateJson)
        }.getOrNull()?.let { return it }

        val root =
            runCatching { json.parseToJsonElement(shard.fieldVersionStateJson).jsonObject }
                .getOrElse {
                    throw SnapshotCorruptedError(
                        "Corrupted fieldVersionStateJson in lane " + shard.replicationLaneId + ": " + it.message,
                    )
                }
        val fieldsRoot = root["fields"]?.jsonObject ?: root
        val entityFields =
            when (shard.replicationLaneId) {
                SyncReplicationLane.LIBRARY.wireName -> {
                    val state = decodeLibraryState(shard.entityStateJson)
                    buildMap {
                        state.groups.forEach { group ->
                            put(
                                SyncEntityType.GROUP.wireName + ":" + group.syncId,
                                buildJsonObject { put("name", group.name) },
                            )
                        }
                        state.feeds.forEach { feed ->
                            put(
                                SyncEntityType.FEED.wireName + ":" + feed.syncId,
                                buildJsonObject {
                                    put("groupSyncId", feed.groupSyncId)
                                    feed.groupGeneration?.let { put("groupGeneration", it) }
                                        ?: put("groupGeneration", JsonNull)
                                    put("name", feed.name)
                                    feed.icon?.let { put("icon", it) } ?: put("icon", JsonNull)
                                    put("url", feed.url)
                                    put("sourceType", feed.sourceType)
                                    put("isNotification", feed.isNotification)
                                    put("isFullContent", feed.isFullContent)
                                    put("isBrowser", feed.isBrowser)
                                },
                            )
                        }
                    }
                }
                SyncReplicationLane.ARTICLE_STATE.wireName -> {
                    val state = decodeArticleState(shard.entityStateJson)
                    state.articles.associate { article ->
                        SyncEntityType.ARTICLE.wireName + ":" + article.syncId to
                            buildJsonObject {
                                put("feedSyncId", article.feedSyncId)
                                article.feedGeneration?.let { put("feedGeneration", it) }
                                    ?: put("feedGeneration", JsonNull)
                                put("title", article.title)
                                put("url", article.link)
                                article.author?.let { put("author", it) } ?: put("author", JsonNull)
                                put("publishedAt", article.date)
                                put("description", article.description)
                                put("contentHtml", article.contentHtml)
                                article.imageUrl?.let { put("imageUrl", it) } ?: put("imageUrl", JsonNull)
                                put("isUnread", article.isUnread)
                                put("isStarred", article.isStarred)
                                put("isReadLater", article.isReadLater)
                                article.fullContentHash?.let {
                                    put(SYNC_ARTICLE_FULL_CONTENT_FIELD, it)
                                }
                            }
                    }
                }
                else ->
                    genericEntities(shard.entityStateJson).associate { entity ->
                        val entityType =
                            entity.stringValue("entityType")?.takeIf(String::isNotBlank)
                                ?: throw SnapshotCorruptedError(
                                    "Snapshot entity has no entityType while restoring FieldVersion"
                                )
                        val syncId =
                            entity.stringValue("entitySyncId")?.takeIf(String::isNotBlank)
                                ?: throw SnapshotCorruptedError(
                                    "Snapshot entity has no entitySyncId while restoring FieldVersion"
                                )
                        val fields =
                            entity["fields"] as? JsonObject
                                ?: throw SnapshotCorruptedError(
                                    "Snapshot entity $entityType/$syncId has no fields while restoring FieldVersion"
                                )
                        "$entityType:$syncId" to fields
                    }
            }
        val result = mutableListOf<GenesisFieldVersionSnapshot>()
        for ((entityKey, fieldElement) in fieldsRoot) {
            val fieldMap =
                runCatching { fieldElement.jsonObject }
                    .getOrElse {
                        throw SnapshotCorruptedError(
                            "Snapshot field-version map is invalid for entity: $entityKey"
                        )
                    }
            val separator = entityKey.indexOf(':')
            if (separator <= 0 || separator == entityKey.lastIndex) {
                throw SnapshotCorruptedError(
                    "Snapshot field-version entity key is invalid: $entityKey"
                )
            }
            val entitySyncId = entityKey.substring(separator + 1)
            val fields =
                entityFields[entityKey]
                    ?: throw SnapshotCorruptedError(
                        "Snapshot field-version has no matching entity: $entityKey"
                    )
            for ((fieldId, tokenElement) in fieldMap) {
                if (fieldId.isBlank()) {
                    throw SnapshotCorruptedError(
                        "Snapshot field-version has a blank fieldId for entity: $entityKey"
                    )
                }
                val versionToken =
                    runCatching { tokenElement.jsonPrimitive.content }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: throw SnapshotCorruptedError(
                            "Snapshot field-version token is invalid for $entityKey/$fieldId"
                        )
                val valueJson =
                    fields[fieldId]?.toString()
                        ?: throw SnapshotCorruptedError(
                            "Snapshot field-version has no matching field value: $entityKey/$fieldId"
                        )
                result += GenesisFieldVersionSnapshot(entitySyncId, fieldId, valueJson, versionToken)
            }
        }
        return result
    }

    private fun configHasRules(value: String): Boolean {
        if (value.isBlank() || value == "{}" || value == "[]") return false
        val root = runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull() ?: return true
        val rules = root["rules"]
        if (rules != null && rules !is JsonNull && rules.toString() != "[]") return true
        return genericEntities(value).any { it.stringValue("entityType") == SyncEntityType.FILTER_RULE.wireName }
    }

    private fun genericEntities(value: String): List<JsonObject> {
        if (value.isBlank() || value == "[]") return emptyList()
        val root =
            runCatching { json.parseToJsonElement(value) }
                .getOrElse {
                    throw SnapshotCorruptedError(
                        "Corrupted Snapshot entityStateJson: " + it.message.orEmpty()
                    )
                }
        val entities =
            when (root) {
                is kotlinx.serialization.json.JsonArray -> root
                is JsonObject -> {
                    val raw = root["entities"] ?: return emptyList()
                    runCatching { raw.jsonArray }
                        .getOrElse {
                            throw SnapshotCorruptedError(
                                "Snapshot entityStateJson entities must be an array"
                            )
                        }
                }
                else ->
                    throw SnapshotCorruptedError(
                        "Snapshot entityStateJson must be an object or array"
                    )
            }
        return entities.mapIndexed { index, entity ->
            runCatching { entity.jsonObject }
                .getOrElse {
                    throw SnapshotCorruptedError(
                        "Snapshot entityStateJson entity[$index] must be an object"
                    )
                }
        }
    }

    private fun JsonObject.stringValue(name: String): String? =
        this[name]?.takeUnless { it is JsonNull }?.let { element ->
            runCatching { element.jsonPrimitive.content }.getOrNull()
        }

    private fun JsonObject.booleanValue(name: String): Boolean? =
        this[name]?.takeUnless { it is JsonNull }?.let { element ->
            runCatching { element.jsonPrimitive.booleanOrNull }.getOrNull()
        }

    private fun JsonObject.longValue(name: String): Long? =
        this[name]?.takeUnless { it is JsonNull }?.let { element ->
            runCatching { element.jsonPrimitive.longOrNull }.getOrNull()
        }

    private fun generationFor(
        shard: SyncSnapshotShardEntity,
        entityType: String,
        entitySyncId: String,
    ): Long {
        val summary =
            runCatching { json.parseToJsonElement(shard.generationSummaryJson).jsonObject }
                .getOrNull()
        val direct =
            summary?.get(entityType + ":" + entitySyncId)
                ?: summary?.get(entitySyncId)
        direct?.jsonPrimitive?.longOrNull?.let { return it }
        return genericEntities(shard.entityStateJson)
            .firstOrNull {
                it.stringValue("entityType") == entityType &&
                    it.stringValue("entitySyncId") == entitySyncId
            }
            ?.longValue("generation")
            ?: 0L
    }

    private fun snapshotDeletionRows(shard: SyncSnapshotShardEntity): List<JsonObject> {
        if (shard.deletionSummaryJson.isBlank() || shard.deletionSummaryJson == "[]") {
            return emptyList()
        }
        val root =
            runCatching { json.parseToJsonElement(shard.deletionSummaryJson) }
                .getOrElse {
                    throw SnapshotCorruptedError(
                        "Corrupted deletionSummaryJson in lane " +
                            shard.replicationLaneId + ": " + it.message
                    )
                }
        val rows =
            when {
                root is kotlinx.serialization.json.JsonArray -> root
                root is JsonObject && root["deleted"] != null -> root["deleted"]!!.jsonArray
                else ->
                    throw SnapshotCorruptedError(
                        "Unsupported deletionSummaryJson in lane " + shard.replicationLaneId
                    )
            }
        return rows.map { it.jsonObject }
            .sortedWith(
                compareBy<JsonObject>(
                    { tombstoneEntityPriority(it.stringValue("entityType")) },
                    { it.stringValue("entitySyncId").orEmpty() },
                )
            )
    }

    private suspend fun restoreTombstones(
        bundle: SyncSnapshotBundleEntity,
        shard: SyncSnapshotShardEntity,
        now: Long,
    ) {
        for (row in snapshotDeletionRows(shard)) {
            val entityType = row.stringValue("entityType")
                ?: throw SnapshotCorruptedError("Tombstone entityType is missing")
            val entitySyncId = row.stringValue("entitySyncId")
                ?: throw SnapshotCorruptedError("Tombstone entitySyncId is missing")
            val generation =
                row.longValue("generation")
                    ?: generationFor(shard, entityType, entitySyncId).coerceAtLeast(1L)
            val versionToken =
                row.stringValue("versionToken")
                    ?: "TOMBSTONE|" + bundle.snapshotBundleId + "|" + entityType + "|" + entitySyncId
            database.syncInboxDao().upsertTombstone(
                SyncTombstoneEntity(
                    syncSpaceId = bundle.syncSpaceId,
                    entityType = entityType,
                    entitySyncId = entitySyncId,
                    entityGeneration = generation,
                    versionToken = versionToken,
                    sourceOperationId = null,
                    updatedAt = row.longValue("deletedAt") ?: now,
                )
            )
            val projection = projectionExtensions.firstOrNull { it.owns(entityType) }
            if (projection == null) {
                projectSnapshotDeletion(
                    syncSpaceId = bundle.syncSpaceId,
                    entityType = entityType,
                    entitySyncId = entitySyncId,
                    generation = generation,
                )
            }
            aliasResolver.reconcileDeleteWins(
                syncSpaceId = bundle.syncSpaceId,
                entityType = entityType,
                syncId = entitySyncId,
                generation = generation,
                now = now,
            )
        }
    }

    private fun tombstoneLanePriority(lane: String): Int =
        when (lane) {
            SyncReplicationLane.ARTICLE_STATE.wireName -> 0
            SyncReplicationLane.LIBRARY.wireName -> 1
            else -> 2
        }

    private fun tombstoneEntityPriority(entityType: String?): Int =
        when (entityType) {
            SyncEntityType.ARTICLE.wireName -> 0
            SyncEntityType.FEED.wireName -> 1
            SyncEntityType.GROUP.wireName -> 2
            else -> 3
        }

    private suspend fun restoreAliasEdges(
        syncSpaceId: String,
        causalMetadataJson: String,
        now: Long,
    ) {
        if (causalMetadataJson.isBlank() || causalMetadataJson == "{}") return
        val root =
            runCatching { json.parseToJsonElement(causalMetadataJson).jsonObject }
                .getOrElse { throw SnapshotCorruptedError("Corrupted CORE_META causal metadata: ${it.message}") }
        val edges = root["aliasEdges"] ?: return
        val decoded =
            runCatching { json.decodeFromString<List<SyncAliasEdgePayloadV1>>(edges.toString()) }
                .getOrElse { throw SnapshotCorruptedError("Corrupted Alias Edge snapshot state: ${it.message}") }
        decoded.sortedWith(
            compareBy(
                SyncAliasEdgePayloadV1::targetEntityType,
                SyncAliasEdgePayloadV1::leftGeneration,
                SyncAliasEdgePayloadV1::leftSyncId,
                SyncAliasEdgePayloadV1::rightSyncId,
            ),
        ).forEach { edge ->
            aliasResolver.applyEdge(syncSpaceId, edge, sourceOperationId = null, now = now)
        }
    }

    private suspend fun restoreBlobIndexes(
        syncSpaceId: String,
        shard: SyncSnapshotShardEntity,
        now: Long,
    ) {
        val manifests =
            runCatching {
                json.decodeFromString<List<SyncBlobManifestWire>>(shard.blobManifestIndexJson.ifBlank { "[]" })
            }.getOrElse {
                throw SnapshotCorruptedError(
                    "Corrupted Blob manifest index in lane ${shard.replicationLaneId}: ${it.message}",
                )
            }
        val references =
            runCatching {
                json.decodeFromString<List<SyncBlobReferenceWire>>(shard.blobReferenceIndexJson.ifBlank { "[]" })
            }.getOrElse {
                throw SnapshotCorruptedError(
                    "Corrupted Blob reference index in lane ${shard.replicationLaneId}: ${it.message}",
                )
            }
        val manifestHashes = manifests.map { it.hash }.toSet()
        if (references.any { it.ownerEntityType == "__operation__" }) {
            throw SnapshotCorruptedError(
                "Snapshot Blob index must not contain Operation-owned references"
            )
        }
        for (reference in references) {
            if (reference.replicationLaneId != shard.replicationLaneId) {
                throw SnapshotCorruptedError(
                    "Blob reference lane ${reference.replicationLaneId} does not match shard ${shard.replicationLaneId}",
                )
            }
            if (reference.hash !in manifestHashes) {
                throw SnapshotCorruptedError("Blob reference ${reference.hash} has no manifest in the same shard")
            }
        }
        blobState.clearMaterializedLaneReferences(syncSpaceId, shard.replicationLaneId)
        for (manifest in manifests.sortedBy { it.hash }) {
            blobState.registerManifest(
                manifest = manifest,
                initialState = SyncBlobAvailabilityState.BLOB_MISSING,
                now = now,
            )
        }
        for (reference in references) {
            blobState.addReference(
                syncSpaceId = syncSpaceId,
                lane = reference.replicationLaneId,
                ownerEntityType = reference.ownerEntityType,
                ownerEntitySyncId = reference.ownerEntitySyncId,
                ownerEntityGeneration = reference.ownerEntityGeneration,
                referenceKind = reference.referenceKind,
                hash = reference.hash,
                now = now,
            )
        }
    }

    private suspend fun projectSnapshotDeletion(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
    ) {
        val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
            ?: throw SnapshotDependencyMissingError("Snapshot tombstone has no local Space binding")
        val mapping = database.syncIdentityMappingDao().findBySyncId(syncSpaceId, entityType, entitySyncId)
            ?: return
        if (generation < mapping.generation) return
        when (entityType) {
            SyncEntityType.ARTICLE.wireName -> {
                blobState.removeOwnerReferences(
                    syncSpaceId = syncSpaceId,
                    lane = SyncReplicationLane.ARTICLE_STATE.wireName,
                    ownerEntityType = SyncEntityType.ARTICLE.wireName,
                    ownerEntitySyncId = entitySyncId,
                    ownerEntityGeneration = generation,
                )
                val article = database.articleDao().queryById(mapping.localId)?.article
                if (article?.accountId == binding.localAccountId) {
                    database.articleDao().deleteByIds(listOf(mapping.localId))
                }
            }
            SyncEntityType.FEED.wireName -> {
                val feed = database.feedDao().queryById(mapping.localId)
                if (feed != null && feed.accountId == binding.localAccountId) {
                    database.feedDao().delete(feed)
                    filterRepository.deleteByFeed(feed.id)
                    websiteParsePreferenceRepository.delete(feed.id)
                    rssHubSubscriptionRepository.remove(feed.id)
                }
            }
            SyncEntityType.GROUP.wireName -> {
                val group = database.groupDao().queryById(mapping.localId)
                if (group != null && group.accountId == binding.localAccountId) {
                    database.groupDao().delete(group)
                }
            }
            SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName -> {
                val feedMapping =
                    database.syncIdentityMappingDao().findBySyncId(
                        syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        mapping.localId,
                    )
                if (feedMapping != null) {
                    websiteParsePreferenceRepository.applyUserSyncState(
                        feedMapping.localId,
                        null,
                    )
                }
            }
            SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName -> {
                val feedMapping =
                    database.syncIdentityMappingDao().findBySyncId(
                        syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        mapping.localId,
                    )
                if (feedMapping != null) {
                    rssHubSubscriptionRepository.replaceSyncSource(
                        feedMapping.localId,
                        null,
                    )
                }
            }
        }
        if (generation > mapping.generation) {
            database.syncIdentityMappingDao().update(
                mapping.copy(generation = generation, updatedAt = System.currentTimeMillis())
            )
        }
    }

    private suspend fun persistRecoveryCapsule(
        bundle: SyncSnapshotBundleEntity,
        targetCoverage: SyncCoverage,
        presentLanes: Set<String>,
        pendingOutboxIds: List<String>,
        reason: String,
        now: Long,
    ) {
        val operationIds =
            database.syncOperationDao().listAllForRecovery(bundle.syncSpaceId)
                .filter { operation ->
                    operation.replicationLaneId in presentLanes &&
                    operation.sequence >
                        (targetCoverage[operation.replicationLaneId]?.get(operation.actorIncarnationId) ?: 0L)
                }
                .map { it.operationId }

        val coverageRows = database.syncInboxDao().listCoverage(bundle.syncSpaceId)
        fun coverage(selector: (SyncCoverageEntity) -> Long): SyncCoverage =
            coverageRows.groupBy(SyncCoverageEntity::replicationLaneId)
                .mapValues { (_, rows) ->
                    rows.associate { row -> row.actorIncarnationId to selector(row) }
                        .filterValues { it > 0L }
                }
                .filterValues { it.isNotEmpty() }
        val vector =
            SyncCoverageVector(
                received = coverage(SyncCoverageEntity::receivedPrefix),
                applied = coverage(SyncCoverageEntity::appliedPrefix),
                retained = coverage(SyncCoverageEntity::retainedPrefix),
                snapshot = coverage(SyncCoverageEntity::snapshotPrefix),
                stableGc = coverage(SyncCoverageEntity::stableGcPrefix),
            )
        val recoveryStateJson = buildLocalRecoveryState(bundle.syncSpaceId, vector)
        database.syncGenesisDao().upsertRecoveryCapsule(
            SyncRecoveryCapsuleEntity(
                capsuleId = "recovery:" + UUID.randomUUID(),
                syncSpaceId = bundle.syncSpaceId,
                targetSnapshotBundleId = bundle.snapshotBundleId,
                coverageJson = SyncOperationCanonicalizer.canonicalJson(json.encodeToString(vector)),
                operationIdsJson = SyncOperationCanonicalizer.canonicalJson(json.encodeToString(operationIds.sorted())),
                pendingOutboxIdsJson =
                    SyncOperationCanonicalizer.canonicalJson(json.encodeToString(pendingOutboxIds.sorted())),
                recoveryStateJson = recoveryStateJson,
                reason = reason,
                createdAt = now,
            )
        )
    }

    private suspend fun buildLocalRecoveryState(
        syncSpaceId: String,
        coverage: SyncCoverageVector,
    ): String {
        val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
            ?: throw SyncRebaseUnsafeException("REBASE_UNSAFE: cannot capture recovery state without a Space binding")
        val groupMappings =
            database.syncIdentityMappingDao().findByType(syncSpaceId, SyncEntityType.GROUP.wireName)
        val feedMappings =
            database.syncIdentityMappingDao().findByType(syncSpaceId, SyncEntityType.FEED.wireName)
        val articleMappings =
            database.syncIdentityMappingDao().findByType(syncSpaceId, SyncEntityType.ARTICLE.wireName)
        val configMappings =
            listOf(
                SyncEntityType.FILTER_RULE,
                SyncEntityType.WEBSITE_RULE,
                SyncEntityType.JSON_RULE,
                SyncEntityType.RSSHUB_SETTINGS,
                SyncEntityType.WEBSITE_PARSE_PREFERENCE,
                SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE,
            ).flatMap { entityType ->
                database.syncIdentityMappingDao().findByType(syncSpaceId, entityType.wireName)
            }
        val mappings = (groupMappings + feedMappings + articleMappings + configMappings)
            .map {
                GenesisMappingSnapshot(
                    entityType = it.entityType,
                    syncId = it.syncId,
                    canonicalKey = it.canonicalKey,
                    generation = it.generation,
                )
            }
            .sortedWith(compareBy(GenesisMappingSnapshot::entityType, GenesisMappingSnapshot::syncId))

        val groupSyncByLocal = groupMappings.associate { it.localId to it.syncId }
        val groupGenerationByLocal = groupMappings.associate { it.localId to it.generation }
        val feedSyncByLocal = feedMappings.associate { it.localId to it.syncId }
        val feedGenerationByLocal = feedMappings.associate { it.localId to it.generation }
        val groups = groupMappings.mapNotNull { mapping ->
            database.groupDao().queryById(mapping.localId)
                ?.takeIf { it.accountId == binding.localAccountId }
                ?.let { GenesisGroupSnapshot(syncId = mapping.syncId, name = it.name) }
        }.sortedBy(GenesisGroupSnapshot::syncId)
        val feeds = feedMappings.mapNotNull { mapping ->
            database.feedDao().queryById(mapping.localId)
                ?.takeIf { it.accountId == binding.localAccountId }
                ?.let { feed ->
                    val groupSyncId = groupSyncByLocal[feed.groupId] ?: return@let null
                    GenesisFeedSnapshot(
                        syncId = mapping.syncId,
                        groupSyncId = groupSyncId,
                        groupGeneration =
                            groupGenerationByLocal[feed.groupId]
                                ?: throw SyncRebaseUnsafeException(
                                    "REBASE_UNSAFE: Feed ${mapping.syncId} references unmapped group ${feed.groupId}"
                                ),
                        name = feed.name,
                        icon = feed.icon,
                        url = feed.url,
                        sourceType = feed.sourceType.name.lowercase(),
                        isNotification = feed.isNotification,
                        isFullContent = feed.isFullContent,
                        isBrowser = feed.isBrowser,
                    )
                }
        }.sortedBy(GenesisFeedSnapshot::syncId)
        val articles = articleMappings.mapNotNull { mapping ->
            database.articleDao().queryById(mapping.localId)?.article
                ?.takeIf { it.accountId == binding.localAccountId }
                ?.let { article ->
                    val feedSyncId = feedSyncByLocal[article.feedId] ?: return@let null
                    GenesisArticleSnapshot(
                        syncId = mapping.syncId,
                        feedSyncId = feedSyncId,
                        feedGeneration =
                            feedGenerationByLocal[article.feedId]
                                ?: throw SyncRebaseUnsafeException(
                                    "REBASE_UNSAFE: Article ${mapping.syncId} references unmapped feed ${article.feedId}"
                                ),
                        title = article.title,
                        author = article.author,
                        link = article.link,
                        date = article.date.time,
                        description = article.shortDescription,
                        contentHtml = article.rawDescription,
                        imageUrl = article.img,
                        isUnread = article.isUnread,
                        isStarred = article.isStarred,
                        isReadLater = article.isReadLater,
                        fullContentHash =
                            database.syncBlobDao()
                                .listReferencesForOwner(
                                    syncSpaceId,
                                    SyncReplicationLane.ARTICLE_STATE.wireName,
                                    SyncEntityType.ARTICLE.wireName,
                                    mapping.syncId,
                                    mapping.generation,
                                )
                                .firstOrNull {
                                    it.referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND
                                }
                                ?.hash,
                    )
                }
        }.sortedBy(GenesisArticleSnapshot::syncId)

        val mappedGroupIds = groupMappings.map(SyncIdentityMappingEntity::localId).toSet()
        val mappedFeedIds = feedMappings.map(SyncIdentityMappingEntity::localId).toSet()
        val mappedArticleIds = articleMappings.map(SyncIdentityMappingEntity::localId).toSet()
        val configMappingsByTypeAndLocalId =
            configMappings.associateBy { it.entityType to it.localId }
        val configStateJson =
            SyncOperationCanonicalizer.canonicalJson(
                buildJsonObject {
                    put("schemaVersion", 1)
                    put(
                        "entities",
                        buildJsonArray {
                            filterRepository.getAll().sortedBy { it.id }.forEach { rule ->
                                val mapping =
                                    configMappingsByTypeAndLocalId[
                                        SyncEntityType.FILTER_RULE.wireName to rule.id
                                    ] ?: throw SyncRebaseUnsafeException(
                                        "REBASE_UNSAFE: filter rule ${rule.id} has no Sync identity"
                                    )
                                val feedSyncId =
                                    rule.feedId?.let { localFeedId ->
                                        feedSyncByLocal[localFeedId]
                                            ?: throw SyncRebaseUnsafeException(
                                                "REBASE_UNSAFE: filter rule ${rule.id} references unmapped feed $localFeedId"
                                            )
                                    }
                                add(
                                    buildJsonObject {
                                        put("entityType", SyncEntityType.FILTER_RULE.wireName)
                                        put("entitySyncId", mapping.syncId)
                                        put("generation", mapping.generation)
                                        put(
                                            "fields",
                                            buildJsonObject {
                                                put("keyword", rule.keyword)
                                                feedSyncId?.let { put("feedSyncId", it) }
                                                    ?: put("feedSyncId", JsonNull)
                                                rule.feedId?.let { localFeedId ->
                                                    put(
                                                        "feedGeneration",
                                                        feedGenerationByLocal[localFeedId]
                                                            ?: throw SyncRebaseUnsafeException(
                                                                "REBASE_UNSAFE: filter rule ${rule.id} references unmapped feed $localFeedId"
                                                            ),
                                                    )
                                                } ?: put("feedGeneration", JsonNull)
                                                rule.feedName?.let { put("feedName", it) }
                                                    ?: put("feedName", JsonNull)
                                                put("type", rule.type.name)
                                                put("enabled", rule.enabled)
                                            },
                                        )
                                    }
                                )
                            }
                            websiteRuleRepository.listSyncRules()
                                .sortedBy(WebsiteRule::id)
                                .forEach { rule ->
                                    val mapping =
                                        configMappingsByTypeAndLocalId[
                                            SyncEntityType.WEBSITE_RULE.wireName to rule.id
                                        ] ?: throw SyncRebaseUnsafeException(
                                            "REBASE_UNSAFE: website rule ${rule.id} has no Sync identity"
                                        )
                                    add(
                                        buildJsonObject {
                                            put("entityType", SyncEntityType.WEBSITE_RULE.wireName)
                                            put("entitySyncId", mapping.syncId)
                                            put("generation", mapping.generation)
                                            put(
                                                "fields",
                                                buildJsonObject {
                                                    put(
                                                        "rule",
                                                        json.parseToJsonElement(json.encodeToString(rule)),
                                                    )
                                                },
                                            )
                                        }
                                    )
                                }
                            jsonRuleRepository.listSyncRules()
                                .sortedBy(JsonRule::id)
                                .forEach { rule ->
                                    val mapping =
                                        configMappingsByTypeAndLocalId[
                                            SyncEntityType.JSON_RULE.wireName to rule.id
                                        ] ?: throw SyncRebaseUnsafeException(
                                            "REBASE_UNSAFE: JSON rule ${rule.id} has no Sync identity"
                                        )
                                    add(
                                        buildJsonObject {
                                            put("entityType", SyncEntityType.JSON_RULE.wireName)
                                            put("entitySyncId", mapping.syncId)
                                            put("generation", mapping.generation)
                                            put(
                                                "fields",
                                                buildJsonObject {
                                                    put(
                                                        "rule",
                                                        json.parseToJsonElement(json.encodeToString(rule)),
                                                    )
                                                },
                                            )
                                        }
                                    )
                                }
                            val rssHubMapping =
                                configMappingsByTypeAndLocalId[
                                    SyncEntityType.RSSHUB_SETTINGS.wireName to "rsshub-settings"
                                ] ?: throw SyncRebaseUnsafeException(
                                    "REBASE_UNSAFE: RSSHub settings have no Sync identity"
                                )
                            add(
                                buildJsonObject {
                                    put("entityType", SyncEntityType.RSSHUB_SETTINGS.wireName)
                                    put("entitySyncId", rssHubMapping.syncId)
                                    put("generation", rssHubMapping.generation)
                                    put(
                                        "fields",
                                        buildJsonObject {
                                            put(
                                                "settings",
                                                json.parseToJsonElement(
                                                    json.encodeToString(rssHubSettingsRepository.current())
                                                ),
                                            )
                                        },
                                    )
                                }
                            )
                            websiteParsePreferenceRepository
                                .listUserSyncStates(mappedFeedIds)
                                .entries
                                .sortedBy { (localFeedId, _) ->
                                    feedSyncByLocal[localFeedId].orEmpty()
                                }
                                .forEach { (localFeedId, state) ->
                                    val feedSyncId =
                                        feedSyncByLocal[localFeedId]
                                            ?: throw SyncRebaseUnsafeException(
                                                "REBASE_UNSAFE: website parse preference references unmapped feed $localFeedId"
                                            )
                                    val mapping =
                                        configMappingsByTypeAndLocalId[
                                            SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName to feedSyncId
                                        ] ?: throw SyncRebaseUnsafeException(
                                            "REBASE_UNSAFE: website parse preference for $feedSyncId has no Sync identity"
                                        )
                                    add(
                                        buildJsonObject {
                                            put(
                                                "entityType",
                                                SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName,
                                            )
                                            put("entitySyncId", mapping.syncId)
                                            put("generation", mapping.generation)
                                            put(
                                                "fields",
                                                buildJsonObject {
                                                    put(
                                                        "preference",
                                                        buildJsonObject {
                                                            put("feedSyncId", feedSyncId)
                                                            put(
                                                                "feedGeneration",
                                                                feedGenerationByLocal[localFeedId]
                                                                    ?: throw SyncRebaseUnsafeException(
                                                                        "REBASE_UNSAFE: website parse preference references unmapped feed $localFeedId"
                                                                    ),
                                                            )
                                                            put(
                                                                "dynamicRenderingEnabled",
                                                                state.dynamicRenderingEnabled,
                                                            )
                                                            state.preferredRuleId?.let {
                                                                put("preferredRuleId", it)
                                                            } ?: put("preferredRuleId", JsonNull)
                                                            state.preferredRuleName?.let {
                                                                put("preferredRuleName", it)
                                                            } ?: put("preferredRuleName", JsonNull)
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    )
                                }
                            rssHubSubscriptionRepository
                                .listSyncSources(mappedFeedIds)
                                .entries
                                .sortedBy { (localFeedId, _) ->
                                    feedSyncByLocal[localFeedId].orEmpty()
                                }
                                .forEach { (localFeedId, sourceUrl) ->
                                    val feedSyncId =
                                        feedSyncByLocal[localFeedId]
                                            ?: throw SyncRebaseUnsafeException(
                                                "REBASE_UNSAFE: RSSHub subscription source references unmapped feed $localFeedId"
                                            )
                                    val mapping =
                                        configMappingsByTypeAndLocalId[
                                            SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName to feedSyncId
                                        ] ?: throw SyncRebaseUnsafeException(
                                            "REBASE_UNSAFE: RSSHub subscription source for $feedSyncId has no Sync identity"
                                        )
                                    add(
                                        buildJsonObject {
                                            put(
                                                "entityType",
                                                SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName,
                                            )
                                            put("entitySyncId", mapping.syncId)
                                            put("generation", mapping.generation)
                                            put(
                                                "fields",
                                                buildJsonObject {
                                                    put(
                                                        "source",
                                                        buildJsonObject {
                                                            put("feedSyncId", feedSyncId)
                                                            put(
                                                                "feedGeneration",
                                                                feedGenerationByLocal[localFeedId]
                                                                    ?: throw SyncRebaseUnsafeException(
                                                                        "REBASE_UNSAFE: RSSHub subscription source references unmapped feed $localFeedId"
                                                                    ),
                                                            )
                                                            put("sourceUrl", sourceUrl)
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    )
                                }
                        },
                    )
                }.toString(),
            )
        val unmappedLocalStateJson = SyncOperationCanonicalizer.canonicalJson(
            buildJsonObject {
                put("schemaVersion", 1)
                put("groups", buildJsonArray {
                    database.groupDao().queryAll(binding.localAccountId)
                        .filter { it.id !in mappedGroupIds }
                        .forEach { group ->
                            add(buildJsonObject {
                                put("localId", group.id)
                                put("name", group.name)
                                put("accountId", group.accountId)
                            })
                        }
                })
                put("feeds", buildJsonArray {
                    database.feedDao().queryAll(binding.localAccountId)
                        .filter { it.id !in mappedFeedIds }
                        .forEach { feed ->
                            add(buildJsonObject {
                                put("localId", feed.id)
                                put("name", feed.name)
                                feed.icon?.let { put("icon", it) } ?: put("icon", JsonNull)
                                put("url", feed.url)
                                put("groupLocalId", feed.groupId)
                                put("accountId", feed.accountId)
                                put("isNotification", feed.isNotification)
                                put("isFullContent", feed.isFullContent)
                                put("isBrowser", feed.isBrowser)
                                put("sourceType", feed.sourceType.name.lowercase())
                            })
                        }
                })
                put("articles", buildJsonArray {
                    database.articleDao().queryAllByAccountId(binding.localAccountId)
                        .filter { it.id !in mappedArticleIds }
                        .forEach { article ->
                            add(buildJsonObject {
                                put("localId", article.id)
                                put("date", article.date.time)
                                put("title", article.title)
                                article.author?.let { put("author", it) } ?: put("author", JsonNull)
                                put("rawDescription", article.rawDescription)
                                put("shortDescription", article.shortDescription)
                                put("link", article.link)
                                put("feedLocalId", article.feedId)
                                put("accountId", article.accountId)
                                put("isUnread", article.isUnread)
                                put("isStarred", article.isStarred)
                                put("isReadLater", article.isReadLater)
                                article.updateAt?.let { put("updateAt", it.time) } ?: put("updateAt", JsonNull)
                            })
                        }
                })
            }.toString(),
        )

        val fieldVersions = database.syncInboxDao().listFieldVersions(syncSpaceId).map {
            RecoveryFieldVersionSnapshot(
                entityType = it.entityType,
                entitySyncId = it.entitySyncId,
                fieldId = it.fieldId,
                entityGeneration = it.entityGeneration,
                versionToken = it.versionToken,
                sourceOperationId = it.sourceOperationId,
                valueJson = it.valueJson,
            )
        }
        val rollbackBaselines = database.syncInboxDao().listRollbackBaselines(syncSpaceId).map {
            RecoveryRollbackBaselineSnapshot(
                entityType = it.entityType,
                entitySyncId = it.entitySyncId,
                fieldId = it.fieldId,
                entityGeneration = it.entityGeneration,
                valueJson = it.valueJson,
            )
        }
        val tombstones = database.syncInboxDao().listTombstones(syncSpaceId).map {
            RecoveryTombstoneSnapshot(
                entityType = it.entityType,
                entitySyncId = it.entitySyncId,
                entityGeneration = it.entityGeneration,
                versionToken = it.versionToken,
                sourceOperationId = it.sourceOperationId,
            )
        }
        val aliasEdges = database.syncAliasDao().listEdges(syncSpaceId).map {
            SyncAliasEdgePayloadV1(
                targetEntityType = it.entityType,
                leftSyncId = it.leftSyncId,
                leftGeneration = it.leftGeneration,
                rightSyncId = it.rightSyncId,
                rightGeneration = it.rightGeneration,
            )
        }
        val state = LocalRecoveryStateSnapshot(
            mappings = mappings,
            libraryState = GenesisLibraryState(groups = groups, feeds = feeds),
            articleState = GenesisArticleState(articles = articles),
            unmappedLocalStateJson = unmappedLocalStateJson,
            configRulesJson = SyncOperationCanonicalizer.canonicalJson(filterRepository.exportRules()),
            configStateJson = configStateJson,
            fieldVersions = fieldVersions,
            rollbackBaselines = rollbackBaselines,
            tombstones = tombstones,
            aliasEdges = aliasEdges,
            coverage = coverage,
        )
        return SyncOperationCanonicalizer.canonicalJson(json.encodeToString(state))
    }

    internal var transactionRunner: (suspend (suspend () -> Unit) -> Unit)? = null

    private suspend fun <T> runInTransaction(block: suspend () -> T): T {
        val runner = transactionRunner
        if (runner != null) {
            var result: T? = null
            runner {
                result = block()
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
        return database.withTransaction { block() }
    }
}
