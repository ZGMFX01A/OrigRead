package me.ash.reader.infrastructure.sync.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
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
    private val verification = SyncBlobFileVerification()
    private val root = File(context.filesDir, "origread-sync/blobs-v1").apply { mkdirs() }

    /** Blob 卷的持久存储代次；丢卷重新创建后旧回执不再描述当前存储。 */
    val storageGeneration: String = File(root, ".storage-generation").let { marker ->
        if (!marker.exists()) me.ash.reader.infrastructure.util.AtomicUtf8File.write(marker, java.util.UUID.randomUUID().toString())
        marker.readText().trim().also { check(it.isNotBlank()) { "Blob storage generation is corrupted" } }
    }

    @Synchronized
    fun putVerified(hash: String, bytes: ByteArray) {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Blob hash must be SHA-256 hex" }
        require(sha256Hex(bytes) == hash) { "Blob bytes do not match declared hash" }
        val target = File(root, hash)
        if (target.isFile) {
            if (target.length() == bytes.size.toLong() && sha256Hex(target) == hash) {
                SyncDurableFiles.sync(target); SyncDurableFiles.syncDirectory(root)
                return
            }
        }
        val temp = File(root, "$hash.tmp-${System.nanoTime()}")
        try {
            temp.writeBytes(bytes)
            require(sha256Hex(temp) == hash) { "Persisted Blob verification failed" }
            verification.invalidate(target)
            SyncDurableFiles.publish(temp, target)
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

    fun getRoot(): File = root

    fun createStagingFile(hash: String): File {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Blob hash must be SHA-256 hex" }
        return File(root, "$hash.fetch")
    }

    fun getBlobFile(hash: String): File? {
        val file = File(root, hash)
        return if (file.isFile) file else null
    }

    fun verifyFile(hash: String, file: File = File(root, hash), force: Boolean = false): Boolean {
        if (!hash.matches(Regex("[a-f0-9]{64}")) || !file.isFile) return false
        return verification.verify(SyncBlobFileVerification.Input(hash, file, { sha256Hex(file) }, force))
    }

    fun hashFile(file: File): String {
        require(file.isFile) { "Blob file is missing" }
        return sha256Hex(file)
    }

    /**
     * Installs an already-staged file without loading the whole Blob into memory.
     * The staged file must live on the same filesystem as the Blob root.
     */
    @Synchronized
    fun installVerifiedFile(hash: String, stagedFile: File): Long {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Blob hash must be SHA-256 hex" }
        require(stagedFile.isFile) { "Staged Blob file is missing" }
        require(sha256Hex(stagedFile) == hash) { "Staged Blob bytes do not match declared hash" }
        val target = File(root, hash)
        if (target.isFile && verifyFile(hash, target)) {
            SyncDurableFiles.sync(target); SyncDurableFiles.syncDirectory(root)
            if (stagedFile.absolutePath != target.absolutePath) stagedFile.delete()
            return target.length()
        }
        verification.invalidate(target)
        SyncDurableFiles.publish(stagedFile, target)
        require(sha256Hex(target) == hash) { "Persisted Blob verification failed" }
        return target.length()
    }

    @Synchronized
    fun readVerifiedRange(hash: String, offset: Long, length: Long): ByteArray? {
        val file = File(root, hash)
        if (!file.isFile) return null
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (offset >= raf.length()) return ByteArray(0)
            val actualLen = minOf(length, raf.length() - offset).toInt()
            val buf = ByteArray(actualLen)
            raf.seek(offset)
            raf.readFully(buf)
            return buf
        }
    }

    @Synchronized
    fun remove(hash: String): Boolean {
        val file = File(root, hash)
        verification.invalidate(file)
        return !file.exists() || file.delete().also { if (it) SyncDurableFiles.syncDirectory(root) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
