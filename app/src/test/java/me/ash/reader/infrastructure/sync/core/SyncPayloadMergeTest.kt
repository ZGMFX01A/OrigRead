package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPayloadMergeTest {
    private fun op(actor: String, clock: Long, title: String, context: String = "{}") = SyncOperationEntity(
        operationId = actor, syncSpaceId = "space", authorDeviceId = actor, actorIncarnationId = actor,
        replicationLaneId = "AI_HISTORY", sequence = 1, logicalClock = clock, causalContextJson = context,
        dependencyDotsJson = "[]", entityType = "conversation", entitySyncId = "chat", entityGeneration = 0,
        operationType = "UPSERT", payloadSchemaVersion = 1, payloadJson = "{\"title\":\"$title\"}", schemaVersion = 1,
        createdWallClock = 1, payloadHash = "", signingDigest = "", buildStatus = "SIGNED", createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `AI field merge keeps losing candidates needed by a future causal successor`() {
        val a = op("a", 9, "A")
        val b = op("b", 8, "B")
        val c = op("c", 1, "C", """{"lanes":[{"replicationLaneId":"AI_HISTORY","actors":[{"actorIncarnationId":"a","prefix":1}]}],"schemaVersion":1}""")
        listOf(listOf(a,b,c), listOf(a,c,b), listOf(b,a,c), listOf(b,c,a), listOf(c,a,b), listOf(c,b,a)).forEach {
            assertEquals("\"B\"", SyncPayloadMerge.resolve(it).getValue("title").valueJson)
        }
    }
}
