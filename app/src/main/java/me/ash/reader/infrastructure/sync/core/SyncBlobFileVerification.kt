package me.ash.reader.infrastructure.sync.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** 校验缓存只绑定不可变本地文件，文件身份、大小或修改时间变化时重新 hash。 */
internal class SyncBlobFileVerification {
    private val stamps = mutableMapOf<String, String>()
    data class Input(val hash: String, val file: File, val digest: () -> String, val force: Boolean = false)

    /** 一次传输的后续 Range 复用完整校验，重新从 offset=0 请求时强制重新检查。 */
    @Synchronized
    fun verify(input: Input): Boolean {
        val before = stamp(input.file)
        val key = input.file.absolutePath
        if (!input.force && stamps[key] == "${input.hash}:$before") return true
        if (input.digest() != input.hash || stamp(input.file) != before) {
            stamps.remove(key)
            return false
        }
        stamps[key] = "${input.hash}:$before"
        return true
    }

    /** 删除/恢复路径使旧校验代次失效。 */
    @Synchronized
    fun invalidate(file: File) { stamps.remove(file.absolutePath) }

    private fun stamp(file: File): String {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val status = android.system.Os.stat(file.absolutePath)
        return "${attributes.fileKey()}:${attributes.size()}:${attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)}:${status.st_ctime}"
    }
}
