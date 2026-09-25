package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.db.AndroidDatabase

class SyncOperationIntegrityException(message: String) : IllegalStateException(message)

/** Signs only already-built local operations. Network transports must consume SIGNED rows only. */
@Singleton
class SyncOperationSigner @Inject constructor(
    private val database: AndroidDatabase,
    private val keyStore: SyncDeviceSigningKeyStore,
) {
    suspend fun signPending(
        syncSpaceId: String,
        limit: Int = 100,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val device = checkNotNull(database.syncRuntimeDao().findDeviceIdentity()) {
            "Device identity must exist before signing Sync operations"
        }
        val pending =
            database.syncOperationDao().listByStatus(
                syncSpaceId = syncSpaceId,
                buildStatus = SyncOperationBuildStatus.AWAITING_SIGNATURE.name,
                limit = limit,
            )

        pending.forEach { operation ->
            validateLocalOperation(operation, device.deviceId)
            val material = SyncOperationCanonicalizer.signingMaterial(operation).toByteArray(Charsets.UTF_8)
            val signature = keyStore.signBase64(device.deviceId, material)

            // Self-check catches broken key/provider state before the row becomes transport-visible.
            val publicKey = keyStore.publicKeySpkiBase64(device.deviceId)
            check(keyStore.verifyBase64(publicKey, material, signature)) {
                "Generated Sync operation signature failed local verification"
            }

            database.withTransaction {
                val changed =
                    database.syncOperationDao().markSigned(
                        operationId = operation.operationId,
                        expectedSigningDigest = operation.signingDigest,
                        authorSignature = signature,
                        updatedAt = now,
                    )
                check(changed == 1) {
                    "Sync operation ${operation.operationId} changed while it was being signed"
                }
            }
        }
        return pending.size
    }

    fun publicKeySpkiBase64(deviceId: String): String = keyStore.publicKeySpkiBase64(deviceId)

    fun verify(
        publicKeySpkiBase64: String,
        operation: SyncOperationEntity,
    ): Boolean {
        val signature = operation.authorSignature ?: return false
        if (operation.buildStatus != SyncOperationBuildStatus.SIGNED.name) return false
        if (!hasValidCanonicalIntegrity(operation)) return false
        val material = SyncOperationCanonicalizer.signingMaterial(operation).toByteArray(Charsets.UTF_8)
        return keyStore.verifyBase64(publicKeySpkiBase64, material, signature)
    }

    private fun validateLocalOperation(
        operation: SyncOperationEntity,
        currentDeviceId: String,
    ) {
        if (operation.authorDeviceId != currentDeviceId) {
            throw SyncOperationIntegrityException(
                "Refusing to sign operation ${operation.operationId} authored by another device"
            )
        }
        if (!hasValidCanonicalIntegrity(operation)) {
            throw SyncOperationIntegrityException(
                "Canonical payload/signing digest mismatch for operation ${operation.operationId}"
            )
        }
    }

    private fun hasValidCanonicalIntegrity(operation: SyncOperationEntity): Boolean =
        runCatching {
            val canonicalPayload = SyncOperationCanonicalizer.canonicalJson(operation.payloadJson)
            val canonicalCausal = SyncOperationCanonicalizer.canonicalJson(operation.causalContextJson)
            val canonicalDependencies = SyncOperationCanonicalizer.canonicalJson(operation.dependencyDotsJson)
            operation.payloadJson == canonicalPayload &&
                operation.causalContextJson == canonicalCausal &&
                operation.dependencyDotsJson == canonicalDependencies &&
                operation.payloadHash == SyncOperationCanonicalizer.sha256Hex(canonicalPayload) &&
                operation.signingDigest == SyncOperationCanonicalizer.signingDigest(operation)
        }.getOrDefault(false)
}
