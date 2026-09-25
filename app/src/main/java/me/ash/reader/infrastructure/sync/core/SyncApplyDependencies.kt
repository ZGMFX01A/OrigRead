package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json

/** Causal context is merge metadata; only explicit dependencies gate materialization. */
object SyncApplyDependencies {
    fun satisfied(dependencyDotsJson: String, applied: SyncCoverage): Boolean =
        runCatching {
            Json.decodeFromString<List<SyncDotWire>>(dependencyDotsJson).all { dot ->
                dot.actorIncarnationId.isNotBlank() && dot.replicationLaneId.isNotBlank() &&
                    dot.sequence > 0 &&
                    (applied[dot.replicationLaneId]?.get(dot.actorIncarnationId) ?: 0L) >= dot.sequence
            }
        }.getOrDefault(false)
}
