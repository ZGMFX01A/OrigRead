package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.identity.LibraryGenesisIdentityBackfill
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

private val PHASE_A_SNAPSHOT_LANES =
    listOf(
        SyncReplicationLane.CORE_META,
        SyncReplicationLane.LIBRARY,
        SyncReplicationLane.ARTICLE_STATE,
        SyncReplicationLane.CONFIG,
        SyncReplicationLane.AI_HISTORY,
        SyncReplicationLane.AUTH,
    )

private fun snapshotEntityTypesForLane(lane: SyncReplicationLane): Set<String> =
    when (lane) {
        SyncReplicationLane.CORE_META ->
            setOf(SyncEntityType.ALIAS_EDGE.wireName)
        SyncReplicationLane.LIBRARY ->
            setOf(
                SyncEntityType.GROUP.wireName,
                SyncEntityType.FEED.wireName,
            )
        SyncReplicationLane.ARTICLE_STATE ->
            setOf(SyncEntityType.ARTICLE.wireName)
        SyncReplicationLane.CONFIG ->
            setOf(
                SyncEntityType.FILTER_RULE.wireName,
                SyncEntityType.WEBSITE_RULE.wireName,
                SyncEntityType.JSON_RULE.wireName,
                SyncEntityType.RSSHUB_SETTINGS.wireName,
                SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName,
                SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName,
            )
        SyncReplicationLane.AI_HISTORY ->
            setOf(
                SyncEntityType.CONVERSATION.wireName,
                SyncEntityType.CONVERSATION_ARTICLE.wireName,
                SyncEntityType.MESSAGE.wireName,
                SyncEntityType.TOOL_CALL.wireName,
                SyncEntityType.CONTEXT_REF.wireName,
                SyncEntityType.EVIDENCE_BLOCK.wireName,
                SyncEntityType.CITATION_REF.wireName,
                SyncEntityType.CITATION_ANNOTATION.wireName,
                SyncEntityType.CITATION_ANNOTATION_REF.wireName,
            )
        SyncReplicationLane.AUTH -> emptySet()
    }

@Serializable
internal data class GenesisMappingSnapshot(
    val entityType: String,
    val syncId: String,
    val canonicalKey: String? = null,
    val generation: Long,
)

@Serializable
internal data class GenesisGroupSnapshot(
    val syncId: String,
    val name: String,
)

@Serializable
internal data class GenesisFeedSnapshot(
    val syncId: String,
    val groupSyncId: String,
    val groupGeneration: Long? = null,
    val name: String,
    val icon: String? = null,
    val url: String,
    val sourceType: String,
    val isNotification: Boolean,
    val isFullContent: Boolean,
    val isBrowser: Boolean,
)

@Serializable
internal data class GenesisArticleSnapshot(
    val syncId: String,
    val feedSyncId: String,
    val feedGeneration: Long? = null,
    val title: String,
    val author: String? = null,
    val link: String,
    val date: Long,
    val description: String = "",
    val contentHtml: String = "",
    val imageUrl: String? = null,
    val isUnread: Boolean,
    val isStarred: Boolean,
    val isReadLater: Boolean,
    val fullContentHash: String? = null,
)

@Serializable
internal data class GenesisFieldVersionSnapshot(
    val entitySyncId: String,
    val fieldId: String,
    val valueJson: String,
    val versionToken: String,
    val entityType: String? = null,
    val entityGeneration: Long? = null,
    val causalContextJson: String? = null,
    val logicalClock: Long? = null,
)

@Serializable
internal data class GenesisCoreState(
    val schemaVersion: Int = 1,
    val genesisBaselineId: String,
    val mappings: List<GenesisMappingSnapshot>,
)

@Serializable
internal data class GenesisLibraryState(
    val schemaVersion: Int = 1,
    val groups: List<GenesisGroupSnapshot>,
    val feeds: List<GenesisFeedSnapshot>,
)

@Serializable
internal data class GenesisArticleState(
    val schemaVersion: Int = 1,
    val articles: List<GenesisArticleSnapshot>,
)

@Serializable
internal data class GenesisShardDescriptor(
    val replicationLaneId: String,
    val shardHash: String,
    val frontierByActorJson: String,
)

@Serializable
internal data class GenesisShardHashMaterial(
    val replicationLaneId: String,
    val frontierByActorJson: String,
    val entityStateJson: String,
    val fieldVersionStateJson: String,
    val causalMergeMetadataJson: String,
    val genesisCoverageJson: String,
    val deletionSummaryJson: String,
    val generationSummaryJson: String,
    val blobManifestIndexJson: String,
    val blobReferenceIndexJson: String,
)

@Serializable
internal data class GenesisBundleHashMaterial(
    val schemaVersion: Int,
    val snapshotEpoch: Long,
    val crossDbCutId: String,
    val replicationPolicyHash: String,
    val requiredCoreShardIdsJson: String,
    val shardDescriptorsJson: String,
)

data class SyncGenesisCutoverResult(
    val syncSpaceId: String,
    val genesisSessionId: String,
    val genesisBaselineId: String,
    val crossDbCutId: String,
    val snapshotBundleId: String,
    val tailOperationsBuilt: Int,
)

private data class GenesisPreparation(
    val cut: SyncGenesisCut,
    val snapshotBundleId: String?,
    val sessionWasAlreadyActive: Boolean,
)

/**
 * R10 Genesis Cutover for all local replication lanes.
 *
 * Edition-owned lanes such as AI_HISTORY contribute their fixed-view state and writer frontier through
 * [SyncBusinessProjectionExtension]. The Reader DB remains the durable session authority.
 */
@Singleton
class SyncGenesisSnapshotService @Inject constructor(
    private val database: AndroidDatabase,
    private val coordinator: SyncRuntimeCoordinator,
    private val identityBackfill: LibraryGenesisIdentityBackfill,
    private val filterRepository: ArticleFilterRepository,
    private val websiteRuleRepository: WebsiteRuleRepository,
    private val jsonRuleRepository: JsonRuleRepository,
    private val rssHubSettingsRepository: RssHubSettingsRepository,
    private val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    private val operationBuilder: ReaderOperationBuilder,
    private val authLedgerService: AndroidSyncAuthLedgerService,
    private val readerCacheHelper: ReaderCacheHelper,
    private val localBlobStore: SyncLocalBlobStore,
    private val signingKeys: SyncDeviceSigningKeyStore,
    private val projectionExtensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension> = emptySet(),
) {
    @Inject lateinit var joinBaselineCapture: SyncSpaceJoinBaselineCapture
    @Inject lateinit var pagedBuilder: SyncPagedGenesisBuilder
    @Inject lateinit var pagedExecution: SyncGenesisExecution
    @Inject lateinit var pagedExport: SyncPagedSnapshotExport
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val blobState = SyncBlobStateService(database)

    data class PagedRunOptions(val localAccountId: Int, val syncSpaceId: String? = null,
        val genesisSessionId: String = UUID.randomUUID().toString(), val now: Long = System.currentTimeMillis())

    /** 分页捕获显式进入既有 cut/barrier/Tail 生命周期，旧格式仅保留给独立的非 LAN 契约。 */
    suspend fun runPaged(options: PagedRunOptions): SyncGenesisCutoverResult =
        pagedExecution.run(SyncGenesisExecution.Options(accountId = options.localAccountId, space = options.syncSpaceId,
            sessionId = options.genesisSessionId, now = options.now,
            prepare = { space -> pagedBuilder.prepare(SyncSnapshotArticleContent.Preparation(options.localAccountId, space, options.now)) }, capture = { input ->
                pagedBuilder.capture(SyncPagedGenesisBuilder.Options(input.accountId, input.session, input.cut))
            }) { input ->
            pagedBuilder.build(SyncPagedGenesisBuilder.Options(input.accountId, input.session, input.cut))
        })

    /** 对外分页清单读取仍受正式来源发布与策略 scope 检查，不能导出接收暂存区。 */
    suspend fun exportPagedManifest(options: SyncPagedSnapshotExport.Scope): SyncPagedSnapshotManifest = pagedExport.manifest(options)

    /** 一次只读取当前已发布字节页，供 HTTPS 页面路由发送。 */
    suspend fun exportPagedPage(options: SyncPagedSnapshotExport.Page): SyncSnapshotBytePage = pagedExport.page(options)

    suspend fun run(
        localAccountId: Int,
        syncSpaceId: String? = null,
        genesisSessionId: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
    ): SyncGenesisCutoverResult {
        val prepared = coordinator.prepareSpace(localAccountId, syncSpaceId, now)
        authLedgerService.ensureLocalSpaceRoot(prepared.syncSpaceId, now)
        coordinator.beginGenesisCapture(localAccountId, genesisSessionId, now)
        try {
            val preparation =
                coordinator.captureGenesisCutWithBarrier(localAccountId, now) { cut, session ->
                    val actorIds =
                        database.syncRuntimeDao().listActors(cut.syncSpaceId)
                            .map { it.actorIncarnationId }
                            .toSet()
                    val projectionFrontiers = linkedMapOf<String, MutableMap<String, Long>>()
                    projectionExtensions.forEach { extension ->
                        extension.genesisLaneFrontiers(
                            syncSpaceId = cut.syncSpaceId,
                            crossDbCutId = cut.crossDbCutId,
                            actorIncarnationIds = actorIds,
                            capturedAt = cut.capturedAt,
                        ).forEach { (lane, actors) ->
                            val target = projectionFrontiers.getOrPut(lane) { linkedMapOf() }
                            actors.forEach { (actorId, sequence) ->
                                target[actorId] = maxOf(target[actorId] ?: 0L, sequence)
                            }
                        }
                    }
                    val mergedFrontiers =
                        cut.laneFrontiers.mapValues { (_, actors) -> actors.toMutableMap() }
                            .toMutableMap()
                    projectionFrontiers.forEach { (lane, actors) ->
                        val target = mergedFrontiers.getOrPut(lane) { linkedMapOf() }
                        actors.forEach { (actorId, sequence) ->
                            target[actorId] = maxOf(target[actorId] ?: 0L, sequence)
                        }
                    }
                    val effectiveCut =
                        cut.copy(
                            laneFrontiers =
                                mergedFrontiers.mapValues { (_, actors) ->
                                    actors.toSortedMap()
                                }
                        )
                    val effectiveSession =
                        if (effectiveCut.laneFrontiers != cut.laneFrontiers) {
                            session.copy(
                                cutFrontierJson = SyncGenesisCodec.encodeFrontiers(effectiveCut.laneFrontiers),
                                updatedAt = now,
                            ).also { updated ->
                                database.withTransaction {
                                    database.syncGenesisDao().upsertSession(updated)
                                }
                            }
                        } else {
                            session
                        }
                    var snapshotBundleId: String? = null
                    var sessionWasAlreadyActive = false
                    if (effectiveSession.state == SyncGenesisStage.ACTIVE.name) {
                        sessionWasAlreadyActive = true
                        snapshotBundleId = effectiveSession.snapshotBundleId
                        database.withTransaction {
                            val binding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                            database.syncRuntimeDao().upsertBinding(
                                binding.copy(
                                    lifecycleState = SyncSpaceLifecycleState.ACTIVE.name,
                                    genesisSessionId = null,
                                    updatedAt = now,
                                ),
                            )
                        }
                    } else if (effectiveSession.state != SyncGenesisStage.SNAPSHOT_BUILT.name &&
                        effectiveSession.state != SyncGenesisStage.TAIL_REPLAY.name
                    ) {
                        snapshotBundleId =
                            buildAndPersistSnapshot(
                                localAccountId,
                                effectiveSession,
                                effectiveCut,
                                now,
                            )
                    } else {
                        snapshotBundleId = effectiveSession.snapshotBundleId
                    }
                    GenesisPreparation(effectiveCut, snapshotBundleId, sessionWasAlreadyActive)
                }
            val cut = preparation.cut

            var tailOperationsBuilt = 0
            var effectiveBundleId: String? = preparation.snapshotBundleId
            if (!preparation.sessionWasAlreadyActive) coordinator.withGenesisBarrier(localAccountId) { session ->
            effectiveBundleId = session.snapshotBundleId ?: effectiveBundleId
            if (session.state == SyncGenesisStage.ACTIVE.name) return@withGenesisBarrier

            database.withTransaction {
                database.syncGenesisDao().upsertSession(
                    session.copy(state = SyncGenesisStage.TAIL_REPLAY.name, updatedAt = now),
                )
            }
            while (true) {
                var builtThisPass = operationBuilder.buildPending(cut.syncSpaceId, 500, now)
                projectionExtensions.forEach { extension ->
                    builtThisPass += extension.buildPendingOperations(cut.syncSpaceId, 500, now)
                }
                tailOperationsBuilt += builtThisPass
                if (builtThisPass == 0) break
            }
            database.withTransaction {
                check(database.syncOutboxDao().listPending(cut.syncSpaceId, 1).isEmpty()) {
                    "Genesis tail replay left pending local mutations"
                }
                check(projectionExtensions.none { it.hasPendingOutbox(cut.syncSpaceId) }) {
                    "Genesis tail replay left pending extension-owned mutations"
                }
                val currentBinding = checkNotNull(database.syncRuntimeDao().findBinding(localAccountId))
                database.syncRuntimeDao().upsertBinding(
                    currentBinding.copy(
                        lifecycleState = SyncSpaceLifecycleState.ACTIVE.name,
                        genesisSessionId = null,
                        updatedAt = now,
                    ),
                )
                database.syncGenesisDao().upsertSession(
                    session.copy(
                        state = SyncGenesisStage.ACTIVE.name,
                        snapshotBundleId = checkNotNull(effectiveBundleId),
                        updatedAt = now,
                    ),
                )
            }
            }

        return SyncGenesisCutoverResult(
            syncSpaceId = cut.syncSpaceId,
            genesisSessionId = cut.genesisSessionId,
            genesisBaselineId = cut.genesisBaselineId,
            crossDbCutId = cut.crossDbCutId,
            snapshotBundleId = checkNotNull(effectiveBundleId),
            tailOperationsBuilt = tailOperationsBuilt,
        )
        } catch (error: Throwable) {
            try {
                database.withTransaction {
                    val binding = database.syncRuntimeDao().findBinding(localAccountId) ?: return@withTransaction
                    val sessionId = binding.genesisSessionId ?: return@withTransaction
                    val session = database.syncGenesisDao().findSession(sessionId) ?: return@withTransaction
                    database.syncGenesisDao().upsertSession(
                        session.copy(
                            state = SyncGenesisStage.FAILED.name,
                            failureReason = error.message?.take(2_000),
                            updatedAt = now,
                        ),
                    )
                }
            } catch (_: Throwable) {
                // Preserve the original cutover failure even if failure journaling itself cannot commit.
            }
            throw error
        }
    }

    suspend fun exportWire(
        snapshotBundleId: String,
        selectedLanes: Set<String>? = null,
    ): SyncSnapshotBundleWire {
        val bundle = checkNotNull(database.syncGenesisDao().findBundle(snapshotBundleId)) {
            "Snapshot bundle not found: " + snapshotBundleId
        }
        val shards = database.syncGenesisDao().listShards(snapshotBundleId)
        check(shards.isNotEmpty()) { "Snapshot bundle has no shards: " + snapshotBundleId }
        val coverage = SyncSnapshotWireCodec.coverageFromShards(
            shards.map { shard ->
                SyncSnapshotShardWire(
                    replicationLaneId = shard.replicationLaneId,
                    frontierJson = shard.frontierByActorJson,
                    entityStateJson = shard.entityStateJson,
                    fieldVersionStateJson = shard.fieldVersionStateJson,
                    causalMetadataJson = shard.causalMergeMetadataJson,
                    genesisCoverageJson = shard.genesisCoverageJson,
                    deletionGenerationSummaryJson = "{}",
                    contentHash = shard.shardHash,
                    deletionSummaryJson = shard.deletionSummaryJson,
                    generationSummaryJson = shard.generationSummaryJson,
                    blobManifestIndexJson = shard.blobManifestIndexJson,
                    blobReferenceIndexJson = shard.blobReferenceIndexJson,
                )
            },
        )
        return SyncSnapshotWireCodec.toWire(
            bundle = bundle,
            shards = shards,
            coverage = coverage,
            keyStore = signingKeys,
            selectedLanes = selectedLanes,
        )
    }

    suspend fun exportStreamShard(
        sourceSnapshotBundleId: String,
        lane: String,
    ): SyncSnapshotShardWire {
        val shard =
            requireNotNull(database.syncGenesisDao().findShard(sourceSnapshotBundleId, lane)) {
                "Snapshot shard not found: $sourceSnapshotBundleId/$lane"
            }
        return SyncSnapshotWireCodec.toWireShard(shard)
    }

    suspend fun exportStreamManifest(
        snapshotBundleId: String,
        selectedLanes: Set<String>? = null,
    ): SyncSnapshotStreamManifestWire {
        val bundle =
            requireNotNull(database.syncGenesisDao().findBundle(snapshotBundleId)) {
                "Snapshot bundle not found: $snapshotBundleId"
            }
        val descriptors = database.syncGenesisDao().listShardDescriptors(snapshotBundleId)
        require(descriptors.isNotEmpty()) { "Snapshot bundle has no shards: $snapshotBundleId" }

        val manifestLanes = descriptors.map { it.replicationLaneId }.toSet()
        val effectiveLanes = selectedLanes ?: manifestLanes
        require(manifestLanes.containsAll(effectiveLanes)) {
            "Snapshot scope contains a lane that is absent from the persisted manifest"
        }
        val requiredCoreShardIds =
            json.decodeFromString<List<String>>(bundle.requiredCoreShardIdsJson)
        require(effectiveLanes.containsAll(requiredCoreShardIds)) {
            "Snapshot scope must include required core shards"
        }

        val selectedDescriptors =
            descriptors.filter { it.replicationLaneId in effectiveLanes }
        val lightweightShards =
            selectedDescriptors.map { descriptor ->
                SyncSnapshotShardWire(
                    replicationLaneId = descriptor.replicationLaneId,
                    frontierJson = descriptor.frontierByActorJson,
                    entityStateJson = "",
                    fieldVersionStateJson = "",
                    causalMetadataJson = "",
                    genesisCoverageJson = "",
                    deletionGenerationSummaryJson = "",
                    contentHash = descriptor.shardHash,
                )
            }
        val coverage = SyncSnapshotWireCodec.coverageFromShards(lightweightShards)
        val isScoped = effectiveLanes != manifestLanes
        val policyHash =
            if (isScoped) {
                SyncSnapshotWireCodec.scopedPolicyHash(
                    bundle.replicationPolicyHash,
                    effectiveLanes,
                )
            } else {
                bundle.replicationPolicyHash
            }
        val wireBundleId =
            if (isScoped) {
                SyncSnapshotWireCodec.scopedSnapshotBundleId(
                    bundle.snapshotBundleId,
                    policyHash,
                )
            } else {
                bundle.snapshotBundleId
            }
        val unsigned =
            SyncSnapshotStreamManifestWire(
                sourceSnapshotBundleId = bundle.snapshotBundleId,
                snapshotBundleId = wireBundleId,
                syncSpaceId = bundle.syncSpaceId,
                snapshotClass = bundle.snapshotClass,
                genesisBaselineId = SyncSnapshotWireCodec.genesisBaselineId(bundle),
                rootHash = "",
                policyHash = policyHash,
                capturedAt = bundle.createdAt,
                shardDescriptors =
                    selectedDescriptors.map {
                        SyncSnapshotShardDescriptorWire(
                            replicationLaneId = it.replicationLaneId,
                            contentHash = it.shardHash,
                            frontierJson = it.frontierByActorJson,
                        )
                    },
                coverage = coverage,
                hashSchemaVersion = SyncSnapshotWireCodec.HASH_SCHEMA_VERSION,
                schemaVersion = bundle.schemaVersion,
                snapshotEpoch = bundle.snapshotEpoch,
                crossDbCutId = bundle.crossDbCutId,
                requiredCoreShardIds = requiredCoreShardIds,
                coverageCommitment =
                    if (bundle.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name) {
                        SyncSnapshotWireCodec.coverageCommitment(coverage)
                    } else {
                        null
                    },
                authStabilityCheckpoint = bundle.authStabilityCheckpointId,
                authorDeviceId = bundle.createdByDeviceId,
                authorSignature = null,
            )
        val rootHash =
            SyncSnapshotWireCodec.richRootHash(
                SyncSnapshotWireCodec.fromStreamManifest(unsigned, lightweightShards)
            )
        if (!isScoped) {
            require(rootHash == bundle.rootHash) {
                "Persisted Snapshot rootHash does not match streamed manifest"
            }
        }
        val withRoot = unsigned.copy(rootHash = rootHash)
        val signature =
            signingKeys.signChunksBase64(
                bundle.createdByDeviceId,
                SyncSnapshotWireCodec.signingMaterialChunks(withRoot) { lane ->
                    val shard =
                        requireNotNull(
                            database.syncGenesisDao()
                                .findShardForStreaming(bundle.snapshotBundleId, lane)
                        ) {
                            "Snapshot shard disappeared during streaming export: $lane"
                        }
                    SyncSnapshotWireCodec.toWireShard(shard)
                },
            )
        return withRoot.copy(authorSignature = signature)
    }

    suspend fun mergeRecoverySnapshot(
        localSnapshotBundleId: String,
        target: SyncSnapshotBundleWire,
        selectedLanes: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): SyncSnapshotBundleWire {
        val baseBundle =
            requireNotNull(database.syncGenesisDao().findBundle(localSnapshotBundleId)) {
                "Local recovery Snapshot bundle not found: $localSnapshotBundleId"
            }
        val session =
            requireNotNull(
                database.syncGenesisDao()
                    .findLatestSession(baseBundle.syncSpaceId)
                    ?.takeIf { it.snapshotBundleId == localSnapshotBundleId }
            ) {
                "Genesis session is missing for recovery Snapshot merge"
            }
        require(target.syncSpaceId == baseBundle.syncSpaceId) {
            "REBASE_UNSAFE: cannot merge Snapshots from different Sync Spaces"
        }
        val crossDbCutId =
            requireNotNull(session.crossDbCutId) {
                "Genesis session has no cross-database cut for recovery Snapshot merge"
            }

        val local = exportWire(localSnapshotBundleId, selectedLanes)
        val merged =
            SyncSnapshotRecoveryMergeEngine.merge(
                local = local,
                target = target,
                selectedLanes = selectedLanes,
                now = now,
            )
        val targetCoverage =
            target.coverage.filterKeys { it in selectedLanes }
        require(coverageDominates(merged.coverage, local.coverage)) {
            "REBASE_UNSAFE: merged recovery Snapshot does not dominate local recovery state"
        }
        require(coverageDominates(merged.coverage, targetCoverage)) {
            "REBASE_UNSAFE: merged recovery Snapshot does not dominate target Snapshot"
        }

        val mergeStamp =
            SyncOperationCanonicalizer.sha256Hex(
                SyncOperationCanonicalizer.canonicalJson(
                    buildJsonObject {
                        put("localRootHash", local.rootHash)
                        put("targetRootHash", target.rootHash)
                        put(
                            "lanes",
                            buildJsonArray {
                                selectedLanes.toSortedSet().forEach { add(JsonPrimitive(it)) }
                            },
                        )
                    }.toString()
                )
            ).take(24)
        val bundleId =
            localSnapshotBundleId.substringBefore(":merge:") + ":merge:" + mergeStamp
        val requiredCoreShardIds = local.requiredCoreShardIds
        val unsigned =
            SyncSnapshotBundleWire(
                snapshotBundleId = bundleId,
                syncSpaceId = baseBundle.syncSpaceId,
                snapshotClass = SyncSnapshotClass.WORKING.name,
                genesisBaselineId = local.genesisBaselineId,
                rootHash = "",
                policyHash = local.policyHash,
                capturedAt = now,
                shards = merged.shards,
                coverage = merged.coverage,
                hashSchemaVersion = SyncSnapshotWireCodec.HASH_SCHEMA_VERSION,
                schemaVersion = baseBundle.schemaVersion,
                snapshotEpoch = baseBundle.snapshotEpoch,
                crossDbCutId = crossDbCutId,
                requiredCoreShardIds = requiredCoreShardIds,
                coverageCommitment = null,
                authStabilityCheckpoint = null,
                authorDeviceId = null,
                authorSignature = null,
            )
        val rootHash = SyncSnapshotWireCodec.richRootHash(unsigned)
        val descriptors =
            merged.shards
                .map { shard ->
                    GenesisShardDescriptor(
                        shard.replicationLaneId,
                        shard.contentHash,
                        shard.frontierJson,
                    )
                }
                .sortedBy(GenesisShardDescriptor::replicationLaneId)
        val bundle =
            SyncSnapshotBundleEntity(
                snapshotBundleId = bundleId,
                syncSpaceId = baseBundle.syncSpaceId,
                snapshotClass = SyncSnapshotClass.WORKING.name,
                schemaVersion = baseBundle.schemaVersion,
                snapshotEpoch = baseBundle.snapshotEpoch,
                crossDbCutId = crossDbCutId,
                replicationPolicyHash = local.policyHash,
                requiredCoreShardIdsJson =
                    SyncGenesisCodec.encodeStringList(requiredCoreShardIds),
                shardDescriptorsJson = json.encodeToString(descriptors),
                authStabilityCheckpointId = null,
                rootHash = rootHash,
                createdByDeviceId = baseBundle.createdByDeviceId,
                createdAt = now,
            )
        val shards =
            merged.shards.map { shard ->
                SyncSnapshotShardEntity(
                    snapshotBundleId = bundleId,
                    syncSpaceId = baseBundle.syncSpaceId,
                    replicationLaneId = shard.replicationLaneId,
                    frontierByActorJson = shard.frontierJson,
                    receivedCoverageSummaryJson = shard.frontierJson,
                    entityStateJson = shard.entityStateJson,
                    fieldVersionStateJson = shard.fieldVersionStateJson,
                    causalMergeMetadataJson = shard.causalMetadataJson,
                    genesisCoverageJson = shard.genesisCoverageJson,
                    deletionSummaryJson =
                        requireNotNull(shard.deletionSummaryJson) {
                            "Merged Snapshot deletionSummaryJson is missing"
                        },
                    generationSummaryJson =
                        requireNotNull(shard.generationSummaryJson) {
                            "Merged Snapshot generationSummaryJson is missing"
                        },
                    blobManifestIndexJson =
                        requireNotNull(shard.blobManifestIndexJson) {
                            "Merged Snapshot blobManifestIndexJson is missing"
                        },
                    blobReferenceIndexJson =
                        requireNotNull(shard.blobReferenceIndexJson) {
                            "Merged Snapshot blobReferenceIndexJson is missing"
                        },
                    shardHash = shard.contentHash,
                )
            }
        database.withTransaction {
            database.syncGenesisDao().upsertBundle(bundle)
            shards.forEach { database.syncGenesisDao().upsertShard(it) }
        }
        return exportWire(bundleId, selectedLanes)
    }

    suspend fun promoteToGcBaseline(
        snapshotBundleId: String,
        checkpointId: String,
        now: Long = System.currentTimeMillis(),
        selectedLanes: Set<String>? = null,
    ): SyncSnapshotBundleWire {
        val promotedBundleId =
            promoteToGcBaselinePersisted(
                snapshotBundleId = snapshotBundleId,
                checkpointId = checkpointId,
                now = now,
                selectedLanes = selectedLanes,
            )
        return exportWire(promotedBundleId)
    }

    suspend fun promoteToGcBaselinePersisted(
        snapshotBundleId: String,
        checkpointId: String,
        now: Long = System.currentTimeMillis(),
        selectedLanes: Set<String>? = null,
    ): String {
        require(checkpointId.isNotBlank()) { "GC_BASELINE requires AuthStabilityCheckpoint" }
        val bundle =
            requireNotNull(database.syncGenesisDao().findBundle(snapshotBundleId)) {
                "Snapshot bundle not found: $snapshotBundleId"
            }
        require(
            bundle.snapshotClass == SyncSnapshotClass.WORKING.name ||
                bundle.snapshotClass == SyncSnapshotClass.GC_BASELINE.name
        ) { "Only WORKING Snapshot can be promoted to GC_BASELINE" }

        val authHistory =
            database.syncAuthLedgerDao().list(bundle.syncSpaceId)
                .map { SyncAuthWireCodec.decode(it.authObjectJson) }
                .sortedWith(AndroidSyncAuthLedgerService.AUTH_ORDER)
        val checkpoint =
            authHistory.lastOrNull { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
                ?: error("GC_BASELINE requires an AuthStabilityCheckpoint")
        require(checkpoint.authObjectId == checkpointId) {
            "GC_BASELINE must reference the current AuthStabilityCheckpoint"
        }
        val accepted = parseAcceptedCoverage(checkpoint)
        val currentManifest = exportStreamManifest(snapshotBundleId, selectedLanes)
        require(coverageDominates(accepted, currentManifest.coverage)) {
            "GC_BASELINE Snapshot exceeds stable authorized coverage"
        }
        currentManifest.coverage.forEach { (lane, actors) ->
            actors.forEach { (actor, prefix) ->
                check(
                    !database.syncInboxDao().hasAppliedProvisionalAtOrBefore(
                        bundle.syncSpaceId,
                        lane,
                        actor,
                        prefix,
                    )
                ) {
                    "GC_BASELINE contains provisional effect at $lane/$actor<=$prefix"
                }
            }
        }
        return promoteSnapshotVariantFromManifest(
            baseBundle = bundle,
            currentManifest = currentManifest,
            snapshotClass = SyncSnapshotClass.GC_BASELINE.name,
            checkpointId = checkpointId,
            now = now,
        )
    }

    suspend fun promoteToBootstrapRecovery(
        snapshotBundleId: String,
        checkpointId: String,
        now: Long = System.currentTimeMillis(),
        selectedLanes: Set<String>? = null,
    ): SyncSnapshotBundleWire {
        val promotedBundleId =
            promoteToBootstrapRecoveryPersisted(
                snapshotBundleId = snapshotBundleId,
                checkpointId = checkpointId,
                now = now,
                selectedLanes = selectedLanes,
            )
        return exportWire(promotedBundleId)
    }

    suspend fun promoteToBootstrapRecoveryPersisted(
        snapshotBundleId: String,
        checkpointId: String,
        now: Long = System.currentTimeMillis(),
        selectedLanes: Set<String>? = null,
    ): String {
        require(checkpointId.isNotBlank()) { "BOOTSTRAP_RECOVERY requires AuthStabilityCheckpoint" }
        val bundle =
            requireNotNull(database.syncGenesisDao().findBundle(snapshotBundleId)) {
                "Snapshot bundle not found: $snapshotBundleId"
            }
        require(
            bundle.snapshotClass == SyncSnapshotClass.WORKING.name ||
                bundle.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name
        ) { "Only WORKING Snapshot can be promoted to BOOTSTRAP_RECOVERY" }

        val authHistory =
            database.syncAuthLedgerDao().list(bundle.syncSpaceId)
                .map { SyncAuthWireCodec.decode(it.authObjectJson) }
                .sortedWith(AndroidSyncAuthLedgerService.AUTH_ORDER)
        val checkpoint =
            authHistory.lastOrNull { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
                ?: error("BOOTSTRAP_RECOVERY requires an AuthStabilityCheckpoint")
        require(checkpoint.authObjectId == checkpointId) {
            "BOOTSTRAP_RECOVERY must reference the current AuthStabilityCheckpoint"
        }
        val checkpointRoot = json.parseToJsonElement(checkpoint.payloadJson).jsonObject
        val currentManifest = exportStreamManifest(snapshotBundleId, selectedLanes)
        require(
            checkpointRoot["acceptedSnapshotBundleId"]?.jsonPrimitive?.content == currentManifest.snapshotBundleId
        ) { "Recovery checkpoint does not accept Snapshot ${currentManifest.snapshotBundleId}" }
        val accepted = parseAcceptedCoverage(checkpoint)
        require(coverageDominates(accepted, currentManifest.coverage)) {
            "BOOTSTRAP_RECOVERY Snapshot exceeds stable authorized coverage"
        }

        return promoteSnapshotVariantFromManifest(
            baseBundle = bundle,
            currentManifest = currentManifest,
            snapshotClass = SyncSnapshotClass.BOOTSTRAP_RECOVERY.name,
            checkpointId = checkpointId,
            now = now,
        )
    }

    private suspend fun promoteSnapshotVariantFromManifest(
        baseBundle: SyncSnapshotBundleEntity,
        currentManifest: SyncSnapshotStreamManifestWire,
        snapshotClass: String,
        checkpointId: String,
        now: Long,
    ): String {
        val selectedLanes = currentManifest.shardDescriptors.map { it.replicationLaneId }.toSet()
        val baseShards =
            database.syncGenesisDao().listShards(baseBundle.snapshotBundleId)
                .filter { it.replicationLaneId in selectedLanes }
        val descriptors =
            baseShards.map {
                GenesisShardDescriptor(it.replicationLaneId, it.shardHash, it.frontierByActorJson)
            }.sortedBy(GenesisShardDescriptor::replicationLaneId)
        val promotedBundle =
            baseBundle.copy(
                snapshotBundleId = currentManifest.snapshotBundleId,
                snapshotClass = snapshotClass,
                replicationPolicyHash = currentManifest.policyHash,
                shardDescriptorsJson = json.encodeToString(descriptors),
                authStabilityCheckpointId = checkpointId,
                rootHash = currentManifest.rootHash,
                createdAt = now,
            )
        val promotedShards =
            baseShards.map { it.copy(snapshotBundleId = currentManifest.snapshotBundleId) }
        database.withTransaction {
            database.syncGenesisDao().upsertBundle(promotedBundle)
            promotedShards.forEach { database.syncGenesisDao().upsertShard(it) }
        }
        return currentManifest.snapshotBundleId
    }

    private suspend fun promoteSnapshotVariant(
        baseBundle: SyncSnapshotBundleEntity,
        currentWire: SyncSnapshotBundleWire,
        snapshotClass: String,
        checkpointId: String,
        now: Long,
    ): SyncSnapshotBundleWire {
        val selectedLanes = currentWire.shards.map { it.replicationLaneId }.toSet()
        val baseShards =
            database.syncGenesisDao().listShards(baseBundle.snapshotBundleId)
                .filter { it.replicationLaneId in selectedLanes }
        val descriptors =
            baseShards.map {
                GenesisShardDescriptor(it.replicationLaneId, it.shardHash, it.frontierByActorJson)
            }.sortedBy(GenesisShardDescriptor::replicationLaneId)
        val promotedBundle =
            baseBundle.copy(
                snapshotBundleId = currentWire.snapshotBundleId,
                snapshotClass = snapshotClass,
                replicationPolicyHash = currentWire.policyHash,
                shardDescriptorsJson = json.encodeToString(descriptors),
                authStabilityCheckpointId = checkpointId,
                rootHash = currentWire.rootHash,
                createdAt = now,
            )
        val promotedShards =
            baseShards.map { it.copy(snapshotBundleId = currentWire.snapshotBundleId) }
        database.withTransaction {
            database.syncGenesisDao().upsertBundle(promotedBundle)
            promotedShards.forEach { database.syncGenesisDao().upsertShard(it) }
        }
        return SyncSnapshotWireCodec.toWire(
            bundle = promotedBundle,
            shards = promotedShards,
            coverage = currentWire.coverage,
            keyStore = signingKeys,
        )
    }

    private fun parseAcceptedCoverage(checkpoint: SyncAuthProtocolObject): SyncCoverage {
        val root =
            runCatching { json.parseToJsonElement(checkpoint.payloadJson).jsonObject }
                .getOrElse { throw IllegalArgumentException("Invalid AuthStabilityCheckpoint payload", it) }
        val accepted =
            root["acceptedPrefixByActorLane"]?.jsonObject
                ?: error("AuthStabilityCheckpoint acceptedPrefixByActorLane is missing")
        return accepted.entries.associate { (lane, actorsElement) ->
            lane to actorsElement.jsonObject.entries.associate { (actor, prefixElement) ->
                val prefix = prefixElement.toString().toLongOrNull()
                    ?: error("Invalid AuthStabilityCheckpoint prefix for $lane/$actor")
                require(prefix >= 0L) { "AuthStabilityCheckpoint prefix cannot be negative" }
                actor to prefix
            }
        }
    }

    private fun coverageDominates(left: SyncCoverage, right: SyncCoverage): Boolean =
        right.all { (lane, actors) ->
            actors.all { (actor, prefix) -> (left[lane]?.get(actor) ?: 0L) >= prefix }
        }

    private suspend fun buildAndPersistSnapshot(
        accountId: Int,
        session: SyncGenesisSessionEntity,
        cut: SyncGenesisCut,
        now: Long,
    ): String {
        database.syncOperationDao().listAllForRecovery(cut.syncSpaceId).forEach { operation ->
            val inbox = database.syncInboxDao().find(operation.operationId)
            if (inbox?.state != "APPLIED") return@forEach
            val frontier =
                cut.laneFrontiers[operation.replicationLaneId]
                    ?.get(operation.actorIncarnationId) ?: 0L
            if (operation.sequence > frontier) {
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: Snapshot materialized state contains applied Operation " +
                        "${operation.replicationLaneId}/${operation.actorIncarnationId}/${operation.sequence} " +
                        "beyond declared cut frontier $frontier"
                )
            }
        }
        val report = identityBackfill.backfill(cut.syncSpaceId, accountId, now)
        check(report.conflicts.isEmpty()) {
            "Genesis identity conflicts must be resolved before Snapshot: ${report.conflicts.take(3)}"
        }

        val mappings =
            database.syncIdentityMappingDao()
                .findByType(cut.syncSpaceId, SyncEntityType.GROUP.wireName) +
                database.syncIdentityMappingDao().findByType(cut.syncSpaceId, SyncEntityType.FEED.wireName) +
                database.syncIdentityMappingDao().findByType(cut.syncSpaceId, SyncEntityType.ARTICLE.wireName) +
                database.syncIdentityMappingDao().findByType(cut.syncSpaceId, SyncEntityType.FILTER_RULE.wireName) +
                database.syncIdentityMappingDao().findByType(cut.syncSpaceId, SyncEntityType.WEBSITE_RULE.wireName) +
                database.syncIdentityMappingDao().findByType(cut.syncSpaceId, SyncEntityType.JSON_RULE.wireName) +
                database.syncIdentityMappingDao().findByType(cut.syncSpaceId, SyncEntityType.RSSHUB_SETTINGS.wireName) +
                database.syncIdentityMappingDao().findByType(cut.syncSpaceId, SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName) +
                database.syncIdentityMappingDao().findByType(cut.syncSpaceId, SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName)
        val mappingSnapshots =
            mappings.map {
                GenesisMappingSnapshot(it.entityType, it.syncId, it.canonicalKey, it.generation)
            }

        val groups = database.groupDao().queryAll(accountId)
        val feeds = database.feedDao().queryAll(accountId)
        val articles = database.articleDao().queryAllByAccountId(accountId)
        val groupMappings = mappings.filter { it.entityType == SyncEntityType.GROUP.wireName }.associateBy { it.localId }
        val feedMappings = mappings.filter { it.entityType == SyncEntityType.FEED.wireName }.associateBy { it.localId }
        val articleMappings = mappings.filter { it.entityType == SyncEntityType.ARTICLE.wireName }.associateBy { it.localId }
        val filterMappings = mappings.filter { it.entityType == SyncEntityType.FILTER_RULE.wireName }.associateBy { it.localId }
        val websiteRuleMappings = mappings.filter { it.entityType == SyncEntityType.WEBSITE_RULE.wireName }.associateBy { it.localId }
        val jsonRuleMappings = mappings.filter { it.entityType == SyncEntityType.JSON_RULE.wireName }.associateBy { it.localId }
        val websiteParsePreferenceMappings =
            mappings.filter { it.entityType == SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName }
                .associateBy { it.localId }
        val rssHubSubscriptionSourceMappings =
            mappings.filter { it.entityType == SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName }
                .associateBy { it.localId }
        val rssHubSettingsMapping =
            mappings.singleOrNull {
                it.entityType == SyncEntityType.RSSHUB_SETTINGS.wireName &&
                    it.localId == "rsshub-settings"
            }
                ?: error("Missing RSSHub settings Sync ID")
        val websiteParsePreferences =
            websiteParsePreferenceRepository.listUserSyncStates(
                feeds.mapTo(linkedSetOf()) { it.id }
            )
        val rssHubSubscriptionSources =
            rssHubSubscriptionRepository.listSyncSources(
                feeds.mapTo(linkedSetOf()) { it.id }
            )
        val aliasEdges =
            database.syncAliasDao().listEdges(cut.syncSpaceId).map { edge ->
                SyncAliasEdgePayloadV1(
                    targetEntityType = edge.entityType,
                    leftSyncId = edge.leftSyncId,
                    leftGeneration = edge.leftGeneration,
                    rightSyncId = edge.rightSyncId,
                    rightGeneration = edge.rightGeneration,
                )
            }

        val articleFullContentHashes = mutableMapOf<String, String>()
        for (article in articles) {
            val mapping = articleMappings[article.id] ?: continue
            val existingHash =
                database.syncBlobDao()
                    .listReferencesForOwner(
                        cut.syncSpaceId,
                        SyncReplicationLane.ARTICLE_STATE.wireName,
                        SyncEntityType.ARTICLE.wireName,
                        mapping.syncId,
                        mapping.generation,
                    )
                    .firstOrNull { it.referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND }
                    ?.hash
            val content =
                readerCacheHelper.readFullContentForAccount(accountId, article.id)
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
            if (content == null) {
                existingHash?.let { articleFullContentHashes[article.id] = it }
                continue
            }

            val reference = SyncBlobPayloadCodec.articleFullContentReference(content)
            localBlobStore.putUtf8Text(reference, content)
            blobState.registerManifest(
                reference.manifest,
                SyncBlobAvailabilityState.READY,
                now,
            )
            blobState.replaceOwnerReference(
                syncSpaceId = cut.syncSpaceId,
                lane = SyncReplicationLane.ARTICLE_STATE.wireName,
                ownerEntityType = SyncEntityType.ARTICLE.wireName,
                ownerEntitySyncId = mapping.syncId,
                ownerEntityGeneration = mapping.generation,
                referenceKind = reference.referenceKind,
                hash = reference.manifest.hash,
                now = now,
            )
            articleFullContentHashes[article.id] = reference.manifest.hash
        }

        val libraryState =
            GenesisLibraryState(
                groups =
                    groups.mapNotNull { group ->
                        groupMappings[group.id]?.let { mapping ->
                            GenesisGroupSnapshot(mapping.syncId, group.name)
                        }
                    }.sortedBy(GenesisGroupSnapshot::syncId),
                feeds =
                    feeds.mapNotNull { feed ->
                        val mapping = feedMappings[feed.id] ?: return@mapNotNull null
                        val groupMapping = groupMappings[feed.groupId] ?: return@mapNotNull null
                        GenesisFeedSnapshot(
                            syncId = mapping.syncId,
                            groupSyncId = groupMapping.syncId,
                            groupGeneration = groupMapping.generation,
                            name = feed.name,
                            icon = feed.icon,
                            url = feed.url,
                            sourceType = feed.sourceType.name.lowercase(),
                            isNotification = feed.isNotification,
                            isFullContent = feed.isFullContent,
                            isBrowser = feed.isBrowser,
                        )
                    }.sortedBy(GenesisFeedSnapshot::syncId),
            )
        val articleState =
            GenesisArticleState(
                articles =
                    articles.mapNotNull { article ->
                        val mapping = articleMappings[article.id] ?: return@mapNotNull null
                        val feedMapping = feedMappings[article.feedId] ?: return@mapNotNull null
                        GenesisArticleSnapshot(
                            syncId = mapping.syncId,
                            feedSyncId = feedMapping.syncId,
                            feedGeneration = feedMapping.generation,
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
                            fullContentHash = articleFullContentHashes[article.id],
                        )
                    }.sortedBy(GenesisArticleSnapshot::syncId),
            )
        val configStateJson =
            SyncOperationCanonicalizer.canonicalJson(
                buildJsonObject {
                    put("schemaVersion", 1)
                    put(
                        "entities",
                        buildJsonArray {
                            filterRepository.getAll()
                                .sortedBy { it.id }
                                .forEach { rule ->
                                    val mapping =
                                        filterMappings[rule.id]
                                            ?: error("Missing filter rule Sync ID for ${rule.id}")
                                    val feedSyncId =
                                        rule.feedId?.let { localFeedId ->
                                            feedMappings[localFeedId]?.syncId
                                                ?: error(
                                                    "Filter rule ${rule.id} references an unmapped feed $localFeedId"
                                                )
                                        }
                                    val feedGeneration =
                                        rule.feedId?.let { localFeedId ->
                                            feedMappings[localFeedId]?.generation
                                                ?: error(
                                                    "Filter rule ${rule.id} references an unmapped feed $localFeedId"
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
                                                    feedSyncId?.let { put("feedSyncId", it) } ?: put("feedSyncId", JsonNull)
                                                    feedGeneration?.let { put("feedGeneration", it) }
                                                        ?: put("feedGeneration", JsonNull)
                                                    rule.feedName?.let { put("feedName", it) } ?: put("feedName", JsonNull)
                                                    put("type", rule.type.name)
                                                    put("enabled", rule.enabled)
                                                },
                                            )
                                        }
                                    )
                                }
                            websiteRuleRepository.listSyncRules()
                                .sortedBy { it.id }
                                .forEach { rule ->
                                    val mapping =
                                        websiteRuleMappings[rule.id]
                                            ?: error("Missing website rule Sync ID for " + rule.id)
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
                                                        json.parseToJsonElement(
                                                            json.encodeToString(WebsiteRule.serializer(), rule)
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    )
                                }
                            jsonRuleRepository.listSyncRules()
                                .sortedBy { it.id }
                                .forEach { rule ->
                                    val mapping =
                                        jsonRuleMappings[rule.id]
                                            ?: error("Missing JSON rule Sync ID for " + rule.id)
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
                                                        json.parseToJsonElement(
                                                            json.encodeToString(JsonRule.serializer(), rule)
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    )
                                }
                            add(
                                buildJsonObject {
                                    put("entityType", SyncEntityType.RSSHUB_SETTINGS.wireName)
                                    put("entitySyncId", rssHubSettingsMapping.syncId)
                                    put("generation", rssHubSettingsMapping.generation)
                                    put(
                                        "fields",
                                        buildJsonObject {
                                            put(
                                                "settings",
                                                rssHubSettingsJson(rssHubSettingsRepository.current()),
                                            )
                                        },
                                    )
                                }
                            )
                            websiteParsePreferences.entries
                                .sortedBy { (localFeedId, _) ->
                                    feedMappings[localFeedId]?.syncId.orEmpty()
                                }
                                .forEach { (localFeedId, state) ->
                                    val feedSyncId =
                                        feedMappings[localFeedId]?.syncId
                                            ?: error(
                                                "Website parse preference references unmapped feed $localFeedId"
                                            )
                                    val mapping =
                                        websiteParsePreferenceMappings[feedSyncId]
                                            ?: error(
                                                "Missing Website Parse Preference Sync ID for $feedSyncId"
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
                                                                feedMappings.getValue(localFeedId).generation,
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
                            rssHubSubscriptionSources.entries
                                .sortedBy { (localFeedId, _) ->
                                    feedMappings[localFeedId]?.syncId.orEmpty()
                                }
                                .forEach { (localFeedId, sourceUrl) ->
                                    val feedSyncId =
                                        feedMappings[localFeedId]?.syncId
                                            ?: error(
                                                "RSSHub subscription source references unmapped feed $localFeedId"
                                            )
                                    val mapping =
                                        rssHubSubscriptionSourceMappings[feedSyncId]
                                            ?: error(
                                                "Missing RSSHub Subscription Source Sync ID for $feedSyncId"
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
                                                                feedMappings.getValue(localFeedId).generation,
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
        // Materialize merge evidence while original history is still available, before GC can
        // discard it. Older installations may not yet have populated the candidate table.
        database.syncOperationDao().listAllForRecovery(cut.syncSpaceId).forEach { operation ->
            val inbox = database.syncInboxDao().find(operation.operationId)
            if (operation.buildStatus != "REJECTED" && (inbox == null || inbox.state == "APPLIED")) {
                SyncPayloadMerge.candidates(listOf(operation)).forEach { (field, candidates) ->
                    candidates.forEach { candidate ->
                        database.syncInboxDao().upsertFieldCandidate(SyncFieldCandidateEntity(SyncFieldVersionEntity(
                            cut.syncSpaceId, operation.entityType, operation.entitySyncId, field,
                            operation.entityGeneration, candidate.token, operation.operationId,
                            candidate.valueJson, cut.capturedAt, candidate.causalContextJson, candidate.logicalClock)))
                    }
                }
            }
        }
        val retainedFieldCandidates = database.syncInboxDao().listFieldCandidates(cut.syncSpaceId)
        val persistedFieldVersions =
            database.syncInboxDao().listFieldVersions(cut.syncSpaceId)
                .associateBy { version ->
                    Triple(version.entityType, version.entitySyncId, version.fieldId)
                }

        fun snapshotFieldVersion(
            entityType: String,
            lane: String,
            entitySyncId: String,
            entityGeneration: Long,
            fieldId: String,
            valueJson: String,
        ): GenesisFieldVersionSnapshot {
            val canonicalValue = SyncOperationCanonicalizer.canonicalJson(valueJson)
            val persisted =
                persistedFieldVersions[Triple(entityType, entitySyncId, fieldId)]
                    ?.takeIf { version ->
                        version.entityGeneration == entityGeneration &&
                            SyncOperationCanonicalizer.canonicalJson(version.valueJson) == canonicalValue
                    }
                    ?.takeIf { version ->
                        SyncVersionToken.source(version.versionToken) == SyncVersionSource.GENESIS ||
                            (version.causalContextJson != null && version.logicalClock != null)
                    }
            return GenesisFieldVersionSnapshot(
                entitySyncId = entitySyncId,
                fieldId = fieldId,
                valueJson = canonicalValue,
                versionToken =
                    persisted?.versionToken
                        ?: SyncVersionToken.genesis(
                            cut.genesisBaselineId,
                            lane,
                            entitySyncId,
                            fieldId,
                        ),
                entityType = entityType,
                entityGeneration = entityGeneration,
                causalContextJson = persisted?.causalContextJson,
                logicalClock = persisted?.logicalClock,
            )
        }

        val configFieldVersions =
            json.parseToJsonElement(configStateJson).jsonObject
                .getValue("entities").jsonArray
                .flatMap { entityValue ->
                    val entity = entityValue.jsonObject
                    val entityType = entity.getValue("entityType").jsonPrimitive.content
                    val entitySyncId = entity.getValue("entitySyncId").jsonPrimitive.content
                    val entityGeneration =
                        entity.getValue("generation").jsonPrimitive.content.toLong()
                    val fields = entity.getValue("fields").jsonObject
                    fields.map { (fieldId, value) ->
                        snapshotFieldVersion(
                            entityType = entityType,
                            lane = SyncReplicationLane.CONFIG.wireName,
                            entitySyncId = entitySyncId,
                            entityGeneration = entityGeneration,
                            fieldId = fieldId,
                            valueJson = value.toString(),
                        )
                    }
                }
                .sortedWith(
                    compareBy(
                        GenesisFieldVersionSnapshot::entitySyncId,
                        GenesisFieldVersionSnapshot::fieldId,
                    )
                )

        val generationByEntity =
            mappingSnapshots.associate { mapping ->
                (mapping.entityType to mapping.syncId) to mapping.generation
            }

        val fieldVersions =
            buildList {
                libraryState.groups.forEach { group ->
                    add(
                        snapshotFieldVersion(
                            entityType = SyncEntityType.GROUP.wireName,
                            lane = SyncReplicationLane.LIBRARY.wireName,
                            entitySyncId = group.syncId,
                            entityGeneration =
                                generationByEntity.getValue(
                                    SyncEntityType.GROUP.wireName to group.syncId
                                ),
                            fieldId = "name",
                            valueJson = json.encodeToString(group.name),
                        ),
                    )
                }
                libraryState.feeds.forEach { feed ->
                    val generation =
                        generationByEntity.getValue(
                            SyncEntityType.FEED.wireName to feed.syncId
                        )
                    listOf(
                        "groupSyncId" to JsonPrimitive(feed.groupSyncId).toString(),
                        "groupGeneration" to
                            (feed.groupGeneration?.let { JsonPrimitive(it).toString() }
                                ?: JsonNull.toString()),
                        "name" to JsonPrimitive(feed.name).toString(),
                        "icon" to
                            (feed.icon?.let { JsonPrimitive(it).toString() }
                                ?: JsonNull.toString()),
                        "url" to JsonPrimitive(feed.url).toString(),
                        "sourceType" to JsonPrimitive(feed.sourceType).toString(),
                        "isNotification" to feed.isNotification.toString(),
                        "isFullContent" to feed.isFullContent.toString(),
                        "isBrowser" to feed.isBrowser.toString(),
                    ).forEach { (fieldId, valueJson) ->
                        add(
                            snapshotFieldVersion(
                                entityType = SyncEntityType.FEED.wireName,
                                lane = SyncReplicationLane.LIBRARY.wireName,
                                entitySyncId = feed.syncId,
                                entityGeneration = generation,
                                fieldId = fieldId,
                                valueJson = valueJson,
                            )
                        )
                    }
                }
                articleState.articles.forEach { article ->
                    val generation =
                        generationByEntity.getValue(
                            SyncEntityType.ARTICLE.wireName to article.syncId
                        )
                    listOf(
                        "feedSyncId" to JsonPrimitive(article.feedSyncId).toString(),
                        "feedGeneration" to
                            (article.feedGeneration?.let { JsonPrimitive(it).toString() }
                                ?: JsonNull.toString()),
                        "title" to JsonPrimitive(article.title).toString(),
                        "url" to JsonPrimitive(article.link).toString(),
                        "author" to
                            (article.author?.let { JsonPrimitive(it).toString() }
                                ?: JsonNull.toString()),
                        "publishedAt" to article.date.toString(),
                        "description" to JsonPrimitive(article.description).toString(),
                        "contentHtml" to JsonPrimitive(article.contentHtml).toString(),
                        "imageUrl" to
                            (article.imageUrl?.let { JsonPrimitive(it).toString() }
                                ?: JsonNull.toString()),
                        "isUnread" to article.isUnread.toString(),
                        "isStarred" to article.isStarred.toString(),
                        "isReadLater" to article.isReadLater.toString(),
                    ).forEach { (field, valueJson) ->
                        add(
                            snapshotFieldVersion(
                                entityType = SyncEntityType.ARTICLE.wireName,
                                lane = SyncReplicationLane.ARTICLE_STATE.wireName,
                                entitySyncId = article.syncId,
                                entityGeneration = generation,
                                fieldId = field,
                                valueJson = valueJson,
                            )
                        )
                    }
                    article.fullContentHash?.let { hash ->
                        add(
                            snapshotFieldVersion(
                                entityType = SyncEntityType.ARTICLE.wireName,
                                lane = SyncReplicationLane.ARTICLE_STATE.wireName,
                                entitySyncId = article.syncId,
                                entityGeneration = generation,
                                fieldId = SYNC_ARTICLE_FULL_CONTENT_FIELD,
                                valueJson = JsonPrimitive(hash).toString(),
                            )
                        )
                    }
                }
            }.sortedWith(compareBy(GenesisFieldVersionSnapshot::entitySyncId, GenesisFieldVersionSnapshot::fieldId))

        val projectionEntities =
            buildList {
                projectionExtensions.forEach { extension ->
                    addAll(
                        extension.prepareGenesis(
                            syncSpaceId = cut.syncSpaceId,
                            genesisBaselineId = cut.genesisBaselineId,
                            now = now,
                        )
                    )
                }
            }.sortedWith(
                compareBy(
                    SyncGenesisProjectionEntity::entityType,
                    SyncGenesisProjectionEntity::entitySyncId,
                )
            )
        val projectionEntityStateJson =
            SyncOperationCanonicalizer.canonicalJson(
                buildJsonObject {
                    put("schemaVersion", 1)
                    put("lane", SyncReplicationLane.AI_HISTORY.wireName)
                    put(
                        "entities",
                        buildJsonArray {
                            projectionEntities.forEach { entity ->
                                add(
                                    buildJsonObject {
                                        put("entityType", entity.entityType)
                                        put("entitySyncId", entity.entitySyncId)
                                        put("generation", entity.generation)
                                        put(
                                            "fields",
                                            json.parseToJsonElement(entity.fieldsJson).jsonObject,
                                        )
                                    }
                                )
                            }
                        },
                    )
                }.toString(),
            )
        val projectionFieldVersions =
            projectionEntities
                .flatMap { entity ->
                    json.parseToJsonElement(entity.fieldsJson).jsonObject
                        .entries
                        .sortedBy { it.key }
                        .map { (fieldId, value) ->
                            snapshotFieldVersion(
                                entityType = entity.entityType,
                                lane = SyncReplicationLane.AI_HISTORY.wireName,
                                entitySyncId = entity.entitySyncId,
                                entityGeneration = entity.generation,
                                fieldId = fieldId,
                                valueJson = value.toString(),
                            )
                        }
                }
                .sortedWith(
                    compareBy(
                        GenesisFieldVersionSnapshot::entityType,
                        GenesisFieldVersionSnapshot::entitySyncId,
                        GenesisFieldVersionSnapshot::fieldId,
                    )
                )
        val projectionFieldVersionJson =
            SyncOperationCanonicalizer.canonicalJson(
                json.encodeToString(projectionFieldVersions)
            )

        val snapshotBundleId = "genesis:v1:${cut.genesisBaselineId}"
        val authObjects =
            database.syncAuthLedgerDao().list(cut.syncSpaceId)
                .map { SyncAuthWireCodec.decode(it.authObjectJson) }
                .sortedWith(compareBy(SyncAuthProtocolObject::authEpoch, SyncAuthProtocolObject::authSequence))
        check(authObjects.any { it.objectType == SyncAuthObjectType.SPACE_ROOT }) {
            "Genesis AUTH shard requires a verified SPACE_ROOT"
        }
        val authEntityStateJson =
            SyncOperationCanonicalizer.canonicalJson(
                buildJsonObject {
                    put("schemaVersion", 1)
                    put("lane", SyncReplicationLane.AUTH.wireName)
                    put(
                        "entities",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("entityType", "auth_ledger")
                                    put("entitySyncId", cut.syncSpaceId + ":auth")
                                    put("generation", authObjects.maxOf(SyncAuthProtocolObject::authEpoch))
                                    put(
                                        "fields",
                                        buildJsonObject {
                                            put(
                                                "objects",
                                                buildJsonArray {
                                                    authObjects.forEach { auth ->
                                                        add(json.parseToJsonElement(json.encodeToString(auth)))
                                                    }
                                                },
                                            )
                                        },
                                    )
                                }
                            )
                        },
                    )
                }.toString(),
            )

        val policyHash = SyncGenesisCodec.hashCanonicalJson(
            SyncGenesisCodec.encodePolicy(
                lanes = PHASE_A_SNAPSHOT_LANES.map(SyncReplicationLane::wireName),
                fieldPolicies = mapOf(
                    "isStarred" to SyncGenesisMergePolicy.STARRED_WINS.name,
                    "isUnread" to SyncGenesisMergePolicy.READ_WINS.name,
                ),
            ),
        )
        val persistedTombstones =
            database.syncInboxDao().listTombstones(cut.syncSpaceId)
        val joinWinners = linkedMapOf<SyncReplicationLane, List<GenesisFieldVersionSnapshot>>()
        val shards =
            PHASE_A_SNAPSHOT_LANES.map { lane ->
                val frontierJson = SyncGenesisCodec.encodeFrontiers(
                    mapOf(lane.wireName to (cut.laneFrontiers[lane.wireName] ?: emptyMap())),
                )
                val entityStateJson =
                    when (lane) {
                        SyncReplicationLane.CORE_META ->
                            json.encodeToString(
                                GenesisCoreState(
                                    genesisBaselineId = cut.genesisBaselineId,
                                    mappings = mappingSnapshots.sortedWith(compareBy(GenesisMappingSnapshot::entityType, GenesisMappingSnapshot::syncId)),
                                ),
                            )
                        SyncReplicationLane.LIBRARY -> json.encodeToString(libraryState)
                        SyncReplicationLane.ARTICLE_STATE -> json.encodeToString(articleState)
                        SyncReplicationLane.CONFIG -> configStateJson
                        SyncReplicationLane.AUTH -> authEntityStateJson
                        SyncReplicationLane.AI_HISTORY -> projectionEntityStateJson
                    }
                val winnerFieldVersionJson =
                    if (lane == SyncReplicationLane.AI_HISTORY) {
                        projectionFieldVersionJson
                    } else if (lane == SyncReplicationLane.CONFIG) {
                        json.encodeToString(configFieldVersions)
                    } else if (lane == SyncReplicationLane.LIBRARY) {
                        json.encodeToString(
                            fieldVersions.filter { version ->
                                version.entityType in
                                    setOf(
                                        SyncEntityType.GROUP.wireName,
                                        SyncEntityType.FEED.wireName,
                                    )
                            }
                        )
                    } else if (lane == SyncReplicationLane.ARTICLE_STATE) {
                        json.encodeToString(
                            fieldVersions.filter { version ->
                                version.entityType == SyncEntityType.ARTICLE.wireName
                            }
                        )
                    } else {
                        "[]"
                    }
                val winnerVersions = json.decodeFromString<List<GenesisFieldVersionSnapshot>>(winnerFieldVersionJson)
                if (session.genesisSessionId.startsWith("join-")) joinWinners[lane] = winnerVersions
                val allVersions = winnerVersions.flatMap { winner ->
                    retainedFieldCandidates.filter { candidate ->
                        candidate.entityType == winner.entityType && candidate.entitySyncId == winner.entitySyncId &&
                            candidate.entityGeneration == winner.entityGeneration && candidate.fieldId == winner.fieldId
                    }.map { candidate -> GenesisFieldVersionSnapshot(
                        entitySyncId = candidate.entitySyncId, fieldId = candidate.fieldId,
                        valueJson = candidate.valueJson, versionToken = candidate.versionToken,
                        entityType = candidate.entityType, entityGeneration = candidate.entityGeneration,
                        causalContextJson = candidate.causalContextJson, logicalClock = candidate.logicalClock,
                    ) } + winner
                }.distinctBy { listOf(it.entityType, it.entitySyncId, it.entityGeneration, it.fieldId, it.versionToken) }
                val fieldVersionJson = json.encodeToString(allVersions.sortedWith(compareBy(
                    GenesisFieldVersionSnapshot::entityType, GenesisFieldVersionSnapshot::entitySyncId,
                    GenesisFieldVersionSnapshot::fieldId, GenesisFieldVersionSnapshot::versionToken)))
                val causalMetadata =
                    buildJsonObject {
                        put("schemaVersion", 1)
                        put("crossDbCutId", cut.crossDbCutId)
                        put("genesisBaselineId", cut.genesisBaselineId)
                        if (lane == SyncReplicationLane.CORE_META) {
                            put(
                                "aliasEdges",
                                json.parseToJsonElement(json.encodeToString(aliasEdges)),
                            )
                        }
                        put(
                            "observedGenesisBaselinesByLane",
                            json.parseToJsonElement(json.encodeToString(
                                PHASE_A_SNAPSHOT_LANES.associate { lane ->
                                    lane.wireName to listOf(cut.genesisBaselineId)
                                },
                            )),
                        )
                    }.toString()
                val genesisCoverageJson = SyncGenesisCodec.encodeStringList(listOf(cut.genesisBaselineId))
                val generationTypes = snapshotEntityTypesForLane(lane)
                val laneTombstones =
                    persistedTombstones
                        .filter { it.entityType in generationTypes }
                        .sortedWith(
                            compareBy(
                                SyncTombstoneEntity::entityType,
                                SyncTombstoneEntity::entitySyncId,
                            )
                        )
                val deletionSummaryJson =
                    SyncOperationCanonicalizer.canonicalJson(
                        buildJsonArray {
                            laneTombstones.forEach { tombstone ->
                                add(
                                    buildJsonObject {
                                        put("entityType", tombstone.entityType)
                                        put("entitySyncId", tombstone.entitySyncId)
                                        put("generation", tombstone.entityGeneration)
                                        put("versionToken", tombstone.versionToken)
                                        put("deletedAt", tombstone.updatedAt)
                                    }
                                )
                            }
                        }.toString()
                    )
                val generationSummary =
                    if (lane == SyncReplicationLane.AI_HISTORY) {
                        projectionEntities.associate {
                            it.entityType + ":" + it.entitySyncId to it.generation
                        }.toMutableMap()
                    } else {
                        mappingSnapshots
                            .filter { it.entityType in generationTypes }
                            .associate { it.entityType + ":" + it.syncId to it.generation }
                            .toMutableMap()
                    }
                laneTombstones.forEach { tombstone ->
                    val key = tombstone.entityType + ":" + tombstone.entitySyncId
                    generationSummary[key] =
                        maxOf(generationSummary[key] ?: 0L, tombstone.entityGeneration)
                }
                val sortedGenerationSummary: Map<String, Long> = generationSummary.toSortedMap()
                val generationSummaryJson =
                    SyncOperationCanonicalizer.canonicalJson(
                        json.encodeToString(sortedGenerationSummary),
                    )
                val blobIndexes = blobState.snapshotIndexes(cut.syncSpaceId, lane.wireName)
                val material =
                    GenesisShardHashMaterial(
                        lane.wireName,
                        frontierJson,
                        entityStateJson,
                        fieldVersionJson,
                        SyncOperationCanonicalizer.canonicalJson(causalMetadata),
                        genesisCoverageJson,
                        deletionSummaryJson,
                        generationSummaryJson,
                        blobIndexes.manifestIndexJson,
                        blobIndexes.referenceIndexJson,
                    )
                val shardHash = SyncGenesisCodec.hashCanonicalJson(json.encodeToString(material))
                SyncSnapshotShardEntity(
                    snapshotBundleId = snapshotBundleId,
                    syncSpaceId = cut.syncSpaceId,
                    replicationLaneId = lane.wireName,
                    frontierByActorJson = frontierJson,
                    receivedCoverageSummaryJson = frontierJson,
                    entityStateJson = entityStateJson,
                    fieldVersionStateJson = fieldVersionJson,
                    causalMergeMetadataJson = SyncOperationCanonicalizer.canonicalJson(causalMetadata),
                    genesisCoverageJson = genesisCoverageJson,
                    deletionSummaryJson = deletionSummaryJson,
                    generationSummaryJson = generationSummaryJson,
                    blobManifestIndexJson = blobIndexes.manifestIndexJson,
                    blobReferenceIndexJson = blobIndexes.referenceIndexJson,
                    shardHash = shardHash,
                )
            }
        val descriptors =
            shards.map { shard ->
                GenesisShardDescriptor(shard.replicationLaneId, shard.shardHash, shard.frontierByActorJson)
            }.sortedBy(GenesisShardDescriptor::replicationLaneId)
        val descriptorsJson = json.encodeToString(descriptors)
        val requiredCoreShardIdsJson = SyncGenesisCodec.encodeStringList(
            listOf(
                SyncReplicationLane.CORE_META.wireName,
                SyncReplicationLane.AUTH.wireName,
            ),
        )
        val rootHash =
            SyncGenesisCodec.hashCanonicalJson(
                json.encodeToString(
                    GenesisBundleHashMaterial(
                        schemaVersion = 1,
                        snapshotEpoch = 1,
                        crossDbCutId = cut.crossDbCutId,
                        replicationPolicyHash = policyHash,
                        requiredCoreShardIdsJson = requiredCoreShardIdsJson,
                        shardDescriptorsJson = descriptorsJson,
                    ),
                ),
            )
        val deviceId = checkNotNull(database.syncRuntimeDao().findDeviceIdentity()).deviceId
        val bundle =
            SyncSnapshotBundleEntity(
                snapshotBundleId = snapshotBundleId,
                syncSpaceId = cut.syncSpaceId,
                snapshotClass = SyncSnapshotClass.WORKING.name,
                schemaVersion = 1,
                snapshotEpoch = 1,
                crossDbCutId = cut.crossDbCutId,
                replicationPolicyHash = policyHash,
                requiredCoreShardIdsJson = requiredCoreShardIdsJson,
                shardDescriptorsJson = descriptorsJson,
                rootHash = rootHash,
                createdByDeviceId = deviceId,
                createdAt = now,
            )

        projectionExtensions.forEach { extension ->
            extension.markGenesisIncluded(
                syncSpaceId = cut.syncSpaceId,
                laneFrontiers = cut.laneFrontiers,
                now = now,
            )
        }
        // 入组基线由各自数据库先独立提交；最后的 Reader 发布不等待 Chat 写锁。
        if (session.genesisSessionId.startsWith("join-")) {
            val actor = checkNotNull(database.syncRuntimeDao().findActiveActor(cut.syncSpaceId))
            val context = SyncWritableActorContext(localAccountId = accountId, syncSpaceId = cut.syncSpaceId,
                deviceId = deviceId, actorIncarnationId = actor.actorIncarnationId,
                lifecycleState = SyncSpaceLifecycleState.GENESIS_CAPTURING)
            joinWinners.filterValues { it.isNotEmpty() }.forEach { (lane, winners) ->
                joinBaselineCapture.capture(SyncSpaceJoinBaselineCapture.JoinBaselineLane(context = context,
                    lane = lane, baselineId = session.genesisSessionId, winners = winners))
            }
        }
        database.withTransaction {
            database.syncGenesisDao().upsertBundle(bundle)
            shards.forEach { database.syncGenesisDao().upsertShard(it) }
            val pending = database.syncOutboxDao().listGenesisCandidates(cut.syncSpaceId)
            pending.filter { outbox ->
                val frontier = cut.laneFrontiers[outbox.replicationLaneId]?.get(outbox.actorIncarnationId) ?: 0L
                outbox.replicationLaneId in PHASE_A_SNAPSHOT_LANES.map(SyncReplicationLane::wireName) &&
                    outbox.sequence <= frontier
            }.forEach { outbox ->
                database.syncOutboxDao().markGenesisIncluded(outbox.outboxId, now)
                database.syncOperationDao().findByDot(
                    outbox.actorIncarnationId,
                    outbox.replicationLaneId,
                    outbox.sequence,
                )?.let { operation ->
                    database.syncGenesisDao().upsertOperationCoverage(
                        SyncGenesisOperationCoverageEntity(
                            operationId = operation.operationId,
                            genesisSessionId = cut.genesisSessionId,
                            includedAt = now,
                        ),
                    )
                }
            }
            database.syncGenesisDao().upsertSession(
                session.copy(
                    state = SyncGenesisStage.SNAPSHOT_BUILT.name,
                    crossDbCutId = cut.crossDbCutId,
                    cutFrontierJson = SyncGenesisCodec.encodeFrontiers(cut.laneFrontiers),
                    snapshotBundleId = snapshotBundleId,
                    updatedAt = now,
                ),
            )
        }
        return snapshotBundleId
    }

    private fun rssHubSettingsJson(settings: RssHubSettings) =
        buildJsonObject {
            put("enabled", settings.enabled)
            put(
                "instances",
                buildJsonArray {
                    settings.instances.forEach { instance ->
                        add(
                            buildJsonObject {
                                put("id", instance.id)
                                put("url", instance.url)
                                put("location", instance.location)
                                put("maintainer", instance.maintainer)
                                put("enabled", instance.enabled)
                                put("builtIn", instance.builtIn)
                            }
                        )
                    }
                },
            )
        }
}
