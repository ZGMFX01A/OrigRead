package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

class SyncAuthWireValidationException(message: String) : IllegalArgumentException(message)

/**
 * Canonical AUTH-lane codec shared with Desktop.
 *
 * AUTH objects are deliberately kept separate from the member cache: the signed ledger is the
 * source of truth, while the cache is only an index used by the operation verifier.
 */
object SyncAuthWireCodec {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = true
        }

    private val coverageSerializer =
        MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer()))

    fun encode(objectValue: SyncAuthProtocolObject): String {
        validate(objectValue)
        return canonicalize(
            json.encodeToJsonElement(SyncAuthProtocolObject.serializer(), objectValue),
        ).toString()
    }

    fun decode(value: String): SyncAuthProtocolObject =
        runCatching {
            json.decodeFromString(SyncAuthProtocolObject.serializer(), value).also(::validate)
        }.getOrElse { error ->
            if (error is SyncAuthWireValidationException) throw error
            throw SyncAuthWireValidationException(error.message ?: "Invalid AUTH JSON")
        }

    fun authObjectId(
        syncSpaceId: String,
        authEpoch: Long,
        objectType: SyncAuthObjectType,
        authorDeviceId: String,
        payloadHash: String,
        authSequence: Long = 0L,
    ): String {
        val material =
            listOf(
                "ORIGREAD_SYNC_AUTH_ID_V1",
                framed("syncSpaceId", syncSpaceId),
                framed("authEpoch", authEpoch.toString()),
                framed("authSequence", authSequence.toString()),
                framed("objectType", objectType.name),
                framed("authorDeviceId", authorDeviceId),
                framed("payloadHash", payloadHash),
            ).joinToString("\n")
        return "auth1:${SyncOperationCanonicalizer.sha256Hex(material)}"
    }

    fun signingMaterial(objectValue: SyncAuthProtocolObject): String =
        buildString {
            append("ORIGREAD_SYNC_AUTH_V1\n")
            field("authObjectId", objectValue.authObjectId)
            field("syncSpaceId", objectValue.syncSpaceId)
            field("authEpoch", objectValue.authEpoch.toString())
            field("authSequence", objectValue.authSequence.toString())
            field("objectType", objectValue.objectType.name)
            field("authorDeviceId", objectValue.authorDeviceId)
            field("ownerDeviceId", objectValue.ownerDeviceId)
            nullableField("targetDeviceId", objectValue.targetDeviceId)
            field(
                "previousEpochFinalAcceptedPrefixByActorLane",
                canonicalCoverage(objectValue.previousEpochFinalAcceptedPrefixByActorLane),
            )
            nullableField(
                "revokeCutoffByActorLane",
                objectValue.revokeCutoffByActorLane?.let(::canonicalCoverage),
            )
            field("payloadHash", objectValue.payloadHash)
        }

    fun signingDigest(objectValue: SyncAuthProtocolObject): String =
        SyncOperationCanonicalizer.sha256Hex(signingMaterial(objectValue))

    /** Canonicalize and sign an AUTH object with the installation key. */
    fun sign(
        objectValue: SyncAuthProtocolObject,
        keyStore: SyncDeviceSigningKeyStore,
    ): SyncAuthProtocolObject {
        val payloadJson = canonicalJson(objectValue.payloadJson)
        val payloadHash = SyncOperationCanonicalizer.sha256Hex(payloadJson)
        val unsigned =
            objectValue.copy(
                payloadJson = payloadJson,
                payloadHash = payloadHash,
                authObjectId =
                    authObjectId(
                        objectValue.syncSpaceId,
                        objectValue.authEpoch,
                        objectValue.objectType,
                        objectValue.authorDeviceId,
                        payloadHash,
                        objectValue.authSequence,
                    ),
                signingDigest = "",
                authorSignature = "",
            )
        val digest = signingDigest(unsigned)
        return unsigned.copy(
            signingDigest = digest,
            authorSignature = keyStore.signBase64(
                unsigned.authorDeviceId,
                signingMaterial(unsigned).toByteArray(Charsets.UTF_8),
            ),
        ).also(::validate)
    }

    fun validate(objectValue: SyncAuthProtocolObject) {
        if (objectValue.protocolVersion != SYNC_PROTOCOL_VERSION) {
            invalid("Unsupported Sync protocol version")
        }
        listOf(
            "authObjectId" to objectValue.authObjectId,
            "syncSpaceId" to objectValue.syncSpaceId,
            "authorDeviceId" to objectValue.authorDeviceId,
            "ownerDeviceId" to objectValue.ownerDeviceId,
            "payloadJson" to objectValue.payloadJson,
            "payloadHash" to objectValue.payloadHash,
            "signingDigest" to objectValue.signingDigest,
            "authorSignature" to objectValue.authorSignature,
        ).forEach { (name, value) ->
            if (value.isBlank()) invalid("$name must not be blank")
        }
        if (objectValue.authEpoch < 0L) invalid("authEpoch must be non-negative")
        if (objectValue.authSequence < 0L) invalid("authSequence must be non-negative")
        if (objectValue.targetDeviceId?.isBlank() == true) invalid("targetDeviceId must be blank or non-empty")

        val payload = canonicalJson(objectValue.payloadJson)
        if (payload != objectValue.payloadJson) invalid("payloadJson is not canonical")
        val previous = canonicalCoverage(objectValue.previousEpochFinalAcceptedPrefixByActorLane)
        if (
            canonicalJson(
                json.encodeToString(coverageSerializer, objectValue.previousEpochFinalAcceptedPrefixByActorLane),
            ) != previous
        ) {
            invalid("previousEpochFinalAcceptedPrefixByActorLane is not canonical")
        }
        objectValue.revokeCutoffByActorLane?.let { cutoff ->
            if (canonicalJson(json.encodeToString(coverageSerializer, cutoff)) != canonicalCoverage(cutoff)) {
                invalid("revokeCutoffByActorLane is not canonical")
            }
        }
        if (objectValue.payloadHash != SyncOperationCanonicalizer.sha256Hex(payload)) {
            invalid("AUTH payloadHash mismatch")
        }
        if (
            objectValue.authObjectId !=
                authObjectId(
                    objectValue.syncSpaceId,
                    objectValue.authEpoch,
                    objectValue.objectType,
                    objectValue.authorDeviceId,
                    objectValue.payloadHash,
                    objectValue.authSequence,
                )
        ) {
            invalid("AUTH authObjectId mismatch")
        }
        if (objectValue.signingDigest != signingDigest(objectValue)) {
            invalid("AUTH signingDigest mismatch")
        }
    }

    fun verify(
        objectValue: SyncAuthProtocolObject,
        publicKeySpkiBase64: String,
        keyStore: SyncDeviceSigningKeyStore,
    ): Boolean =
        runCatching {
            validate(objectValue)
            keyStore.verifyBase64(
                publicKeySpkiBase64,
                signingMaterial(objectValue).toByteArray(Charsets.UTF_8),
                objectValue.authorSignature,
            )
        }.getOrDefault(false)

    private fun canonicalCoverage(value: SyncCoverage): String {
        val normalized = linkedMapOf<String, Map<String, Long>>()
        value.forEach { (lane, actors) ->
            if (lane.isBlank()) invalid("Invalid AUTH coverage lane")
            val normalizedActors = linkedMapOf<String, Long>()
            actors.forEach { (actor, prefix) ->
                if (actor.isBlank() || prefix < 0L) invalid("Invalid AUTH coverage prefix for $lane/$actor")
                if (prefix > 0L) normalizedActors[actor] = prefix
            }
            if (normalizedActors.isNotEmpty()) normalized[lane] = normalizedActors
        }
        return canonicalJson(json.encodeToString(coverageSerializer, normalized))
    }

    /** Canonical signing material for OWNER_RECOVERY proof. */
    fun ownerRecoverySigningMaterial(
        syncSpaceId: String,
        authEpoch: Long,
        targetDeviceId: String,
        previousCut: Map<String, Map<String, Long>>,
    ): String =
        listOf(
            "ORIGREAD_OWNER_RECOVERY_PROOF_V1",
            framed("syncSpaceId", syncSpaceId),
            framed("authEpoch", authEpoch.toString()),
            framed("targetDeviceId", targetDeviceId),
            framed("previousEpochFinalAcceptedPrefixByActorLane", canonicalCoverage(previousCut)),
        ).joinToString("\n")

    /** 验证 OWNER_RECOVERY 的密码学恢复证明签名 */
    fun verifyOwnerRecoveryProof(
        syncSpaceId: String,
        authEpoch: Long,
        targetDeviceId: String,
        previousCut: Map<String, Map<String, Long>>,
        recoveryProofBase64: String,
        spaceRootPublicKeySpkiBase64: String,
        keyStore: SyncDeviceSigningKeyStore,
    ): Boolean =
        runCatching {
            val material = ownerRecoverySigningMaterial(syncSpaceId, authEpoch, targetDeviceId, previousCut)
            keyStore.verifyBase64(
                publicKeySpkiBase64 = spaceRootPublicKeySpkiBase64,
                material = material.toByteArray(Charsets.UTF_8),
                signatureBase64 = recoveryProofBase64,
            )
        }.getOrDefault(false)

    private fun canonicalJson(value: String): String =
        try {
            SyncOperationCanonicalizer.canonicalJson(value)
        } catch (error: Throwable) {
            throw SyncAuthWireValidationException(error.message ?: "Invalid AUTH JSON")
        }

    private fun StringBuilder.field(name: String, value: String) {
        append(name)
        append('=')
        append(value.toByteArray(Charsets.UTF_8).size)
        append(':')
        append(value)
        append('\n')
    }

    private fun StringBuilder.nullableField(name: String, value: String?) {
        if (value == null) {
            append(name)
            append("=-1:\n")
        } else {
            field(name, value)
        }
    }

    private fun framed(name: String, value: String): String =
        "$name=${value.toByteArray(Charsets.UTF_8).size}:$value"

    private fun invalid(message: String): Nothing = throw SyncAuthWireValidationException(message)

    private fun canonicalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(element.entries.sortedBy { it.key }.associate { (key, value) -> key to canonicalize(value) })
            is JsonArray -> JsonArray(element.map(::canonicalize))
            else -> element
        }
}
