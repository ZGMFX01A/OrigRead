package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object SyncOperationWireCodec {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = true
        }

    fun toWire(operation: SyncOperationEntity): SyncOperationEnvelope =
        SyncOperationEnvelope(
            operationId = operation.operationId,
            syncSpaceId = operation.syncSpaceId,
            authorDeviceId = operation.authorDeviceId,
            actorIncarnationId = operation.actorIncarnationId,
            replicationLaneId = operation.replicationLaneId,
            sequence = operation.sequence,
            logicalClock = operation.logicalClock,
            causalContextJson = operation.causalContextJson,
            dependencyDotsJson = operation.dependencyDotsJson,
            entityType = operation.entityType,
            entitySyncId = operation.entitySyncId,
            entityGeneration = operation.entityGeneration,
            operationType = operation.operationType,
            payloadSchemaVersion = operation.payloadSchemaVersion,
            payloadJson = operation.payloadJson,
            schemaVersion = operation.schemaVersion,
            authGrantId = operation.authGrantId,
            authEpoch = operation.authEpoch,
            createdWallClock = operation.createdWallClock,
            payloadHash = operation.payloadHash,
            signingDigest = operation.signingDigest,
            authorSignature = checkNotNull(operation.authorSignature),
        )

    fun fromWire(envelope: SyncOperationEnvelope, receivedAt: Long = System.currentTimeMillis()): SyncOperationEntity {
        validate(envelope)
        return SyncOperationEntity(
            operationId = envelope.operationId,
            syncSpaceId = envelope.syncSpaceId,
            authorDeviceId = envelope.authorDeviceId,
            actorIncarnationId = envelope.actorIncarnationId,
            replicationLaneId = envelope.replicationLaneId,
            sequence = envelope.sequence,
            logicalClock = envelope.logicalClock,
            causalContextJson = envelope.causalContextJson,
            dependencyDotsJson = envelope.dependencyDotsJson,
            entityType = envelope.entityType,
            entitySyncId = envelope.entitySyncId,
            entityGeneration = envelope.entityGeneration,
            operationType = envelope.operationType,
            payloadSchemaVersion = envelope.payloadSchemaVersion,
            payloadJson = envelope.payloadJson,
            schemaVersion = envelope.schemaVersion,
            authGrantId = envelope.authGrantId,
            authEpoch = envelope.authEpoch,
            createdWallClock = envelope.createdWallClock,
            payloadHash = envelope.payloadHash,
            signingDigest = envelope.signingDigest,
            authorSignature = envelope.authorSignature,
            buildStatus = SyncOperationBuildStatus.SIGNED.name,
            createdAt = receivedAt,
            updatedAt = receivedAt,
        )
    }

    fun encode(envelope: SyncOperationEnvelope): String =
        canonicalize(json.encodeToJsonElement(SyncOperationEnvelope.serializer(), envelope)).toString()

    fun decode(value: String): SyncOperationEnvelope =
        json.decodeFromString(SyncOperationEnvelope.serializer(), value)

    fun validate(envelope: SyncOperationEnvelope) {
        require(envelope.protocolVersion == SYNC_PROTOCOL_VERSION) { "Unsupported Sync protocol version" }
        require(envelope.operationId.isNotBlank()) { "operationId must not be blank" }
        require(envelope.syncSpaceId.isNotBlank()) { "syncSpaceId must not be blank" }
        require(envelope.authorDeviceId.isNotBlank()) { "authorDeviceId must not be blank" }
        require(envelope.actorIncarnationId.isNotBlank()) { "actorIncarnationId must not be blank" }
        require(envelope.replicationLaneId.isNotBlank()) { "replicationLaneId must not be blank" }
        require(envelope.sequence in 1..9_007_199_254_740_991L) { "sequence must be a positive safe integer" }
        require(envelope.logicalClock in 1..9_007_199_254_740_991L) { "logicalClock must be a positive safe integer" }
        require(envelope.entityGeneration in 0..9_007_199_254_740_991L) { "entityGeneration must be a non-negative safe integer" }
        require(envelope.createdWallClock in 0..9_007_199_254_740_991L) { "createdWallClock must be a non-negative safe integer" }
        require(envelope.authEpoch == null || envelope.authEpoch in 0..9_007_199_254_740_991L) { "authEpoch must be a non-negative safe integer" }
        require(envelope.entityType.isNotBlank() && envelope.entitySyncId.isNotBlank() &&
            envelope.operationType.isNotBlank() && envelope.authorSignature.isNotBlank()) { "Operation identity and signature must not be blank" }
        require(envelope.payloadSchemaVersion > 0) { "payloadSchemaVersion must be positive" }
        require(envelope.schemaVersion > 0) { "schemaVersion must be positive" }
        val payload = SyncOperationCanonicalizer.canonicalJson(envelope.payloadJson)
        require(payload == envelope.payloadJson) { "payloadJson is not canonical" }
        require(SyncOperationCanonicalizer.canonicalJson(envelope.causalContextJson) == envelope.causalContextJson) { "causalContextJson is not canonical" }
        require(SyncOperationCanonicalizer.canonicalJson(envelope.dependencyDotsJson) == envelope.dependencyDotsJson) { "dependencyDotsJson is not canonical" }
        require(envelope.payloadHash == SyncOperationCanonicalizer.sha256Hex(payload)) { "payloadHash mismatch" }
        require(envelope.operationId == SyncOperationCanonicalizer.operationId(envelope.syncSpaceId, envelope.actorIncarnationId, envelope.replicationLaneId, envelope.sequence)) { "operationId mismatch" }
        require(envelope.signingDigest == SyncOperationCanonicalizer.signingDigest(fromWireUnchecked(envelope))) { "signingDigest mismatch" }
    }

    fun verify(
        envelope: SyncOperationEnvelope,
        publicKeySpkiBase64: String,
        keyStore: SyncDeviceSigningKeyStore,
    ): Boolean =
        runCatching {
            validate(envelope)
            keyStore.verifyBase64(
                publicKeySpkiBase64,
                SyncOperationCanonicalizer.signingMaterial(fromWireUnchecked(envelope)).toByteArray(Charsets.UTF_8),
                envelope.authorSignature,
            )
        }.getOrDefault(false)

    private fun fromWireUnchecked(envelope: SyncOperationEnvelope): SyncOperationEntity =
        SyncOperationEntity(
            operationId = envelope.operationId,
            syncSpaceId = envelope.syncSpaceId,
            authorDeviceId = envelope.authorDeviceId,
            actorIncarnationId = envelope.actorIncarnationId,
            replicationLaneId = envelope.replicationLaneId,
            sequence = envelope.sequence,
            logicalClock = envelope.logicalClock,
            causalContextJson = envelope.causalContextJson,
            dependencyDotsJson = envelope.dependencyDotsJson,
            entityType = envelope.entityType,
            entitySyncId = envelope.entitySyncId,
            entityGeneration = envelope.entityGeneration,
            operationType = envelope.operationType,
            payloadSchemaVersion = envelope.payloadSchemaVersion,
            payloadJson = envelope.payloadJson,
            schemaVersion = envelope.schemaVersion,
            authGrantId = envelope.authGrantId,
            authEpoch = envelope.authEpoch,
            createdWallClock = envelope.createdWallClock,
            payloadHash = envelope.payloadHash,
            signingDigest = envelope.signingDigest,
            authorSignature = envelope.authorSignature,
            buildStatus = SyncOperationBuildStatus.SIGNED.name,
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun canonicalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(element.entries.sortedBy { it.key }.associate { (key, value) -> key to canonicalize(value) })
            is JsonArray -> JsonArray(element.map(::canonicalize))
            else -> element
        }
}
