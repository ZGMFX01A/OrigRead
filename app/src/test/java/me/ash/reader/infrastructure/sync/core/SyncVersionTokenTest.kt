package me.ash.reader.infrastructure.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncVersionTokenTest {
    @Test
    fun lateGenesisIsConcurrentUnlessItsLaneBaselineWasObserved() {
        val genesis = SyncFieldCandidate("isStarred", "true",
            SyncVersionToken.genesis("late", "ARTICLE_STATE", "article", "isStarred"), SyncVersionSource.GENESIS)
        val operation = SyncFieldCandidate("isStarred", "false",
            SyncVersionToken.operation("actor", "ARTICLE_STATE", 1), SyncVersionSource.OPERATION)
        assertEquals(false, SyncVersionToken.happensBefore(genesis, operation))
        assertEquals(operation, SyncVersionResolver.resolve(listOf(genesis, operation), SyncGenesisMergePolicy.STARRED_WINS))
        val observed = operation.copy(causalContextJson = """{"schemaVersion":1,"lanes":[],"observedGenesisBaselinesByLane":{"ARTICLE_STATE":["late"]}}""")
        assertEquals(true, SyncVersionToken.happensBefore(genesis, observed))
        val wrongLane = operation.copy(causalContextJson = """{"schemaVersion":1,"lanes":[],"observedGenesisBaselinesByLane":{"LIBRARY":["late"]}}""")
        assertEquals(false, SyncVersionToken.happensBefore(genesis, wrongLane))
    }

    @Test
    fun invalidOperationTokensCannotSupplyCausalEvidence() {
        listOf("OPERATION_V1|actor|LIBRARY|0", "OPERATION_V1|actor|LIBRARY|-1",
            "OPERATION_V1||LIBRARY|1", "OPERATION_V1|actor|LIBRARY|1|extra",
            "OPERATION_V1|actor|LIBRARY|1junk").forEach {
            assertEquals(null, SyncVersionToken.parseOperationDot(it))
        }
    }

    @Test
    fun genesisTokenIsStableAndOperationSharesTheSameFieldSurface() {
        val first = SyncVersionToken.genesis("baseline-a", "ARTICLE_STATE", "article-1", "isStarred")
        val second = SyncVersionToken.genesis("baseline-a", "ARTICLE_STATE", "article-1", "isStarred")
        assertEquals(first, second)
        assertEquals(SyncVersionSource.GENESIS, SyncVersionToken.source(first))
        assertEquals(
            SyncVersionSource.OPERATION,
            SyncVersionToken.source(SyncVersionToken.operation("actor-a", "ARTICLE_STATE", 1)),
        )
        assertEquals(
            "GENESIS_V1|baseline-a|ARTICLE_STATE|article-1|isStarred|9d29aea2342bcde6bb2fca175acfb7c15c0f2344b8cfdb89ca0c382066d9bcd0",
            SyncVersionToken.genesis("baseline-a", "ARTICLE_STATE", "article-1", "isStarred"),
        )
        assertNotEquals(first, SyncVersionToken.genesis("baseline-b", "ARTICLE_STATE", "article-1", "isStarred"))
    }

    @Test
    fun genesisMergePoliciesPreserveReadAndStarredSafetyAndOperationPrecedence() {
        val read =
            listOf(
                SyncFieldCandidate("isUnread", "true", "GENESIS_V1|a|ARTICLE_STATE|article|isUnread|1", SyncVersionSource.GENESIS),
                SyncFieldCandidate("isUnread", "false", "GENESIS_V1|b|ARTICLE_STATE|article|isUnread|2", SyncVersionSource.GENESIS),
            )
        assertEquals("false", SyncVersionResolver.resolve(read, SyncGenesisMergePolicy.READ_WINS).valueJson)

        val starred =
            listOf(
                SyncFieldCandidate("isStarred", "false", "GENESIS_V1|a|ARTICLE_STATE|article|isStarred|1", SyncVersionSource.GENESIS),
                SyncFieldCandidate("isStarred", "true", "GENESIS_V1|b|ARTICLE_STATE|article|isStarred|2", SyncVersionSource.GENESIS),
            )
        assertEquals("true", SyncVersionResolver.resolve(starred, SyncGenesisMergePolicy.STARRED_WINS).valueJson)

        val postEnable =
            starred +
                SyncFieldCandidate(
                    "isStarred",
                    "false",
                    SyncVersionToken.operation("actor-a", "ARTICLE_STATE", 3),
                    SyncVersionSource.OPERATION,
                )
        assertEquals("false", SyncVersionResolver.resolve(postEnable, SyncGenesisMergePolicy.STARRED_WINS).valueJson)
    }

    @Test(expected = SyncGenesisVersionCollisionException::class)
    fun differentValuesForOneVersionTokenAreRejected() {
        SyncVersionResolver.resolve(
            listOf(
                SyncFieldCandidate("isStarred", "true", "same-token", SyncVersionSource.GENESIS),
                SyncFieldCandidate("isStarred", "false", "same-token", SyncVersionSource.GENESIS),
            ),
            SyncGenesisMergePolicy.DETERMINISTIC,
        )
    }

    @Test
    fun happensBeforeWinsEvenWhenTokenSortsSmaller() {
        // actor-z 的字典序大于 actor-a，但 actor-a 的操作在因果上发生在 actor-z 之后（causalContext 包含了 actor-z seq 1）
        val opZ = SyncFieldCandidate(
            fieldId = "isStarred",
            valueJson = "true",
            token = SyncVersionToken.operation("actor-z", "ARTICLE_STATE", 1),
            source = SyncVersionSource.OPERATION,
        )
        val causalContextOfA = """{"schemaVersion":1,"lanes":[{"replicationLaneId":"ARTICLE_STATE","actors":[{"actorIncarnationId":"actor-z","prefix":1}]}]}"""
        val opA = SyncFieldCandidate(
            fieldId = "isStarred",
            valueJson = "false",
            token = SyncVersionToken.operation("actor-a", "ARTICLE_STATE", 1),
            source = SyncVersionSource.OPERATION,
            causalContextJson = causalContextOfA,
        )

        // opZ happens-before opA，因此无论传入顺序如何，因果后发生的 opA 必然胜出
        val winner1 = SyncVersionResolver.resolve(listOf(opZ, opA), SyncGenesisMergePolicy.STARRED_WINS)
        assertEquals("false", winner1.valueJson)
        assertEquals(opA.token, winner1.token)

        val winner2 = SyncVersionResolver.resolve(listOf(opA, opZ), SyncGenesisMergePolicy.STARRED_WINS)
        assertEquals("false", winner2.valueJson)
        assertEquals(opA.token, winner2.token)
    }

    @Test
    fun concurrentOperationsFallBackToPolicyAndDeterministicTieBreaker() {
        // 两个真正并发的操作（互相未观察到彼此）
        val op1 = SyncFieldCandidate(
            fieldId = "isStarred",
            valueJson = "true",
            token = SyncVersionToken.operation("actor-1", "ARTICLE_STATE", 1),
            source = SyncVersionSource.OPERATION,
        )
        val op2 = SyncFieldCandidate(
            fieldId = "isStarred",
            valueJson = "false",
            token = SyncVersionToken.operation("actor-2", "ARTICLE_STATE", 1),
            source = SyncVersionSource.OPERATION,
        )

        // Explicit operations use the deterministic register; starred-wins is Genesis-only.
        val winnerStarred = SyncVersionResolver.resolve(listOf(op1, op2), SyncGenesisMergePolicy.STARRED_WINS)
        assertEquals("false", winnerStarred.valueJson)

        // READ_WINS (针对 isUnread)
        val read1 = SyncFieldCandidate(
            fieldId = "isUnread",
            valueJson = "true",
            token = SyncVersionToken.operation("actor-1", "ARTICLE_STATE", 1),
            source = SyncVersionSource.OPERATION,
        )
        val read2 = SyncFieldCandidate(
            fieldId = "isUnread",
            valueJson = "false",
            token = SyncVersionToken.operation("actor-2", "ARTICLE_STATE", 1),
            source = SyncVersionSource.OPERATION,
        )
        val winnerRead = SyncVersionResolver.resolve(listOf(read1, read2), SyncGenesisMergePolicy.READ_WINS)
        assertEquals("false", winnerRead.valueJson)
    }

    @Test fun sameActorSequenceUsesNumericOrdering() {
        val old = SyncFieldCandidate("name", "\"old\"", SyncVersionToken.operation("actor", "LIBRARY", 9), SyncVersionSource.OPERATION)
        val new = old.copy(valueJson = "\"new\"", token = SyncVersionToken.operation("actor", "LIBRARY", 10))
        assertEquals(new, SyncVersionResolver.resolve(listOf(new, old), SyncGenesisMergePolicy.DETERMINISTIC))
    }

    @Test fun concurrentRegistersUseLogicalClockAndAreOrderIndependent() {
        val a = SyncFieldCandidate("isStarred", "true", SyncVersionToken.operation("z", "ARTICLE_STATE", 1), SyncVersionSource.OPERATION, logicalClock = 5)
        val b = a.copy(valueJson = "false", token = SyncVersionToken.operation("a", "ARTICLE_STATE", 1), logicalClock = 6)
        assertEquals(b, SyncVersionResolver.resolve(listOf(a, b), SyncGenesisMergePolicy.STARRED_WINS))
        assertEquals(b, SyncVersionResolver.resolve(listOf(b, a), SyncGenesisMergePolicy.STARRED_WINS))
    }
}
