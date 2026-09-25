package me.ash.reader.infrastructure.sync.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncBlobTransferCoordinator @Inject constructor(
    private val blobState: SyncBlobStateService,
) {
    suspend fun upload(
        syncSpaceId: String,
        manifest: SyncBlobManifestWire,
        bytes: ByteArray,
        session: SyncEndpointSession,
        policyByLane: Map<String, String>,
        chunkBytes: Int = 1024 * 1024,
        now: Long = System.currentTimeMillis(),
    ): SyncBlobPersistedAckWire? {
        require(chunkBytes > 0)
        require(bytes.size.toLong() == manifest.totalBytes) { "Blob size does not match manifest" }
        require(sha256Hex(bytes) == manifest.hash) { "Blob content hash does not match manifest" }
        blobState.registerManifest(manifest, SyncBlobAvailabilityState.READY, now)
        blobState.markReadyVerified(manifest.hash, manifest.totalBytes, now)
        check(blobState.transferAllowed(syncSpaceId, manifest.hash, policyByLane)) {
            "Blob transfer is blocked by replication lane policy"
        }

        val remoteStatus = session.getBlobStatus(manifest.hash)
        var offset = 0
        var restart = false
        if (remoteStatus != null) {
            require(remoteStatus.hash == manifest.hash && remoteStatus.totalBytes == manifest.totalBytes) {
                "Remote Blob status does not match manifest"
            }
            require(remoteStatus.receivedBytes in 0..manifest.totalBytes) {
                "Remote Blob status has an invalid durable prefix"
            }
            if (remoteStatus.complete) {
                require(
                    remoteStatus.receivedBytes == manifest.totalBytes &&
                        remoteStatus.receivedPrefixSha256 == manifest.hash &&
                        !remoteStatus.replicaId.isNullOrBlank() &&
                        remoteStatus.persistedAt != null
                ) { "Remote completed Blob status is inconsistent" }
                val existingAck =
                    SyncBlobPersistedAckWire(
                        syncSpaceId = syncSpaceId,
                        hash = manifest.hash,
                        replicaId = checkNotNull(remoteStatus.replicaId),
                        totalBytes = manifest.totalBytes,
                        persistedAt = checkNotNull(remoteStatus.persistedAt),
                    )
                blobState.recordPersistedAck(existingAck)
                return existingAck
            }
            val prefixLength = remoteStatus.receivedBytes.toInt()
            val localPrefixHash = sha256Hex(bytes.copyOfRange(0, prefixLength))
            if (localPrefixHash == remoteStatus.receivedPrefixSha256) {
                offset = prefixLength
            } else {
                restart = true
            }
        }

        var finalAck: SyncBlobPersistedAckWire? = null
        var firstChunk = true
        do {
            val end = minOf(bytes.size, offset + chunkBytes)
            val isFinal = end == bytes.size
            val ack =
                session.pushBlob(
                    SyncBlobChunkWire(
                        hash = manifest.hash,
                        offset = offset.toLong(),
                        totalBytes = bytes.size.toLong(),
                        bytesBase64 = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)),
                        isFinal = isFinal,
                        restart = restart && firstChunk,
                    )
                )
            if (!isFinal && ack != null) error("BlobPersistedAck arrived before the final chunk")
            if (isFinal) finalAck = ack
            offset = end
            firstChunk = false
        } while (offset < bytes.size)
        if (manifest.durability == SyncBlobDurability.SYNC_DURABLE && finalAck == null) {
            error("SYNC_DURABLE Blob requires a durable replica acknowledgement")
        }
        if (finalAck != null) {
            require(finalAck.syncSpaceId == syncSpaceId && finalAck.hash == manifest.hash)
            blobState.recordPersistedAck(finalAck)
        }
        return finalAck
    }

    suspend fun fetch(
        syncSpaceId: String,
        manifest: SyncBlobManifestWire,
        session: SyncEndpointSession,
        policyByLane: Map<String, String>,
        persistVerified: suspend (ByteArray) -> Unit,
        chunkBytes: Long = 1024L * 1024L,
        now: Long = System.currentTimeMillis(),
    ): ByteArray {
        require(chunkBytes > 0)
        blobState.registerManifest(manifest, SyncBlobAvailabilityState.BLOB_MISSING, now)
        check(blobState.transferAllowed(syncSpaceId, manifest.hash, policyByLane)) {
            "Blob transfer is blocked by replication lane policy"
        }
        blobState.markFetching(manifest.hash, now)
        try {
            val output = ByteArrayOutputStream()
            var offset = 0L
            while (true) {
                val chunk = session.fetchBlob(manifest.hash, offset, chunkBytes)
                require(chunk.hash == manifest.hash) { "Fetched Blob hash identity mismatch" }
                require(chunk.offset == offset) { "Fetched Blob offset is not contiguous" }
                require(chunk.totalBytes == manifest.totalBytes) { "Fetched Blob size does not match manifest" }
                val bytes = Base64.getDecoder().decode(chunk.bytesBase64)
                require(bytes.isNotEmpty() || chunk.isFinal) { "Blob fetch made no progress" }
                output.write(bytes)
                offset += bytes.size
                require(offset <= manifest.totalBytes) { "Fetched Blob exceeds manifest size" }
                if (chunk.isFinal) {
                    require(offset == manifest.totalBytes) { "Final Blob chunk is incomplete" }
                    break
                }
            }
            val bytes = output.toByteArray()
            require(sha256Hex(bytes) == manifest.hash) { "Fetched Blob content hash mismatch" }
            persistVerified(bytes)
            blobState.markReadyVerified(manifest.hash, manifest.totalBytes, now)
            return bytes
        } catch (error: Throwable) {
            blobState.markFailed(manifest.hash, error.message ?: error::class.java.simpleName, now)
            throw error
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
