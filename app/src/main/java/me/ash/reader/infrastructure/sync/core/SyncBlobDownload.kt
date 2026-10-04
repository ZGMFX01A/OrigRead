package me.ash.reader.infrastructure.sync.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.util.AtomicUtf8File

/** 持久下载任务只保留已 flush 的前缀，绑定 Space/hash/长度后跨断网与重启续传。 */
internal class SyncBlobDownload(private val input: Input) : AutoCloseable {
    data class Input(val staging: File, val space: String, val manifest: SyncBlobManifestWire)
    @Serializable private data class Checkpoint(val space: String, val hash: String, val totalBytes: Long, val prefix: Long, val prefixHash: String)
    val file = File(input.staging.absolutePath + "-" + SyncOperationCanonicalizer.sha256Hex(
        "${input.space}\n${input.manifest.hash}\n${input.manifest.totalBytes}"))
    private val marker = File(file.absolutePath + ".checkpoint")
    private val output = RandomAccessFile(file.also { SyncBlobDownloadRetention.prepare(it, input.manifest.totalBytes) }, "rw")
    private val digest = MessageDigest.getInstance("SHA-256")
    var offset: Long = 0L
        private set

    init {
        try {
            val checkpoint = AtomicUtf8File.readOrNull(marker)?.let { Json.decodeFromString<Checkpoint>(it) }
            offset = restore(checkpoint)
            output.setLength(offset)
            append(ByteArray(0))
        } catch (error: Throwable) {
            // 检查点/前缀损坏必须报告；清除无效输入，下一轮显式重试才重新下载。
            output.close(); file.delete(); marker.delete()
            throw error
        }
    }

    /** 每个完整分块先持久化字节，再发布检查点，不把未提交尾部当作续传进度。 */
    fun append(bytes: ByteArray) {
        require(offset + bytes.size <= input.manifest.totalBytes) { "Fetched Blob exceeds manifest size" }
        output.seek(offset); output.write(bytes); output.fd.sync()
        digest.update(bytes); offset += bytes.size
        AtomicUtf8File.write(marker, Json.encodeToString(Checkpoint(input.space, input.manifest.hash,
            input.manifest.totalBytes, offset, prefixHash())))
        SyncDurableFiles.syncDirectory(checkNotNull(marker.parentFile))
    }

    /** 最终全对象校验不能被前缀检查点替代；发布成功才删除恢复任务。 */
    fun verify() {
        if (offset != input.manifest.totalBytes || prefixHash() != input.manifest.hash) {
            marker.delete()
            error("Fetched Blob content hash or size mismatch")
        }
        output.fd.sync()
    }

    override fun close() = output.close()
    fun complete() { marker.delete() }

    /** 已提交前缀仅重读一次，损坏和截断均显式失败。 */
    private fun restore(checkpoint: Checkpoint?): Long {
        if (checkpoint == null) return 0L
        require(checkpoint.space == input.space && checkpoint.hash == input.manifest.hash &&
            checkpoint.totalBytes == input.manifest.totalBytes && checkpoint.prefix in 0..checkpoint.totalBytes &&
            output.length() >= checkpoint.prefix) { "BLOB_PARTIAL_CORRUPTED: invalid checkpoint" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var position = 0L
        while (position < checkpoint.prefix) {
            val count = output.read(buffer, 0, minOf(buffer.size.toLong(), checkpoint.prefix - position).toInt())
            check(count > 0) { "BLOB_PARTIAL_CORRUPTED: prefix truncated" }
            digest.update(buffer, 0, count); position += count
        }
        require(prefixHash() == checkpoint.prefixHash) { "BLOB_PARTIAL_CORRUPTED: prefix checksum mismatch" }
        return position
    }

    /** SHA-256 状态克隆只生成摘要，不重复扫描已接收文件。 */
    private fun prefixHash(): String = (digest.clone() as MessageDigest).digest().joinToString("") { "%02x".format(it) }
}
