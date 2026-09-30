package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Canonical R10 Snapshot wire encoding.
 *
 * hashSchemaVersion=2 binds the complete shard merge metadata and the bundle
 * manifest. The detached author signature binds the entire canonical wire
 * object except authorSignature itself.
 */
object SyncSnapshotWireCodec {
    const val HASH_SCHEMA_VERSION = 2
    private const val SIGNING_DOMAIN = "ORIGREAD_SNAPSHOT_V1"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun toWire(
        bundle: SyncSnapshotBundleEntity,
        shards: List<SyncSnapshotShardEntity>,
        coverage: SyncCoverage,
        keyStore: SyncDeviceSigningKeyStore,
        selectedLanes: Set<String>? = null,
    ): SyncSnapshotBundleWire {
        require(shards.all { it.snapshotBundleId == bundle.snapshotBundleId && it.syncSpaceId == bundle.syncSpaceId })
        val manifestLanes = shards.map { it.replicationLaneId }.toSet()
        val effectiveLanes = selectedLanes ?: manifestLanes
        require(manifestLanes.containsAll(effectiveLanes)) {
            "Snapshot scope contains a lane that is absent from the persisted manifest"
        }
        val requiredCoreShardIds =
            json.decodeFromString<List<String>>(bundle.requiredCoreShardIdsJson)
        require(effectiveLanes.containsAll(requiredCoreShardIds)) {
            "Snapshot scope must include required core shards"
        }
        val wireShards =
            shards.filter { it.replicationLaneId in effectiveLanes }.map(::toWireShard)
        val isScoped = effectiveLanes != manifestLanes
        val policyHash =
            if (isScoped) {
                scopedPolicyHash(bundle.replicationPolicyHash, effectiveLanes)
            } else {
                bundle.replicationPolicyHash
            }
        val snapshotBundleId =
            if (isScoped) scopedSnapshotBundleId(bundle.snapshotBundleId, policyHash)
            else bundle.snapshotBundleId
        val scopedCoverage =
            normalizeCoverage(coverage).filterKeys { it in effectiveLanes }
        require(scopedCoverage == coverageFromShards(wireShards)) {
            "Snapshot scoped coverage does not match selected shard frontiers"
        }
        val unsigned = SyncSnapshotBundleWire(
            snapshotBundleId = snapshotBundleId,
            syncSpaceId = bundle.syncSpaceId,
            snapshotClass = bundle.snapshotClass,
            genesisBaselineId = genesisBaselineId(bundle),
            rootHash = "",
            policyHash = policyHash,
            capturedAt = bundle.createdAt,
            shards = wireShards,
            coverage = scopedCoverage,
            hashSchemaVersion = HASH_SCHEMA_VERSION,
            schemaVersion = bundle.schemaVersion,
            snapshotEpoch = bundle.snapshotEpoch,
            crossDbCutId = bundle.crossDbCutId,
            requiredCoreShardIds = requiredCoreShardIds,
            coverageCommitment =
                if (bundle.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name) {
                    coverageCommitment(scopedCoverage)
                } else {
                    null
                },
            authStabilityCheckpoint = bundle.authStabilityCheckpointId,
            authorDeviceId = bundle.createdByDeviceId,
            authorSignature = null,
        )
        val withRoot = unsigned.copy(rootHash = richRootHash(unsigned))
        if (!isScoped) {
            require(withRoot.rootHash == bundle.rootHash) {
                "Persisted Snapshot rootHash does not match exported full manifest"
            }
        }
        return withRoot.copy(
            authorSignature = keyStore.signBase64(
                bundle.createdByDeviceId,
                signingMaterial(withRoot).toByteArray(Charsets.UTF_8),
            ),
        )
    }

    fun scopedPolicyHash(basePolicyHash: String, lanes: Set<String>): String =
        SyncGenesisCodec.hashCanonicalJson(
            json.encodeToString(
                SnapshotPolicyScopeMaterial(
                    basePolicyHash = basePolicyHash,
                    lanes = lanes.toSortedSet().toList(),
                )
            )
        )

    fun scopedSnapshotBundleId(baseSnapshotBundleId: String, scopedPolicyHash: String): String =
        baseSnapshotBundleId + ":scope:" + scopedPolicyHash.take(16)

    fun verifyRichWire(
        wire: SyncSnapshotBundleWire,
        publicKeySpkiBase64: String,
        keyStore: SyncDeviceSigningKeyStore,
    ) {
        require(wire.hashSchemaVersion == HASH_SCHEMA_VERSION) {
            "REBASE_UNSAFE: unsupported snapshot hash schema " + wire.hashSchemaVersion
        }
        require(!wire.authorDeviceId.isNullOrBlank() && !wire.authorSignature.isNullOrBlank()) {
            "REBASE_UNSAFE: snapshot author signature is missing"
        }
        require(!wire.crossDbCutId.isNullOrBlank()) {
            "REBASE_UNSAFE: snapshot crossDbCutId is missing"
        }
        require(wire.requiredCoreShardIds.isNotEmpty()) {
            "REBASE_UNSAFE: snapshot required core shard manifest is missing"
        }
        val signatureValid = keyStore.verifyBase64(
            publicKeySpkiBase64,
            signingMaterial(wire).toByteArray(Charsets.UTF_8),
            checkNotNull(wire.authorSignature),
        )
        if (!signatureValid) throw SnapshotCorruptedError("Snapshot author signature verification failed")

        for (shard in wire.shards) {
            val expected = richShardHash(shard)
            if (expected != shard.contentHash) {
                throw SnapshotCorruptedError(
                    "Shard contentHash mismatch for lane " + shard.replicationLaneId +
                        ": expected " + expected + ", got " + shard.contentHash,
                )
            }
        }
        val expectedRoot = richRootHash(wire)
        if (expectedRoot != wire.rootHash) {
            throw SnapshotCorruptedError(
                "Snapshot rootHash mismatch: expected " + expectedRoot + ", got " + wire.rootHash,
            )
        }
        val frontierCoverage = coverageFromShards(wire.shards)
        if (normalizeCoverage(wire.coverage) != frontierCoverage) {
            throw SnapshotCorruptedError("Snapshot coverage does not match shard frontiers")
        }
    }

    fun toInternalBundle(wire: SyncSnapshotBundleWire): SyncSnapshotBundleEntity {
        val descriptors =
            wire.shards.map {
                GenesisShardDescriptor(it.replicationLaneId, it.contentHash, it.frontierJson)
            }.sortedBy(GenesisShardDescriptor::replicationLaneId)
        return SyncSnapshotBundleEntity(
            snapshotBundleId = wire.snapshotBundleId,
            syncSpaceId = wire.syncSpaceId,
            snapshotClass = wire.snapshotClass,
            schemaVersion = wire.schemaVersion,
            snapshotEpoch = wire.snapshotEpoch,
            crossDbCutId = checkNotNull(wire.crossDbCutId),
            replicationPolicyHash = wire.policyHash,
            requiredCoreShardIdsJson = SyncGenesisCodec.encodeStringList(wire.requiredCoreShardIds),
            shardDescriptorsJson = json.encodeToString(descriptors),
            authStabilityCheckpointId = wire.authStabilityCheckpoint,
            rootHash = wire.rootHash,
            createdByDeviceId = checkNotNull(wire.authorDeviceId),
            createdAt = wire.capturedAt,
        )
    }

    fun toInternalBundle(manifest: SyncSnapshotStreamManifestWire): SyncSnapshotBundleEntity {
        val descriptors =
            manifest.shardDescriptors.map {
                GenesisShardDescriptor(it.replicationLaneId, it.contentHash, it.frontierJson)
            }.sortedBy(GenesisShardDescriptor::replicationLaneId)
        return SyncSnapshotBundleEntity(
            snapshotBundleId = manifest.snapshotBundleId,
            syncSpaceId = manifest.syncSpaceId,
            snapshotClass = manifest.snapshotClass,
            schemaVersion = manifest.schemaVersion,
            snapshotEpoch = manifest.snapshotEpoch,
            crossDbCutId = checkNotNull(manifest.crossDbCutId),
            replicationPolicyHash = manifest.policyHash,
            requiredCoreShardIdsJson = SyncGenesisCodec.encodeStringList(manifest.requiredCoreShardIds),
            shardDescriptorsJson = json.encodeToString(descriptors),
            authStabilityCheckpointId = manifest.authStabilityCheckpoint,
            rootHash = manifest.rootHash,
            createdByDeviceId = checkNotNull(manifest.authorDeviceId),
            createdAt = manifest.capturedAt,
        )
    }

    fun toWireShard(shard: SyncSnapshotShardEntity): SyncSnapshotShardWire =
        SyncSnapshotShardWire(
            replicationLaneId = shard.replicationLaneId,
            frontierJson = shard.frontierByActorJson,
            entityStateJson = shard.entityStateJson,
            fieldVersionStateJson = shard.fieldVersionStateJson,
            causalMetadataJson = shard.causalMergeMetadataJson,
            genesisCoverageJson = shard.genesisCoverageJson,
            deletionGenerationSummaryJson =
                canonicalDeletionGenerationSummary(
                    shard.deletionSummaryJson,
                    shard.generationSummaryJson,
                ),
            contentHash = shard.shardHash,
            deletionSummaryJson = shard.deletionSummaryJson,
            generationSummaryJson = shard.generationSummaryJson,
            blobManifestIndexJson = shard.blobManifestIndexJson,
            blobReferenceIndexJson = shard.blobReferenceIndexJson,
        )

    fun genesisBaselineId(bundle: SyncSnapshotBundleEntity): String? =
        bundle.snapshotBundleId
            .substringBefore(":scope:")
            .removePrefix("genesis:v1:")
            .substringBefore(":merge:")
            .takeIf { it.isNotBlank() }

    fun toInternalShard(
        snapshotBundleId: String,
        syncSpaceId: String,
        shard: SyncSnapshotShardWire,
    ): SyncSnapshotShardEntity =
        SyncSnapshotShardEntity(
                snapshotBundleId = snapshotBundleId,
                syncSpaceId = syncSpaceId,
                replicationLaneId = shard.replicationLaneId,
                frontierByActorJson = shard.frontierJson,
                receivedCoverageSummaryJson = shard.frontierJson,
                entityStateJson = shard.entityStateJson,
                fieldVersionStateJson = shard.fieldVersionStateJson,
                causalMergeMetadataJson = shard.causalMetadataJson,
                genesisCoverageJson = shard.genesisCoverageJson,
                deletionSummaryJson = requireNotNull(shard.deletionSummaryJson) {
                    "REBASE_UNSAFE: deletionSummaryJson is missing"
                },
                generationSummaryJson = requireNotNull(shard.generationSummaryJson) {
                    "REBASE_UNSAFE: generationSummaryJson is missing"
                },
                blobManifestIndexJson = requireNotNull(shard.blobManifestIndexJson) {
                    "REBASE_UNSAFE: blobManifestIndexJson is missing"
                },
                blobReferenceIndexJson = requireNotNull(shard.blobReferenceIndexJson) {
                    "REBASE_UNSAFE: blobReferenceIndexJson is missing"
                },
                shardHash = shard.contentHash,
            )

    fun toInternalShards(wire: SyncSnapshotBundleWire): List<SyncSnapshotShardEntity> =
        wire.shards.map { shard ->
            toInternalShard(
                snapshotBundleId = wire.snapshotBundleId,
                syncSpaceId = wire.syncSpaceId,
                shard = shard,
            )
        }

    fun signingMaterial(wire: SyncSnapshotBundleWire): String {
        val element = json.encodeToJsonElement(SyncSnapshotBundleWire.serializer(), wire).jsonObject
        val withoutSignature = JsonObject(element.filterKeys { it != "authorSignature" })
        return SIGNING_DOMAIN + "\n" + SyncOperationCanonicalizer.canonicalJson(withoutSignature.toString())
    }

    fun toStreamManifest(wire: SyncSnapshotBundleWire): SyncSnapshotStreamManifestWire =
        SyncSnapshotStreamManifestWire(
            sourceSnapshotBundleId = wire.snapshotBundleId,
            snapshotBundleId = wire.snapshotBundleId,
            syncSpaceId = wire.syncSpaceId,
            snapshotClass = wire.snapshotClass,
            genesisBaselineId = wire.genesisBaselineId,
            rootHash = wire.rootHash,
            policyHash = wire.policyHash,
            capturedAt = wire.capturedAt,
            shardDescriptors =
                wire.shards.map {
                    SyncSnapshotShardDescriptorWire(
                        replicationLaneId = it.replicationLaneId,
                        contentHash = it.contentHash,
                        frontierJson = it.frontierJson,
                    )
                },
            coverage = wire.coverage,
            hashSchemaVersion = wire.hashSchemaVersion,
            schemaVersion = wire.schemaVersion,
            snapshotEpoch = wire.snapshotEpoch,
            crossDbCutId = wire.crossDbCutId,
            requiredCoreShardIds = wire.requiredCoreShardIds,
            coverageCommitment = wire.coverageCommitment,
            authStabilityCheckpoint = wire.authStabilityCheckpoint,
            authorDeviceId = wire.authorDeviceId,
            authorSignature = wire.authorSignature,
        )

    fun fromStreamManifest(
        manifest: SyncSnapshotStreamManifestWire,
        shards: List<SyncSnapshotShardWire>,
    ): SyncSnapshotBundleWire =
        SyncSnapshotBundleWire(
            snapshotBundleId = manifest.snapshotBundleId,
            syncSpaceId = manifest.syncSpaceId,
            snapshotClass = manifest.snapshotClass,
            genesisBaselineId = manifest.genesisBaselineId,
            rootHash = manifest.rootHash,
            policyHash = manifest.policyHash,
            capturedAt = manifest.capturedAt,
            shards = shards,
            coverage = manifest.coverage,
            hashSchemaVersion = manifest.hashSchemaVersion,
            schemaVersion = manifest.schemaVersion,
            snapshotEpoch = manifest.snapshotEpoch,
            crossDbCutId = manifest.crossDbCutId,
            requiredCoreShardIds = manifest.requiredCoreShardIds,
            coverageCommitment = manifest.coverageCommitment,
            authStabilityCheckpoint = manifest.authStabilityCheckpoint,
            authorDeviceId = manifest.authorDeviceId,
            authorSignature = manifest.authorSignature,
        )

    /**
     * Produces exactly the same bytes as [signingMaterial] without ever constructing the complete
     * Snapshot JSON. The caller may load one shard at a time from SQLite/staging storage.
     */
    fun signingMaterialChunks(
        manifest: SyncSnapshotStreamManifestWire,
        shardLoader: (String) -> SyncSnapshotShardWire,
    ): Sequence<ByteArray> = sequence {
        yield((SIGNING_DOMAIN + "\n{").toByteArray(Charsets.UTF_8))
        val skeleton =
            fromStreamManifest(manifest.copy(authorSignature = null), emptyList())
        val element =
            json.encodeToJsonElement(SyncSnapshotBundleWire.serializer(), skeleton).jsonObject
                .filterKeys { it != "authorSignature" }
        val sortedKeys = element.keys.sorted()
        sortedKeys.forEachIndexed { index, key ->
            if (index > 0) yield(",".toByteArray(Charsets.UTF_8))
            yield(json.encodeToString(key).toByteArray(Charsets.UTF_8))
            yield(":".toByteArray(Charsets.UTF_8))
            if (key == "shards") {
                yield("[".toByteArray(Charsets.UTF_8))
                manifest.shardDescriptors.forEachIndexed { shardIndex, descriptor ->
                    if (shardIndex > 0) yield(",".toByteArray(Charsets.UTF_8))
                    val shard = shardLoader(descriptor.replicationLaneId)
                    require(
                        shard.replicationLaneId == descriptor.replicationLaneId &&
                            shard.contentHash == descriptor.contentHash &&
                            shard.frontierJson == descriptor.frontierJson
                    ) {
                        "SNAPSHOT_CORRUPTED: streamed shard does not match its signed descriptor"
                    }
                    yield(
                        SyncOperationCanonicalizer.canonicalJson(
                            json.encodeToString(SyncSnapshotShardWire.serializer(), shard)
                        ).toByteArray(Charsets.UTF_8)
                    )
                }
                yield("]".toByteArray(Charsets.UTF_8))
            } else {
                yield(
                    SyncOperationCanonicalizer.canonicalJson(
                        checkNotNull(element[key]).toString()
                    ).toByteArray(Charsets.UTF_8)
                )
            }
        }
        yield("}".toByteArray(Charsets.UTF_8))
    }

    fun verifyRichStream(
        manifest: SyncSnapshotStreamManifestWire,
        publicKeySpkiBase64: String,
        keyStore: SyncDeviceSigningKeyStore,
        shardLoader: (String) -> SyncSnapshotShardWire,
    ) {
        require(manifest.hashSchemaVersion == HASH_SCHEMA_VERSION) {
            "REBASE_UNSAFE: unsupported snapshot hash schema " + manifest.hashSchemaVersion
        }
        require(!manifest.authorDeviceId.isNullOrBlank() && !manifest.authorSignature.isNullOrBlank()) {
            "REBASE_UNSAFE: snapshot author signature is missing"
        }
        require(!manifest.crossDbCutId.isNullOrBlank()) {
            "REBASE_UNSAFE: snapshot crossDbCutId is missing"
        }
        require(manifest.requiredCoreShardIds.isNotEmpty()) {
            "REBASE_UNSAFE: snapshot required core shard manifest is missing"
        }
        require(
            manifest.shardDescriptors.map { it.replicationLaneId }.distinct().size ==
                manifest.shardDescriptors.size
        ) { "REBASE_UNSAFE: duplicate streamed snapshot lane" }

        val lightweightShards =
            manifest.shardDescriptors.map { descriptor ->
                val shard = shardLoader(descriptor.replicationLaneId)
                require(
                    shard.replicationLaneId == descriptor.replicationLaneId &&
                        shard.contentHash == descriptor.contentHash &&
                        shard.frontierJson == descriptor.frontierJson
                ) {
                    "SNAPSHOT_CORRUPTED: streamed shard does not match its descriptor"
                }
                val expected = richShardHash(shard)
                if (expected != shard.contentHash) {
                    throw SnapshotCorruptedError(
                        "Shard contentHash mismatch for lane " + shard.replicationLaneId
                    )
                }
                SyncSnapshotShardWire(
                    replicationLaneId = descriptor.replicationLaneId,
                    frontierJson = descriptor.frontierJson,
                    entityStateJson = "",
                    fieldVersionStateJson = "",
                    causalMetadataJson = "",
                    genesisCoverageJson = "",
                    deletionGenerationSummaryJson = "",
                    contentHash = descriptor.contentHash,
                )
            }
        val lightweight = fromStreamManifest(manifest, lightweightShards)
        if (richRootHash(lightweight) != manifest.rootHash) {
            throw SnapshotCorruptedError("Snapshot rootHash mismatch")
        }
        if (normalizeCoverage(manifest.coverage) != coverageFromShards(lightweightShards)) {
            throw SnapshotCorruptedError("Snapshot coverage does not match shard frontiers")
        }
        if (
            !keyStore.verifyChunksBase64(
                publicKeySpkiBase64,
                signingMaterialChunks(manifest, shardLoader),
                checkNotNull(manifest.authorSignature),
            )
        ) {
            throw SnapshotCorruptedError("Snapshot author signature verification failed")
        }
    }

    fun richShardHash(shard: SyncSnapshotShardWire): String {
        val material = GenesisShardHashMaterial(
            replicationLaneId = shard.replicationLaneId,
            frontierByActorJson = shard.frontierJson,
            entityStateJson = shard.entityStateJson,
            fieldVersionStateJson = shard.fieldVersionStateJson,
            causalMergeMetadataJson = shard.causalMetadataJson,
            genesisCoverageJson = shard.genesisCoverageJson,
            deletionSummaryJson = requireNotNull(shard.deletionSummaryJson),
            generationSummaryJson = requireNotNull(shard.generationSummaryJson),
            blobManifestIndexJson = requireNotNull(shard.blobManifestIndexJson),
            blobReferenceIndexJson = requireNotNull(shard.blobReferenceIndexJson),
        )
        return SyncGenesisCodec.hashCanonicalJson(json.encodeToString(material))
    }

    fun richRootHash(wire: SyncSnapshotBundleWire): String {
        val descriptors = wire.shards.map {
            GenesisShardDescriptor(it.replicationLaneId, it.contentHash, it.frontierJson)
        }.sortedBy(GenesisShardDescriptor::replicationLaneId)
        val material = GenesisBundleHashMaterial(
            schemaVersion = wire.schemaVersion,
            snapshotEpoch = wire.snapshotEpoch,
            crossDbCutId = checkNotNull(wire.crossDbCutId),
            replicationPolicyHash = wire.policyHash,
            requiredCoreShardIdsJson = SyncGenesisCodec.encodeStringList(wire.requiredCoreShardIds),
            shardDescriptorsJson = json.encodeToString(descriptors),
        )
        return SyncGenesisCodec.hashCanonicalJson(json.encodeToString(material))
    }

    fun coverageFromShards(shards: List<SyncSnapshotShardWire>): SyncCoverage {
        val result = linkedMapOf<String, Map<String, Long>>()
        for (shard in shards) {
            val decoded = SyncGenesisCodec.decodeFrontiers(shard.frontierJson)
            val laneCoverage = (decoded[shard.replicationLaneId] ?: emptyMap()).filterValues { it > 0L }
            if (laneCoverage.isNotEmpty()) result[shard.replicationLaneId] = laneCoverage.toSortedMap()
        }
        return result.toSortedMap()
    }

    fun coverageCommitment(coverage: SyncCoverage): String =
        SyncGenesisCodec.hashCanonicalJson(
            json.encodeToString(normalizeCoverage(coverage))
        )

    private fun normalizeCoverage(value: SyncCoverage): SyncCoverage =
        value.toSortedMap()
            .mapValues { (_, actors) -> actors.filterValues { it > 0L }.toSortedMap() }
            .filterValues { it.isNotEmpty() }

    private fun canonicalDeletionGenerationSummary(deletion: String, generation: String): String =
        SyncOperationCanonicalizer.canonicalJson(
            buildString {
                append("""{"schemaVersion":1,"deleted":""")
                append(deletion)
                append(""","generations":""")
                append(generation)
                append('}')
            },
        )
}

@Serializable
private data class SnapshotPolicyScopeMaterial(
    val basePolicyHash: String,
    val lanes: List<String>,
)
