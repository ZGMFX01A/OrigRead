package me.ash.reader.infrastructure.sync.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_IN_MEMORY_BLOB_BYTES = 16L * 1024L * 1024L

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
        onChunkSent: suspend (Int) -> Unit = {},
    ): SyncBlobPersistedAckWire? {
        require(chunkBytes > 0)
        require(manifest.totalBytes <= MAX_IN_MEMORY_BLOB_BYTES) {
            "Blob exceeds the in-memory upload limit; use uploadFile"
        }
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
            onChunkSent(end - offset)
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

    /**
     * File-backed upload path used by R11 LAN/Server sessions. Only one negotiated chunk is held
     * in memory at a time; resume prefix hashing is also streamed from disk.
     */
    suspend fun uploadFile(
        syncSpaceId: String,
        manifest: SyncBlobManifestWire,
        file: File,
        session: SyncEndpointSession,
        policyByLane: Map<String, String>,
        chunkBytes: Int = 1024 * 1024,
        now: Long = System.currentTimeMillis(),
        onChunkSent: suspend (Int) -> Unit = {},
    ): SyncBlobPersistedAckWire? {
        require(chunkBytes > 0)
        require(file.isFile) { "Local Blob file is missing" }
        require(file.length() == manifest.totalBytes) { "Blob size does not match manifest" }
        require(sha256FileHex(file) == manifest.hash) { "Blob content hash does not match manifest" }
        blobState.registerManifest(manifest, SyncBlobAvailabilityState.READY, now)
        blobState.markReadyVerified(manifest.hash, manifest.totalBytes, now)
        check(blobState.transferAllowed(syncSpaceId, manifest.hash, policyByLane)) {
            "Blob transfer is blocked by replication lane policy"
        }

        val remoteStatus = session.getBlobStatus(manifest.hash)
        var offset = 0L
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
                val existingAck = SyncBlobPersistedAckWire(
                    syncSpaceId = syncSpaceId,
                    hash = manifest.hash,
                    replicaId = checkNotNull(remoteStatus.replicaId),
                    totalBytes = manifest.totalBytes,
                    persistedAt = checkNotNull(remoteStatus.persistedAt),
                )
                blobState.recordPersistedAck(existingAck)
                return existingAck
            }
            if (sha256FileHex(file, remoteStatus.receivedBytes) == remoteStatus.receivedPrefixSha256) {
                offset = remoteStatus.receivedBytes
            } else {
                restart = true
            }
        }

        var finalAck: SyncBlobPersistedAckWire? = null
        var firstChunk = true
        RandomAccessFile(file, "r").use { input ->
            do {
                val remaining = manifest.totalBytes - offset
                val size = minOf(chunkBytes.toLong(), remaining).toInt()
                val bytes = ByteArray(size)
                if (size > 0) {
                    input.seek(offset)
                    input.readFully(bytes)
                }
                val end = offset + size
                val isFinal = end == manifest.totalBytes
                val ack = session.pushBlob(
                    SyncBlobChunkWire(
                        hash = manifest.hash,
                        offset = offset,
                        totalBytes = manifest.totalBytes,
                        bytesBase64 = Base64.getEncoder().encodeToString(bytes),
                        isFinal = isFinal,
                        restart = restart && firstChunk,
                    )
                )
                onChunkSent(size)
                if (!isFinal && ack != null) error("BlobPersistedAck arrived before the final chunk")
                if (isFinal) finalAck = ack
                offset = end
                firstChunk = false
            } while (offset < manifest.totalBytes)
        }
        if (manifest.durability == SyncBlobDurability.SYNC_DURABLE && finalAck == null) {
            error("SYNC_DURABLE Blob requires a durable replica acknowledgement")
        }
        finalAck?.let { ack ->
            require(ack.syncSpaceId == syncSpaceId && ack.hash == manifest.hash)
            blobState.recordPersistedAck(ack)
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
        onChunkReceived: suspend (Int) -> Unit = {},
    ): ByteArray {
        require(chunkBytes > 0)
        require(manifest.totalBytes <= MAX_IN_MEMORY_BLOB_BYTES) {
            "Blob exceeds the in-memory fetch limit; use fetchToFile"
        }
        blobState.registerManifest(manifest, SyncBlobAvailabilityState.BLOB_MISSING, now)
        check(blobState.transferAllowed(syncSpaceId, manifest.hash, policyByLane)) {
            "Blob transfer is blocked by replication lane policy"
        }
        blobState.markFetching(manifest.hash, now)
        try {
            if (manifest.totalBytes == 0L) {
                val empty = ByteArray(0)
                require(sha256Hex(empty) == manifest.hash) { "Fetched empty Blob hash mismatch" }
                persistVerified(empty)
                blobState.markReadyVerified(manifest.hash, 0L, now)
                return empty
            }
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
                onChunkReceived(bytes.size)
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

    suspend fun fetchToFile(
        syncSpaceId: String,
        manifest: SyncBlobManifestWire,
        session: SyncEndpointSession,
        policyByLane: Map<String, String>,
        stagedFile: File,
        persistVerified: suspend (File) -> Unit,
        chunkBytes: Long = 1024L * 1024L,
        now: Long = System.currentTimeMillis(),
        onChunkReceived: suspend (Int) -> Unit = {},
    ) {
        require(chunkBytes > 0)
        blobState.registerManifest(manifest, SyncBlobAvailabilityState.BLOB_MISSING, now)
        check(blobState.transferAllowed(syncSpaceId, manifest.hash, policyByLane)) {
            "Blob transfer is blocked by replication lane policy"
        }
        blobState.markFetching(manifest.hash, now)
        try {
            stagedFile.parentFile?.mkdirs()
            if (manifest.totalBytes == 0L) {
                require(sha256Hex(ByteArray(0)) == manifest.hash) { "Fetched empty Blob hash mismatch" }
                RandomAccessFile(stagedFile, "rw").use { output ->
                    output.setLength(0L)
                    output.fd.sync()
                }
                persistVerified(stagedFile)
                blobState.markReadyVerified(manifest.hash, 0L, now)
                return
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            RandomAccessFile(stagedFile, "rw").use { output ->
                output.setLength(0L)
                while (true) {
                    val chunk = session.fetchBlob(manifest.hash, offset, chunkBytes)
                    require(chunk.hash == manifest.hash) { "Fetched Blob hash identity mismatch" }
                    require(chunk.offset == offset) { "Fetched Blob offset is not contiguous" }
                    require(chunk.totalBytes == manifest.totalBytes) { "Fetched Blob size does not match manifest" }
                    val bytes = Base64.getDecoder().decode(chunk.bytesBase64)
                    require(bytes.isNotEmpty() || chunk.isFinal) { "Blob fetch made no progress" }
                    if (bytes.isNotEmpty()) {
                        output.seek(offset)
                        output.write(bytes)
                        digest.update(bytes)
                    }
                    onChunkReceived(bytes.size)
                    offset += bytes.size
                    require(offset <= manifest.totalBytes) { "Fetched Blob exceeds manifest size" }
                    if (chunk.isFinal) {
                        require(offset == manifest.totalBytes) { "Final Blob chunk is incomplete" }
                        break
                    }
                }
                output.fd.sync()
            }
            require(stagedFile.length() == manifest.totalBytes) { "Fetched Blob file size mismatch" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == manifest.hash) { "Fetched Blob content hash mismatch" }
            persistVerified(stagedFile)
            blobState.markReadyVerified(manifest.hash, manifest.totalBytes, now)
        } catch (error: Throwable) {
            if (stagedFile.exists()) stagedFile.delete()
            blobState.markFailed(manifest.hash, error.message ?: error::class.java.simpleName, now)
            throw error
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256FileHex(file: File, limit: Long = file.length()): String {
        require(limit in 0..file.length()) { "Blob hash prefix is outside the file" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = limit
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) error("Blob file ended before the requested prefix")
                if (read == 0) continue
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
