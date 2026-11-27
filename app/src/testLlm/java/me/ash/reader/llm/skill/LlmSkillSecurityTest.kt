package me.ash.reader.llm.skill

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LlmSkillSecurityTest {
    @Test
    fun `zip parser rejects entries escaping nested skill root`() {
        val dir = Files.createTempDirectory("origread-skill-zip").toFile()
        try {
            val context = mock<Context>()
            whenever(context.filesDir).thenReturn(dir)
            val repo = LlmSkillRepository(context)
            val bytes =
                zipBytes(
                    "my-skill/SKILL.md" to
                        """
                        ---
                        name: my-skill
                        description: Valid test skill
                        ---
                        Use references/info.md.
                        """.trimIndent(),
                    "other/scripts/evil.md" to "should never be accepted",
                )

            val error = assertThrows(LlmSkillFormatException::class.java) {
                repo.parseZip(bytes, "my-skill.zip")
            }
            assertTrue(error.message.orEmpty().contains("越界"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `validateBackupState rejects unsafe sourceFileName and resource path traversal`() {
        val dir = Files.createTempDirectory("origread-skill-backup").toFile()
        try {
            val context = mock<Context>()
            whenever(context.filesDir).thenReturn(dir)
            val repo = LlmSkillRepository(context)

            val unsafeSourceBackup =
                backupPayload(
                    sourceFileName = "../escape.md",
                    resourcePath = "references/safe.md",
                )
            assertThrows(IllegalArgumentException::class.java) {
                repo.validateBackupState(unsafeSourceBackup)
            }

            listOf(
                "../secret.md",
                "/etc/passwd.md",
                "references\\win.md",
                "scripts/run.md",
            ).forEach { unsafePath ->
                val payload =
                    backupPayload(
                        sourceFileName = null,
                        resourcePath = unsafePath,
                    )
                assertThrows("Expected rejection for resource path $unsafePath", IllegalArgumentException::class.java) {
                    repo.validateBackupState(payload)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun backupPayload(sourceFileName: String?, resourcePath: String): String =
        JSONObject()
            .put("version", 1)
            .put("bindings", JSONObject())
            .put(
                "skills",
                JSONArray().put(
                    JSONObject()
                        .put("id", "safe-skill")
                        .put("description", "Safe description")
                        .put("enabled", true)
                        .put("instructions", "Follow steps.")
                        .put("sourceFileName", sourceFileName ?: JSONObject.NULL)
                        .put(
                            "resources",
                            JSONArray().put(
                                JSONObject().put("path", resourcePath).put("content", "text")
                            ),
                        )
                ),
            )
            .toString()
}
