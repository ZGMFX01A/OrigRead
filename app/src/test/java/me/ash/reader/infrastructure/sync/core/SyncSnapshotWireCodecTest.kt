package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSnapshotWireCodecTest {
    @Test
    fun crossPlatformSnapshotV2FixtureIsStable() {
        val shard =
            SyncSnapshotShardWire(
                replicationLaneId = "ARTICLE_STATE",
                frontierJson = """[{"actorFrontiers":{"actor-A":4},"replicationLaneId":"ARTICLE_STATE"}]""",
                entityStateJson = """{"articles":[],"schemaVersion":1}""",
                fieldVersionStateJson = "[]",
                causalMetadataJson = """{"crossDbCutId":"cut-1","schemaVersion":1}""",
                genesisCoverageJson = """["base-1"]""",
                deletionGenerationSummaryJson = """{"deleted":[],"generations":{},"schemaVersion":1}""",
                contentHash = "",
                deletionSummaryJson = "[]",
                generationSummaryJson = "{}",
                blobManifestIndexJson = "[]",
                blobReferenceIndexJson = "[]",
            )
        val contentHash = SyncSnapshotWireCodec.richShardHash(shard)
        assertEquals(
            "fa2a24f74e976676ba8374cc6b5bb8ece969a110e0df77c43744532df14af7fa",
            contentHash,
        )
        val bundle =
            SyncSnapshotBundleWire(
                snapshotBundleId = "snap-fixture",
                syncSpaceId = "space-1",
                snapshotClass = SyncSnapshotClass.WORKING.name,
                genesisBaselineId = "base-1",
                rootHash = "",
                policyHash = "policy-1",
                capturedAt = 123456789L,
                shards = listOf(shard.copy(contentHash = contentHash)),
                coverage = mapOf("ARTICLE_STATE" to mapOf("actor-A" to 4L)),
                hashSchemaVersion = 2,
                schemaVersion = 1,
                snapshotEpoch = 1L,
                crossDbCutId = "cut-1",
                requiredCoreShardIds = listOf("AUTH", "CORE_META"),
                coverageCommitment = null,
                authStabilityCheckpoint = null,
                authorDeviceId = "device-A",
                authorSignature = null,
            )
        val rootHash = SyncSnapshotWireCodec.richRootHash(bundle)
        assertEquals(
            "3b1cd419111a9f56233a6cef3677a21a654d9e94106f5d4d40ebc69799a43694",
            rootHash,
        )
        assertEquals(
            "423d1f696383f65a01129b61b2d21d52a0767d5fe404ef9560da36876590b4f6",
            SyncOperationCanonicalizer.sha256Hex(
                SyncSnapshotWireCodec.signingMaterial(bundle.copy(rootHash = rootHash)),
            ),
        )
    }
}
