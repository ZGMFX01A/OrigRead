package me.ash.reader.infrastructure.sync.core

/** The two version sources share one comparison surface after Genesis cutover. */
enum class SyncVersionSource {
    GENESIS,
    OPERATION,
}

enum class SyncGenesisMergePolicy {
    DETERMINISTIC,
    READ_WINS,
    STARRED_WINS,
    SET_WINS,
}

data class SyncFieldCandidate(
    val fieldId: String,
    val valueJson: String,
    val token: String,
    val source: SyncVersionSource,
    val observedGenesisBaselines: Set<String> = emptySet(),
    val causalContextJson: String? = null,
    val logicalClock: Long = 0L,
)

data class SyncOperationDot(
    val actorIncarnationId: String,
    val replicationLaneId: String,
    val sequence: Long,
)

class SyncGenesisVersionCollisionException(message: String) : IllegalStateException(message)

/**
 * R10 unified VersionToken primitive.
 *
 * The token is deliberately a stable string at this layer so it can be embedded in snapshot JSON and
 * later carried by a wire merge engine without coupling the snapshot schema to Kotlin sealed classes.
 */
object SyncVersionToken {
    private const val GENESIS_PREFIX = "GENESIS_V1"
    private const val OPERATION_PREFIX = "OPERATION_V1"

    fun genesis(
        genesisBaselineId: String,
        replicationLaneId: String,
        entitySyncId: String,
        fieldId: String,
    ): String {
        require(genesisBaselineId.isNotBlank()) { "genesisBaselineId must not be blank" }
        require(replicationLaneId.isNotBlank()) { "replicationLaneId must not be blank" }
        require(entitySyncId.isNotBlank()) { "entitySyncId must not be blank" }
        require(fieldId.isNotBlank()) { "fieldId must not be blank" }
        val stamp =
            SyncOperationCanonicalizer.sha256Hex(
                framed("genesisBaselineId", genesisBaselineId, "replicationLaneId", replicationLaneId, "entitySyncId", entitySyncId, "fieldId", fieldId),
            )
        return "$GENESIS_PREFIX|$genesisBaselineId|$replicationLaneId|$entitySyncId|$fieldId|$stamp"
    }

    fun operation(actorIncarnationId: String, replicationLaneId: String, sequence: Long): String {
        require(actorIncarnationId.isNotBlank()) { "actorIncarnationId must not be blank" }
        require(replicationLaneId.isNotBlank()) { "replicationLaneId must not be blank" }
        require(sequence > 0) { "sequence must be positive" }
        return "$OPERATION_PREFIX|$actorIncarnationId|$replicationLaneId|$sequence"
    }

    fun source(token: String): SyncVersionSource =
        when {
            token.startsWith("$GENESIS_PREFIX|") -> SyncVersionSource.GENESIS
            token.startsWith("$OPERATION_PREFIX|") -> SyncVersionSource.OPERATION
            else -> error("Unknown Sync VersionToken: $token")
        }

    fun parseOperationDot(token: String): SyncOperationDot? {
        if (!token.startsWith("$OPERATION_PREFIX|")) return null
        val parts = token.split('|')
        if (parts.size != 4) return null
        val actor = parts[1]
        val lane = parts[2]
        val seq = parts[3].toLongOrNull() ?: return null
        if (actor.isBlank() || lane.isBlank() || !parts[3].matches(Regex("[0-9]+")) || seq <= 0L || seq > 9_007_199_254_740_991L) return null
        return SyncOperationDot(actor, lane, seq)
    }

    /**
     * 判断 candidateA 是否因果上严格先于 candidateB (A happens-before B)
     */
    fun happensBefore(candidateA: SyncFieldCandidate, candidateB: SyncFieldCandidate): Boolean {
        val sourceA = candidateA.source
        val sourceB = candidateB.source

        // An unobserved baseline is concurrent; operation precedence is a merge policy,
        // not evidence that the operation observed a late-joining device's history.
        if (sourceA == SyncVersionSource.GENESIS && sourceB == SyncVersionSource.OPERATION) {
            val parts = candidateA.token.split('|')
            if (parts.size != 6) return false
            val observed = candidateB.causalContextJson?.let(SyncCausalContextCodec::decode)
                ?.observedGenesisBaselinesByLane?.get(parts[2]).orEmpty()
            return parts[1] in observed || parts[1] in candidateB.observedGenesisBaselines
        }
        if (sourceA == SyncVersionSource.OPERATION && sourceB == SyncVersionSource.GENESIS) return false

        // 都是 OPERATION 时，比对 candidateB 的因果上下文是否覆盖了 candidateA 的 Dot
        if (sourceA == SyncVersionSource.OPERATION && sourceB == SyncVersionSource.OPERATION) {
            val dotA = parseOperationDot(candidateA.token) ?: return false
            val dotB = parseOperationDot(candidateB.token) ?: return false
            if (dotA.actorIncarnationId == dotB.actorIncarnationId && dotA.replicationLaneId == dotB.replicationLaneId && dotA.sequence < dotB.sequence) return true
            if (SyncCausalContextCodec.covers(candidateB.causalContextJson, dotA.replicationLaneId, dotA.actorIncarnationId, dotA.sequence)) {
                return true
            }
        }
        return false
    }

    private fun framed(vararg fields: String): String =
        buildString {
            require(fields.size % 2 == 0)
            fields.asList().chunked(2).forEach { (name, value) ->
                append(name)
                append('=')
                append(value.toByteArray(Charsets.UTF_8).size)
                append(':')
                append(value)
                append('\n')
            }
        }
}

object SyncVersionResolver {
    /**
     * Resolve conflicting field versions using causal ordering, merge policy, and deterministic tie-breaking.
     */
    fun resolve(
        candidates: List<SyncFieldCandidate>,
        policy: SyncGenesisMergePolicy,
    ): SyncFieldCandidate {
        require(candidates.isNotEmpty()) { "At least one field candidate is required" }
        val duplicateTokens = candidates.groupBy(SyncFieldCandidate::token)
        duplicateTokens.values.forEach { sameToken ->
            if (sameToken.map(SyncFieldCandidate::valueJson).distinct().size > 1) {
                throw SyncGenesisVersionCollisionException(
                    "Same VersionToken carries different values for ${sameToken.first().fieldId}",
                )
            }
        }

        // 去重候选
        val unique = duplicateTokens.values.map { it.first() }

        // 1. 因果消解：过滤掉被因果覆盖的陈旧候选（若 A happens-before B 则 A 被裁决淘汰）
        val causalMaximal = unique.filter { candidate ->
            unique.none { other -> other !== candidate && SyncVersionToken.happensBefore(candidate, other) }
        }

        if (causalMaximal.size == 1) {
            return causalMaximal.first()
        }

        // 2. 并发候选之间执行业务偏好决胜
        val preferred = if (causalMaximal.all { it.source == SyncVersionSource.GENESIS })
            when (policy) {
                SyncGenesisMergePolicy.READ_WINS -> causalMaximal.filter { it.valueJson == "false" }.maxByOrNull { it.token }
                SyncGenesisMergePolicy.STARRED_WINS,
                SyncGenesisMergePolicy.SET_WINS,
                -> causalMaximal.filter { it.valueJson == "true" }.maxByOrNull { it.token }
                SyncGenesisMergePolicy.DETERMINISTIC -> null
            } else null
        return preferred ?: causalMaximal.maxWithOrNull(
            compareBy<SyncFieldCandidate> { if (it.source == SyncVersionSource.OPERATION) 1 else 0 }
                .thenBy { it.logicalClock }
                .thenBy { SyncVersionToken.parseOperationDot(it.token)?.actorIncarnationId.orEmpty() }
                .thenBy { SyncVersionToken.parseOperationDot(it.token)?.replicationLaneId.orEmpty() }
                .thenBy { SyncVersionToken.parseOperationDot(it.token)?.sequence ?: 0L }
                .thenBy { it.token },
        )!!
    }
}
