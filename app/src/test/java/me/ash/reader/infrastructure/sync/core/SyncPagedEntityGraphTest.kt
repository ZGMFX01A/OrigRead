package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncPagedEntityGraphTest {
    /** 真实大正文只用于证明图校验不会走全文读取接口。 */
    private val largeBodySize = 2_000_000
    /** 同一父上下文的证据数量沿用原始回归场景。 */
    private val evidenceCount = 3400

    /** 构造保留原业务身份与代次的实体输入。 */
    private fun entity(type: String, id: String, fields: String): SyncSnapshotRecord =
        SyncSnapshotRecord("ENTITY", "$type:$id", Json.parseToJsonElement(
            """{"entityType":"$type","entitySyncId":"$id","generation":0,"fields":$fields}""").jsonObject)

    private class Fixture {
        val store: SyncPagedSnapshotStore = mock()
        private val derived: SyncSnapshotDerivedIndex = mock()
        var roots = emptyList<SyncSnapshotRecord>()
        val parents = mutableMapOf<Pair<String, String>, SyncSnapshotRecord>()
        val deleted = mutableSetOf<Pair<String, String>>()
        val parentReads = mutableMapOf<Pair<String, String>, Int>()
        var bodyReads = 0
        init {
            whenever(store.derived).thenReturn(derived)
            whenever(derived.entities(any())).thenAnswer { invocation ->
                val filter = invocation.getArgument<SyncPagedSnapshotStore.RecordFilter>(0)
                if (filter.entitySyncId == null) roots.asSequence().map { metadata(it, filter.bundleId) }
                else {
                    val key = checkNotNull(filter.entityType) to filter.entitySyncId!!
                    parentReads[key] = (parentReads[key] ?: 0) + 1
                    listOfNotNull(parents[key]).asSequence().map { metadata(it, filter.bundleId) }
                }
            }
            whenever(store.records(any())).thenAnswer { invocation ->
                val filter = invocation.getArgument<SyncPagedSnapshotStore.RecordFilter>(0)
                val key = filter.entityType to filter.entitySyncId
                when (filter.kind) {
                    "TOMBSTONE" -> if (key in deleted) sequenceOf(SyncSnapshotRecord("TOMBSTONE", "deleted", JsonObject(emptyMap()))) else emptySequence()
                    else -> { bodyReads += 1; emptySequence<SyncSnapshotRecord>() }
                }
            }
        }

        /** 测试替身仅提供身份、共享上下文与父引用，正文不能进入轻索引。 */
        private fun metadata(record: SyncSnapshotRecord, bundle: String): SyncSnapshotEntityMetadata {
            val value = record.value
            val type = value.getValue("entityType").jsonPrimitive.content
            val fields = value.getValue("fields").jsonObject
            val context = JsonObject(fields.filterKeys { it in setOf("conversationSyncId", "assistantMessageSyncId", "contextRefSyncId") })
            return SyncSnapshotEntityMetadata(bundle, "AI_HISTORY", record.key, type,
                value.getValue("entitySyncId").jsonPrimitive.content, value.getValue("generation").jsonPrimitive.long,
                context, SyncPagedEntityDependencies.parents(type, fields))
        }
    }

    @Test fun thousandsOfEvidenceChildrenNeverReadSharedLargeParentBody() {
        val f = Fixture()
        f.parents["context_ref" to "context"] = entity("context_ref", "context",
            """{"conversationSyncId":"conversation","assistantMessageSyncId":"message","contentSnapshot":"${"x".repeat(largeBodySize)}"}""")
        f.roots = (1..evidenceCount).map { entity("evidence_block", "e$it", """{"contextRefSyncId":"context"}""") }
        SyncPagedEntityGraph.requireComplete(f.store, "fixed-root")
        assertEquals(0, f.bodyReads)
    }

    @Test fun cacheHitStillRejectsDifferentChildContext() {
        val f = Fixture()
        f.parents["context_ref" to "context"] = entity("context_ref", "context", """{"conversationSyncId":"c1","assistantMessageSyncId":"m1"}""")
        f.roots = listOf(entity("evidence_block", "e1", """{"contextRefSyncId":"context","conversationSyncId":"c1"}"""),
            entity("evidence_block", "e2", """{"contextRefSyncId":"context","conversationSyncId":"c2"}"""))
        assertThrows(IllegalStateException::class.java) { SyncPagedEntityGraph.requireComplete(f.store, "b") }
        assertEquals(0, f.bodyReads)
    }

    @Test fun cacheHitStillRejectsWrongParentGeneration() {
        val f = Fixture()
        val parent = entity("feed", "f", """{"groupSyncId":"g"}""")
        f.parents["feed" to "f"] = parent.copy(value = JsonObject(parent.value + ("generation" to JsonPrimitive(2))))
        f.roots = listOf(entity("article", "a1", """{"feedSyncId":"f","feedGeneration":2}"""),
            entity("article", "a2", """{"feedSyncId":"f","feedGeneration":3}"""))
        assertThrows(IllegalStateException::class.java) { SyncPagedEntityGraph.requireComplete(f.store, "b") }
        assertEquals(0, f.bodyReads)
    }

    @Test fun missingAndDeletedParentsRemainErrors() {
        val f = Fixture()
        f.roots = listOf(entity("evidence_block", "e", """{"contextRefSyncId":"context"}"""))
        assertThrows(IllegalStateException::class.java) { SyncPagedEntityGraph.requireComplete(f.store, "b") }
        f.parents["context_ref" to "context"] = entity("context_ref", "context", """{"conversationSyncId":"c","assistantMessageSyncId":"m"}""")
        f.deleted.add("context_ref" to "context")
        assertThrows(IllegalStateException::class.java) { SyncPagedEntityGraph.requireComplete(f.store, "b") }
        assertEquals(0, f.bodyReads)
    }

    @Test fun independentValidationDoesNotReusePreviousParentProof() {
        val f = Fixture()
        f.roots = listOf(entity("evidence_block", "e", """{"contextRefSyncId":"context"}"""))
        f.parents["context_ref" to "context"] = entity("context_ref", "context", """{"conversationSyncId":"c","assistantMessageSyncId":"m"}""")
        SyncPagedEntityGraph.requireComplete(f.store, "b1")
        f.parents.clear()
        assertThrows(IllegalStateException::class.java) { SyncPagedEntityGraph.requireComplete(f.store, "b2") }
        assertEquals(2, f.parentReads["context_ref" to "context"])
        assertEquals(0, f.bodyReads)
    }
}
