package me.ash.reader.infrastructure.sync.core

import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

class ReaderOperationBuilderTest {
    private val builder = ReaderOperationBuilder(mock<AndroidDatabase>())

    @Test
    fun `buildOperation freezes Dot causal payload and pre-signing state`() {
        val outbox =
            SyncOutboxEntity(
                outboxId = "actor-1:ARTICLE_STATE:7",
                syncSpaceId = "space-1",
                actorIncarnationId = "actor-1",
                replicationLaneId = SyncReplicationLane.ARTICLE_STATE.wireName,
                sequence = 7,
                entityType = "article",
                entitySyncId = "article-sync-1",
                entityGeneration = 0,
                mutationType = SyncMutationType.FIELD_SET.name,
                payloadSchemaVersion = 1,
                payloadJson = """{"value":false,"field":"isUnread"}""",
                causalContextJson = """{"schemaVersion":1,"lanes":[]}""",
                status = SyncOutboxStatus.PENDING_BUILD.name,
                createdAt = 1234,
                updatedAt = 1234,
            )

        val operation = builder.buildOperation(outbox, authorDeviceId = "device-1", now = 2000)

        assertEquals(
            SyncOperationCanonicalizer.operationId(
                outbox.syncSpaceId,
                outbox.actorIncarnationId,
                outbox.replicationLaneId,
                outbox.sequence,
            ),
            operation.operationId,
        )
        require(operation.operationId != outbox.outboxId) {
            "wire operationId must bind syncSpaceId instead of reusing the device-local outboxId"
        }
        assertEquals("device-1", operation.authorDeviceId)
        assertEquals(7, operation.sequence)
        assertEquals(7, operation.logicalClock)
        assertEquals("""{"field":"isUnread","value":false}""", operation.payloadJson)
        assertEquals(SyncOperationCanonicalizer.sha256Hex(operation.payloadJson), operation.payloadHash)
        assertEquals(SyncOperationCanonicalizer.signingDigest(operation.copy(signingDigest = "")), operation.signingDigest)
        assertEquals(SyncOperationBuildStatus.AWAITING_SIGNATURE.name, operation.buildStatus)
        assertNull(operation.authorSignature)
        assertNull(operation.authGrantId)
        assertNull(operation.authEpoch)
    }

    @Test
    fun `buildOperation injects active grant and rejects unauthorized device under strict mode`() {
        val outbox =
            SyncOutboxEntity(
                outboxId = "actor-1:ARTICLE_STATE:1",
                syncSpaceId = "space-1",
                actorIncarnationId = "actor-1",
                replicationLaneId = SyncReplicationLane.ARTICLE_STATE.wireName,
                sequence = 1,
                entityType = "article",
                entitySyncId = "article-1",
                entityGeneration = 0,
                mutationType = SyncMutationType.FIELD_SET.name,
                payloadSchemaVersion = 1,
                payloadJson = """{"value":true,"field":"isStarred"}""",
                causalContextJson = """{"schemaVersion":1,"lanes":[]}""",
                status = SyncOutboxStatus.PENDING_BUILD.name,
                createdAt = 1000,
                updatedAt = 1000,
            )

        // 1. 未授权且 strictAuth 时抛出异常
        var thrown = false
        try {
            builder.buildOperation(outbox, authorDeviceId = "device-1", now = 2000, activeGrant = null, strictAuth = true)
        } catch (e: SyncAuthNotGrantedException) {
            thrown = true
        }
        assertEquals(true, thrown)

        // 2. 注入活跃 grant 时，封包真实的 authGrantId 和 authEpoch
        val grant = ActiveAuthGrant(authGrantId = "auth1:root-1", authEpoch = 0L, isOwner = true)
        val operation = builder.buildOperation(outbox, authorDeviceId = "device-1", now = 2000, activeGrant = grant, strictAuth = true)
        assertEquals("auth1:root-1", operation.authGrantId)
        assertEquals(0L, operation.authEpoch)
    }
}
