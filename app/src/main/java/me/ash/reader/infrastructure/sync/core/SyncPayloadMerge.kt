package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Field register merge for AI records; the original payload stays immutable in the log/journal. */
object SyncPayloadMerge {
    fun candidates(operations: List<SyncOperationEntity>): Map<String, List<SyncFieldCandidate>> {
        val candidates = linkedMapOf<String, MutableList<SyncFieldCandidate>>()
        operations.forEach { operation ->
            val payload = Json.parseToJsonElement(operation.payloadJson).jsonObject
            val fields = when (operation.operationType) {
                SyncMutationType.FIELD_SET.name -> mapOf(
                    (payload["field"]?.jsonPrimitive?.content ?: error("FIELD_SET has no field")) to
                        (payload["value"] ?: error("FIELD_SET has no value")),
                )
                SyncMutationType.UPSERT.name, SyncMutationType.RELATION_SET.name ->
                    (payload["fields"] as? JsonObject) ?: payload
                else -> emptyMap()
            }
            fields.forEach { (field, value) ->
                candidates.getOrPut(field) { mutableListOf() }.add(SyncFieldCandidate(
                    fieldId = field, valueJson = SyncOperationCanonicalizer.canonicalJson(value.toString()),
                    token = SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence),
                    source = SyncVersionSource.OPERATION, causalContextJson = operation.causalContextJson,
                    logicalClock = operation.logicalClock,
                ))
            }
        }
        return candidates
    }

    fun resolve(operations: List<SyncOperationEntity>, retained: List<SyncFieldCandidate> = emptyList()): Map<String, SyncFieldCandidate> {
        val all = candidates(operations).values.flatten() + retained
        return all.groupBy { it.fieldId }.mapValues { (_, values) ->
            SyncVersionResolver.resolve(values, SyncGenesisMergePolicy.DETERMINISTIC)
        }
    }
}
