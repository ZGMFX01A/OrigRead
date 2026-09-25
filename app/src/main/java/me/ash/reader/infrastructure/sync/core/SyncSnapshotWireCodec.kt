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
        val wireShards = shards.filter { it.replicationLaneId in effectiveLanes }.map { shard ->
            SyncSnapshotShardWire(
                replicationLaneId = shard.replicationLaneId,
                frontierJson = shard.frontierByActorJson,
                entityStateJson = shard.entityStateJson,
                fieldVersionStateJson = shard.fieldVersionStateJson,
                causalMetadataJson = shard.causalMergeMetadataJson,
                genesisCoverageJson = shard.genesisCoverageJson,
                deletionGenerationSummaryJson = canonicalDeletionGenerationSummary(
                    shard.deletionSummaryJson,
                    shard.generationSummaryJson,
                ),
                contentHash = shard.shardHash,
                deletionSummaryJson = shard.deletionSummaryJson,
                generationSummaryJson = shard.generationSummaryJson,
                blobManifestIndexJson = shard.blobManifestIndexJson,
                blobReferenceIndexJson = shard.blobReferenceIndexJson,
            )
        }
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
            genesisBaselineId =
                bundle.snapshotBundleId
                    .substringBefore(":scope:")
                    .removePrefix("genesis:v1:")
                    .substringBefore(":merge:")
                    .takeIf { it.isNotBlank() },
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

    fun toInternalShards(wire: SyncSnapshotBundleWire): List<SyncSnapshotShardEntity> =
        wire.shards.map { shard ->
            SyncSnapshotShardEntity(
                snapshotBundleId = wire.snapshotBundleId,
                syncSpaceId = wire.syncSpaceId,
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
        }

    fun signingMaterial(wire: SyncSnapshotBundleWire): String {
        val element = json.encodeToJsonElement(SyncSnapshotBundleWire.serializer(), wire).jsonObject
        val withoutSignature = JsonObject(element.filterKeys { it != "authorSignature" })
        return SIGNING_DOMAIN + "\n" + SyncOperationCanonicalizer.canonicalJson(withoutSignature.toString())
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
