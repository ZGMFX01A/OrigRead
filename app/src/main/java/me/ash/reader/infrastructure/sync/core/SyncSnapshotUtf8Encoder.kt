package me.ash.reader.infrastructure.sync.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** 大记录使用固定编码缓冲，避免每次生成完整 UTF-8 字节副本。 */
internal class SyncSnapshotUtf8Encoder {
    private val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private val bytes = ByteBuffer.allocate(ENCODING_BYTES)

    /** 代理对由 CharsetEncoder 完整处理，字节页可在任意 UTF-8 字节位置切分。 */
    fun write(text: String, consume: (ByteArray, Int) -> Unit) {
        encoder.reset()
        val chars = CharBuffer.wrap(text)
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            bytes.clear()
            val result = encoder.encode(chars, bytes, true)
            if (result.isError) result.throwException()
            consume(bytes.array(), bytes.position())
            if (result.isUnderflow) break
        }
        bytes.clear()
        val result = encoder.flush(bytes)
        if (result.isError) result.throwException()
        check(result.isUnderflow) { "SNAPSHOT_ENCODING_FAILED: UTF-8 flush exceeds bounded buffer" }
        if (bytes.position() > 0) consume(bytes.array(), bytes.position())
    }

    companion object {
        /** 单条超大记录仍逐块取消，只保留一个小型编码缓冲。 */
        private const val ENCODING_BYTES = 32 * 1024
    }
}
