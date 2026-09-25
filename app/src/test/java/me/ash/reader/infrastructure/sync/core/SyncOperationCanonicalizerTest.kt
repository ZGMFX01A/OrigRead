package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncOperationCanonicalizerTest {
    @Test
    fun `binary64 numbers match Desktop canonical bytes`() {
        assertEquals(
            "[1,0,1e+30,4.5,0.002,1e-7,0.000001,333333333.3333333,5e-324]",
            SyncOperationCanonicalizer.canonicalJson("[1.0,-0,1E30,4.50,2e-3,1e-7,0.000001,333333333.33333329,5e-324]"),
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SyncOperationCanonicalizer.canonicalJson("\"\\ud800\"")
        }
    }
    @Test
    fun `canonical fixture matches desktop payload hash and signing digest`() {
        val payload = SyncOperationCanonicalizer.canonicalJson("""{"z":1,"a":{"y":2,"x":"汉"}}""")
        val causal =
            SyncOperationCanonicalizer.canonicalJson(
                """{"lanes":[{"actors":[{"prefix":3,"actorIncarnationId":"actor-A"}],"replicationLaneId":"ARTICLE_STATE"}],"schemaVersion":1}"""
            )

        assertEquals("""{"a":{"x":"汉","y":2},"z":1}""", payload)
        assertEquals(
            "d73c001609cc6a22a49c7bddb0eb12101330ead01b152535ed3c7e17e2d5f2e1",
            SyncOperationCanonicalizer.sha256Hex(payload),
        )
        assertEquals(
            "op1:c72e27cd6a19a23c0f3e853d363336fe5e7c8caa654f746fd0407c39a847000b",
            SyncOperationCanonicalizer.operationId("space-1", "actor-A", "ARTICLE_STATE", 4),
        )

        val operation =
            SyncOperationEntity(
                operationId = "actor-A:ARTICLE_STATE:4",
                syncSpaceId = "space-1",
                authorDeviceId = "device-1",
                actorIncarnationId = "actor-A",
                replicationLaneId = "ARTICLE_STATE",
                sequence = 4,
                logicalClock = 4,
                causalContextJson = causal,
                dependencyDotsJson = "[]",
                entityType = "article",
                entitySyncId = "article-sync-1",
                entityGeneration = 0,
                operationType = "FIELD_SET",
                payloadSchemaVersion = 1,
                payloadJson = payload,
                schemaVersion = 1,
                createdWallClock = 1_700_000_000_000,
                payloadHash = SyncOperationCanonicalizer.sha256Hex(payload),
                signingDigest = "",
                buildStatus = SyncOperationBuildStatus.AWAITING_SIGNATURE.name,
                createdAt = 1,
                updatedAt = 1,
            )

        assertEquals(
            "91c4ea9088e143a7abfded10022703f19780fff3a8422ef62836be2308be35ae",
            SyncOperationCanonicalizer.signingDigest(operation),
        )
    }
}
