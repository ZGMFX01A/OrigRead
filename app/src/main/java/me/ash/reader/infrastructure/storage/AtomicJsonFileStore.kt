package me.ash.reader.infrastructure.storage

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

object AtomicJsonFileStore {
    // Windows 同目标并行 rename 可能互相拒绝；串行提交也保护跨 Repository 实例的写入。
    @Synchronized
    fun writeAtomicText(target: File, content: String) {
        val path = target.absoluteFile.toPath()
        Files.createDirectories(path.parent)
        val staging = path.resolveSibling("${target.name}.tmp-${UUID.randomUUID()}")
        try {
            Files.write(staging, content.toByteArray(Charsets.UTF_8))
            try {
                Files.move(staging, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(staging, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    fun preserveCorruptAndThrow(target: File, label: String, cause: Throwable): Nothing {
        val backup = target.absoluteFile.resolveSibling("${target.name}.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        val error = IllegalStateException("$label 损坏：${target.absolutePath}；损坏备份：${backup.path}", cause)
        try {
            Files.copy(target.toPath(), backup.toPath())
        } catch (copyFailure: Exception) {
            error.addSuppressed(copyFailure)
            throw IllegalStateException("$label 损坏，备份失败；原文件保留于 ${target.absolutePath}", error)
        }
        throw error
    }
}
