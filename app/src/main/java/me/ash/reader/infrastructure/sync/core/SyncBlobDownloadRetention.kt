package me.ash.reader.infrastructure.sync.core

import java.io.File

/** 下载前缀沿用 LAN 暂存的保留期与容量，正式内容不参与回收。 */
internal object SyncBlobDownloadRetention {
    /** 断网后允许跨会话恢复的保留时长。 */
    private const val RETENTION_MS = 24L * 60 * 60 * 1000
    /** 单对象及全局临时容量沿用已有 LAN 资源预算。 */
    private const val OBJECT_BYTES = 512L * 1024 * 1024
    private const val TOTAL_BYTES = 1024L * 1024 * 1024

    /** 过期前缀显式删除，容量超限明确报错，不降级为无检查点下载。 */
    fun prepare(file: File, totalBytes: Long) {
        require(totalBytes in 0..OBJECT_BYTES) { "BLOB_STAGING_LIMIT: download exceeds temporary object capacity" }
        val files = checkNotNull(file.parentFile?.listFiles()) { "Cannot enumerate partial Blob downloads" }
        var used = 0L
        for (candidate in files) {
            if (!candidate.name.matches(Regex("[a-f0-9]{64}\\.fetch-[a-f0-9]{64}")) || candidate == file) continue
            val marker = File(candidate.absolutePath + ".checkpoint")
            val modified = if (marker.exists()) marker.lastModified() else candidate.lastModified()
            if (modified < System.currentTimeMillis() - RETENTION_MS) {
                check(candidate.delete()) { "Cannot remove expired Blob prefix" }
                if (marker.exists()) check(marker.delete()) { "Cannot remove expired Blob checkpoint" }
            } else {
                val reserved = if (marker.exists()) org.json.JSONObject(marker.readText()).getLong("totalBytes") else candidate.length()
                check(reserved >= 0L) { "BLOB_PARTIAL_CORRUPTED: invalid reserved download size" }
                used += reserved
            }
        }
        check(used + totalBytes <= TOTAL_BYTES) { "BLOB_STAGING_LIMIT: partial downloads exceed temporary capacity" }
    }
}
