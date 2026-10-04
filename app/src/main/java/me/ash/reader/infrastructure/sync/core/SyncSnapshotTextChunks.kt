package me.ash.reader.infrastructure.sync.core

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** SQLite TEXT 按原始 UTF-8 字节读取，避免 text substr 将内嵌 NUL 当成字符串结束。 */
internal object SyncSnapshotTextChunks {
    /** 单块只占少量 CursorWindow，完整记录大小不由该值限制。 */
    private const val READ_BYTES = 32 * 1024

    /** 持续解码完整当前记录；UTF-8 字符跨块时保留解码状态，非法字节明确失败。 */
    fun read(load: (Long, Int) -> ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return InputStreamReader(Chunks(load), decoder).use { it.readText() }
    }

    /** load 只接收 SQL 参数值，SQL 标识符由调用方固定的本地 schema 决定。 */
    private class Chunks(private val load: (Long, Int) -> ByteArray) : InputStream() {
        private var position = 1L
        private var bytes = byteArrayOf()
        private var offset = 0
        private var ended = false

        /** 空块是数据库真正的文本末尾；其余字节包括 NUL 全部保留。 */
        private fun availableChunk(): Boolean {
            if (offset < bytes.size) return true
            if (ended) return false
            bytes = load(position, READ_BYTES)
            position += bytes.size
            offset = 0
            ended = bytes.isEmpty()
            return !ended
        }

        override fun read(): Int = if (availableChunk()) bytes[offset++].toInt() and BYTE_MASK else -1

        /** 标准 InputStream 缓冲区写入只复制当前块，避免逐字节数据库访问。 */
        override fun read(destination: ByteArray, start: Int, length: Int): Int {
            require(start >= 0 && length >= 0 && start <= destination.size - length)
            if (length == 0) return 0
            if (!availableChunk()) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(destination, start, offset, offset + count)
            offset += count
            return count
        }
    }

    /** InputStream 单字节返回值必须是无符号值，负值专门表示 EOF。 */
    private const val BYTE_MASK = 0xff
}
