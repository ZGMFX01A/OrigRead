package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FrozenActorPrefixV1(
    val actorIncarnationId: String,
    val prefix: Long,
)

@Serializable
data class FrozenLaneFrontierV1(
    val replicationLaneId: String,
    val actors: List<FrozenActorPrefixV1>,
)

@Serializable
data class FrozenCausalContextV1(
    val schemaVersion: Int,
    val lanes: List<FrozenLaneFrontierV1>,
    val observedGenesisBaselinesByLane: Map<String, List<String>> = emptyMap(),
)

object SyncCausalContextCodec {
    // Keep the original compact shape when no Genesis baseline has been observed. Once a cut is
    // active, the optional field becomes the durable proof that a tail Operation saw that baseline.
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    fun freeze(
        writerStates: List<SyncLaneWriterStateEntity>,
        appliedFrontiers: List<SyncAppliedFrontierEntity>,
        additionalFrontiers: List<SyncAppliedFrontierEntity> = emptyList(),
        observedGenesisBaselinesByLane: Map<String, List<String>> = emptyMap(),
    ): String {
        val prefixes = linkedMapOf<Pair<String, String>, Long>()

        fun merge(lane: String, actor: String, prefix: Long) {
            if (prefix <= 0) return
            val key = lane to actor
            prefixes[key] = maxOf(prefixes[key] ?: 0L, prefix)
        }

        writerStates.forEach { merge(it.replicationLaneId, it.actorIncarnationId, it.lastSequence) }
        appliedFrontiers.forEach { merge(it.replicationLaneId, it.actorIncarnationId, it.appliedPrefix) }
        additionalFrontiers.forEach { merge(it.replicationLaneId, it.actorIncarnationId, it.appliedPrefix) }

        val lanes =
            prefixes.entries
                .groupBy { it.key.first }
                .toSortedMap()
                .map { (lane, entries) ->
                    FrozenLaneFrontierV1(
                        replicationLaneId = lane,
                        actors =
                            entries
                                .map { (key, prefix) -> FrozenActorPrefixV1(key.second, prefix) }
                                .sortedBy(FrozenActorPrefixV1::actorIncarnationId),
                    )
                }

        return json.encodeToString(
            FrozenCausalContextV1(
                schemaVersion = 1,
                lanes = lanes,
                observedGenesisBaselinesByLane = observedGenesisBaselinesByLane
                    .toSortedMap()
                    .mapValues { (_, baselines) -> baselines.distinct().sorted() },
            ),
        )
    }

    fun decode(jsonString: String): FrozenCausalContextV1? =
        try {
            json.decodeFromString<FrozenCausalContextV1>(jsonString)
        } catch (_: Throwable) {
            null
        }

    /**
     * 判断给定的 causalContextJson 是否因果覆盖了指定 lane/actor 在 sequence 处的 Dot。
     */
    fun covers(contextJson: String?, lane: String, actor: String, sequence: Long): Boolean {
        if (contextJson.isNullOrBlank() || sequence <= 0) return false
        val context = decode(contextJson) ?: return false
        val laneFrontier = context.lanes.firstOrNull { it.replicationLaneId == lane } ?: return false
        val actorPrefix = laneFrontier.actors.firstOrNull { it.actorIncarnationId == actor }?.prefix ?: 0L
        return actorPrefix >= sequence
    }
}
