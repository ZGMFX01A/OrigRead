package me.ash.reader.infrastructure.sync.core

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile

/** 文件内容与目录项使用独立持久化屏障；任何 I/O 错误必须阻止耐久 ACK。 */
internal object SyncDurableFiles {
    /** 关闭流不代表断电耐久，发布前显式同步已写入的全部字节。 */
    fun sync(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    /** Android 本地 POSIX 文件系统支持目录 fsync，发布后同步目录项。 */
    fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Durability barrier target is not a directory" }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    /** 仅接受同文件系统的原子发布，不用直接复制到最终路径掩盖 rename 失败。 */
    fun publish(staging: File, target: File) {
        sync(staging)
        Os.rename(staging.absolutePath, target.absolutePath)
        syncDirectory(checkNotNull(target.parentFile))
    }
}
