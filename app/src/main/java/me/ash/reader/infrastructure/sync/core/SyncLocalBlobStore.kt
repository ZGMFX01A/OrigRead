package me.ash.reader.infrastructure.sync.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local verified Blob byte store.
 *
 * Business databases remain free to keep their own materialized copy. This store is the durable
 * hand-off point used by Sync before an Operation may advertise a local Blob as READY.
 */
@Singleton
class SyncLocalBlobStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val root = File(context.filesDir, "origread-sync/blobs-v1").apply { mkdirs() }

    @Synchronized
    fun putVerified(hash: String, bytes: ByteArray) {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Blob hash must be SHA-256 hex" }
        require(sha256Hex(bytes) == hash) { "Blob bytes do not match declared hash" }
        val target = File(root, hash)
        if (target.isFile) {
            val existing = target.readBytes()
            if (existing.size == bytes.size && sha256Hex(existing) == hash) return
        }
        val temp = File(root, "$hash.tmp-${System.nanoTime()}")
        try {
            temp.writeBytes(bytes)
            require(sha256Hex(temp.readBytes()) == hash) { "Persisted Blob verification failed" }
            if (target.exists() && !target.delete()) error("Unable to replace corrupt local Blob $hash")
            check(temp.renameTo(target)) { "Unable to atomically install local Blob $hash" }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun putUtf8Text(reference: SyncPayloadBlobRefWire, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() == reference.manifest.totalBytes) { "Blob text size does not match manifest" }
        putVerified(reference.manifest.hash, bytes)
    }

    @Synchronized
    fun readVerified(hash: String): ByteArray? {
        val file = File(root, hash)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (sha256Hex(bytes) != hash) {
            file.delete()
            return null
        }
        return bytes
    }

    @Synchronized
    fun remove(hash: String): Boolean = !File(root, hash).exists() || File(root, hash).delete()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
