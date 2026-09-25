package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@kotlinx.serialization.Serializable
private data class SyncGenesisPolicySnapshot(
    val defaultPolicy: String,
    val fieldPolicies: Map<String, String>,
    val lanes: List<String>,
)

/** Canonical encodings shared by the durable cut row and Snapshot manifest. */
object SyncGenesisCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encodeFrontiers(frontiers: Map<String, Map<String, Long>>): String {
        val rows =
            frontiers
                .toSortedMap()
                .map { (lane, actors) ->
                    SyncLaneFrontierSnapshot(lane, actors.toSortedMap())
                }
        return SyncOperationCanonicalizer.canonicalJson(json.encodeToString(rows))
    }

    fun decodeFrontiers(value: String): Map<String, Map<String, Long>> {
        runCatching {
            return json.decodeFromString<List<SyncLaneFrontierSnapshot>>(value)
                .associate { it.replicationLaneId to it.actorFrontiers.toSortedMap() }
                .toSortedMap()
        }
        val root = json.parseToJsonElement(value).jsonObject
        return root.entries
            .sortedBy { it.key }
            .associate { (lane, actorsElement) ->
                lane to actorsElement.jsonObject.entries
                    .sortedBy { it.key }
                    .associate { (actor, prefix) ->
                        actor to (
                            prefix.jsonPrimitive.content.toLongOrNull()
                                ?: error("Invalid Genesis frontier prefix for $actor")
                            )
                    }
            }
    }

    fun encodeStringList(values: Iterable<String>): String =
        SyncOperationCanonicalizer.canonicalJson(json.encodeToString(values.toList().sorted()))

    fun encodePolicy(lanes: Iterable<String>, fieldPolicies: Map<String, String>): String =
        SyncOperationCanonicalizer.canonicalJson(
            json.encodeToString(
                SyncGenesisPolicySnapshot(
                    defaultPolicy = "DETERMINISTIC",
                    fieldPolicies = fieldPolicies.toSortedMap(),
                    lanes = lanes.toList(),
                ),
            ),
        )

    fun hashCanonicalJson(value: String): String =
        SyncOperationCanonicalizer.sha256Hex(SyncOperationCanonicalizer.canonicalJson(value))
}
