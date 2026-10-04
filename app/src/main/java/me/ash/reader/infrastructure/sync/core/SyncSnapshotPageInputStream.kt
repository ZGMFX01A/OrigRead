package me.ash.reader.infrastructure.sync.core

import java.io.InputStream

/** 顺序读取已验签清单中的页面，让 UTF-8 解码器处理跨页字符与记录续段。 */
internal class SyncSnapshotPageInputStream(private val options: Options) : InputStream() {
    data class Options(val pageCount: Int, val loadPage: (Int) -> ByteArray)
    private var pageIndex = 0
    private var bytes = byteArrayOf()
    private var offset = 0

    /** 一次只载入一个页面，读完立即释放，空页不会伪造额外记录。 */
    private fun ensureAvailable(): Boolean {
        while (offset == bytes.size && pageIndex < options.pageCount) {
            bytes = options.loadPage(pageIndex++)
            offset = 0
        }
        return offset < bytes.size
    }

    override fun read(): Int = if (ensureAvailable()) bytes[offset++].toInt() and 0xff else -1

    /** 单次批量读取只复制当前页剩余字节，调用方可以持续消费跨页记录。 */
    override fun read(destination: ByteArray, start: Int, length: Int): Int {
        require(start >= 0 && length >= 0 && start <= destination.size - length)
        if (length == 0) return 0
        if (!ensureAvailable()) return -1
        val count = minOf(length, bytes.size - offset)
        bytes.copyInto(destination, start, offset, offset + count)
        offset += count
        return count
    }
}
