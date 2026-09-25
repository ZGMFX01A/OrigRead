package me.ash.reader.infrastructure.util

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Small durable UTF-8 file primitive for user-authored configuration.
 *
 * AtomicFile keeps the previous complete file recoverable until the replacement is fully written.
 * A missing file is distinct from a malformed existing file: callers may treat missing as an empty
 * configuration, while decode errors must propagate instead of silently becoming "delete all".
 */
object AtomicUtf8File {
    private fun newFile(file: File) = File(file.absolutePath + ".new")

    private fun backupFile(file: File) = File(file.absolutePath + ".bak")

    /**
     * A leftover backup means the previous write never reached its commit point. Restore it before
     * reading; a leftover .new file is an uncommitted replacement and can be discarded.
     */
    private fun recover(file: File) {
        val backup = backupFile(file)
        if (backup.exists()) {
            if (file.exists() && !file.delete()) {
                throw IOException("Couldn't discard incomplete atomic file ${file.absolutePath}")
            }
            if (!backup.renameTo(file)) {
                throw IOException("Couldn't restore atomic backup ${backup.absolutePath}")
            }
        }
        newFile(file).takeIf(File::exists)?.delete()
    }

    @Synchronized
    fun readOrNull(file: File): String? {
        recover(file)
        return try {
            file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    @Synchronized
    fun write(file: File, content: String) {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Couldn't create directory ${parent.absolutePath}")
            }
        }
        recover(file)
        val pending = newFile(file)
        val backup = backupFile(file)
        if (pending.exists() && !pending.delete()) {
            throw IOException("Couldn't discard stale atomic temp file ${pending.absolutePath}")
        }

        try {
            FileOutputStream(pending).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }

            val hadBase = file.exists()
            if (hadBase && !file.renameTo(backup)) {
                throw IOException("Couldn't stage atomic backup ${file.absolutePath}")
            }
            if (!pending.renameTo(file)) {
                if (hadBase) backup.renameTo(file)
                throw IOException("Couldn't commit atomic file ${file.absolutePath}")
            }
            if (backup.exists() && !backup.delete()) {
                throw IOException("Couldn't finalize atomic file ${file.absolutePath}")
            }
        } catch (error: Throwable) {
            pending.delete()
            if (backup.exists()) {
                file.delete()
                backup.renameTo(file)
            }
            throw error
        }
    }
}
