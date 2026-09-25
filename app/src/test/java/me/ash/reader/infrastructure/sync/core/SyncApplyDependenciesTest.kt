package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApplyDependenciesTest {
    @Test
    fun dependenciesNeedAppliedKnowledgeRatherThanNetworkReceipt() {
        val dependencies = """[{"actorIncarnationId":"remote","replicationLaneId":"LIBRARY","sequence":2}]"""
        assertFalse(SyncApplyDependencies.satisfied(dependencies, emptyMap()))
        assertFalse(SyncApplyDependencies.satisfied(dependencies, mapOf("LIBRARY" to mapOf("remote" to 1L))))
        assertTrue(SyncApplyDependencies.satisfied(dependencies, mapOf("LIBRARY" to mapOf("remote" to 2L))))
    }

    @Test
    fun emptyDependenciesDoNotRequireUnrelatedPausedLanes() {
        assertTrue(SyncApplyDependencies.satisfied("[]", emptyMap()))
    }

    @Test
    fun invalidDependencyCannotBeSilentlyIgnored() {
        listOf("{}", "null", "[{}]", """[{"actorIncarnationId":"a","replicationLaneId":"LIBRARY","sequence":0}]""")
            .forEach { assertFalse(SyncApplyDependencies.satisfied(it, emptyMap())) }
    }
}
