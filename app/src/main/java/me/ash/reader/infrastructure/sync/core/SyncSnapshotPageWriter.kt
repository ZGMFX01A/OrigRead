package me.ash.reader.infrastructure.sync.core

import java.io.ByteArrayOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 写入目标只负责记录唯一性与页面持久化，不持有完整业务快照。 */
interface SyncSnapshotPageSink {
    fun writeRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Boolean
    /** 私有固定捕获接口必须明确实现，不能退回锁内 hash 路径。 */
    fun captureRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Boolean =
        throw UnsupportedOperationException("Immutable Snapshot capture is not implemented")
    fun writePage(input: SyncSnapshotPageWrite): String
    /** 即时存储没有在途记录；批量存储必须在结束页面前提交队列。 */
    fun flushRecords() = Unit
}

data class SyncSnapshotPageWrite(val snapshotBundleId: String, val lane: String, val index: Int, val bytes: ByteArray)

/** 按原始 UTF-8 字节切页，一条大记录可跨页，避免 HTTP 整条记录体积上限。 */
class SyncSnapshotPageWriter(private val options: Options) {
    data class Options(val snapshotBundleId: String, val sink: SyncSnapshotPageSink, val deferPages: Boolean = false,
        val recordsAlreadyIndexed: Boolean = false)
    private data class LaneBuffer(
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
        val hashes: MutableList<String> = mutableListOf(),
        var count: Long = 0,
    )
    private val lanes = linkedMapOf<String, LaneBuffer>()
    private val json = Json { encodeDefaults = true }
    private val encoding = SyncSnapshotUtf8Encoder()
    /** 固定来源捕获只写紧凑私有事实，原签名操作随后按轻索引分组关联。 */
    internal val deferred get() = options.deferPages

    /** 先去重完整候选身份，再把规范记录连续输出到当前 lane 的页面。 */
    fun append(lane: String, kind: String, value: JsonObject) {
        val record = SyncSnapshotRecord(kind, SyncSnapshotRecordCodec.key(kind, value), value)
        if (options.deferPages) { options.sink.captureRecord(options.snapshotBundleId, lane, record); return }
        if (!options.recordsAlreadyIndexed && !options.sink.writeRecord(options.snapshotBundleId, lane, record)) return
        appendIndexed(lane, sequenceOf(SyncOperationCanonicalizer.canonicalJson(json.encodeToString(record))))
    }

    /** 已提交索引只输出完整规范记录，不重复 SQL 写入或解码来源对象。 */
    internal fun appendIndexed(lane: String, fragments: Sequence<String>) {
        lanes.getOrPut(lane) { LaneBuffer() }.count++
        SyncSnapshotTextFragments.write(fragments) { text -> encoding.write(text) { bytes, size -> appendBytes(lane, bytes, size) } }
    }

    /** 固定小块保持原有页面字节边界，大单条不截断也不创建完整字节数组。 */
    private fun appendBytes(lane: String, bytes: ByteArray, size: Int) {
        val buffer = lanes.getValue(lane)
        var offset = 0
        while (offset < size) {
            val length = minOf(size - offset, SNAPSHOT_PAGE_BYTES - buffer.bytes.size())
            buffer.bytes.write(bytes, offset, length)
            offset += length
            if (buffer.bytes.size() == SNAPSHOT_PAGE_BYTES) flush(lane)
        }
    }

    /** 空 lane 也写一张空页；frontier 来自同一个 Genesis 固定视图。 */
    fun finish(frontiers: Map<String, String>): List<SyncSnapshotLanePages> {
        options.sink.flushRecords()
        return frontiers.toSortedMap().map { (lane, frontier) ->
            val buffer = lanes.getOrPut(lane) { LaneBuffer() }
            if (buffer.bytes.size() > 0 || buffer.hashes.isEmpty()) flush(lane)
            SyncSnapshotLanePages(lane, frontier, buffer.hashes.toList(), buffer.count)
        }
    }

    /** 落盘后立即清空字节缓冲，只保留轻量页摘要供最终清单签名。 */
    private fun flush(lane: String) {
        val buffer = lanes.getValue(lane)
        val hash = options.sink.writePage(SyncSnapshotPageWrite(options.snapshotBundleId, lane, buffer.hashes.size, buffer.bytes.toByteArray()))
        buffer.hashes.add(hash)
        buffer.bytes.reset()
    }
}
