package me.ash.reader.infrastructure.storage

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicJsonFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `atomic writes create parent replace content and clean staging files`() {
        val file = temp.root.resolve("nested/settings.json")
        AtomicJsonFileStore.writeAtomicText(file, "old")
        AtomicJsonFileStore.writeAtomicText(file, "新配置")
        assertEquals("新配置", file.readText())
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }

    @Test fun `concurrent locked access retains complete content`() {
        val file = temp.newFile("settings.json")
        val payloads = (0..3).map { it.toString().repeat(8192) }
        AtomicJsonFileStore.writeAtomicText(file, payloads[0])
        val pool = Executors.newFixedThreadPool(5)
        try {
            val writers = payloads.map { payload -> pool.submit { repeat(30) { AtomicJsonFileStore.writeAtomicText(file, payload) } } }
            val reader = pool.submit {
                repeat(500) {
                    // 与生产 Repository 一样让读取和替换共用锁；Windows 不保证可替换已打开的目标。
                    synchronized(AtomicJsonFileStore) {
                        assertTrue(String(Files.readAllBytes(file.toPath()), Charsets.UTF_8) in payloads)
                    }
                }
            }
            (writers + reader).forEach { it.get(20, TimeUnit.SECONDS) }
            assertFalse(temp.root.list()!!.any { ".tmp-" in it })
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun `corruption backup preserves exact bytes and original cause`() {
        val file = temp.newFile("settings.json").apply { writeText("{broken") }
        val cause = IllegalArgumentException("bad JSON")
        val error = assertThrows(IllegalStateException::class.java) {
            AtomicJsonFileStore.preserveCorruptAndThrow(file, "Settings", cause)
        }
        val backup = temp.root.listFiles()!!.single { ".corrupt-" in it.name }
        assertEquals("{broken", file.readText())
        assertArrayEquals(file.readBytes(), backup.readBytes())
        assertSame(cause, error.cause)
        assertTrue(error.message!!.contains(backup.absolutePath))
    }
}
