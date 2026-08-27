package me.ash.reader.infrastructure.editionsync

import java.util.Date
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditionSyncFoundationTest {
    @Test
    fun `peer package resolver is symmetric for github and google play`() {
        assertEquals(
            "me.ash.reader.llm",
            EditionSyncContract.peerPackageName(edition = "standard", channel = "github"),
        )
        assertEquals(
            "me.ash.reader",
            EditionSyncContract.peerPackageName(edition = "llm", channel = "github"),
        )
        assertEquals(
            "me.ash.reader.llm.google.play",
            EditionSyncContract.peerPackageName(edition = "standard", channel = "googlePlay"),
        )
        assertEquals(
            "me.ash.reader.google.play",
            EditionSyncContract.peerPackageName(edition = "llm", channel = "googlePlay"),
        )
    }

    @Test
    fun `aes gcm direct transfer decrypts exactly and rejects tampering`() {
        val original = "OrigRead Standard ↔ LLM\n${Date(1_000)}".toByteArray()
        val encrypted = EditionSyncCrypto.encrypt(original)

        assertNotEquals(original.toList(), encrypted.ciphertext.toList())
        assertArrayEquals(
            original,
            EditionSyncCrypto.decrypt(
                encrypted.ciphertext,
                encrypted.keyBase64,
                encrypted.ivBase64,
            ),
        )

        val tampered = encrypted.ciphertext.copyOf().also { it[it.lastIndex] = (it.last() xor 0x01) }
        assertThrows(Exception::class.java) {
            EditionSyncCrypto.decrypt(tampered, encrypted.keyBase64, encrypted.ivBase64)
        }
    }

    @Test
    fun `bundle serialization preserves portable reading state without llm only fields`() {
        val bundle =
            EditionSyncBundle(
                sourceEdition = "standard",
                sourcePackageName = "me.ash.reader",
                sourceVersion = "1.0.0",
                createdAtEpochMillis = 123L,
                configurationBackupJson = "{\"schemaVersion\":1}",
                configurationBackupPassword = "one-time-password",
                reading =
                    EditionSyncReadingSnapshot(
                        sourceAccount =
                            EditionSyncAccountSnapshot(
                                name = "Local",
                                typeId = 1,
                                securityKey = null,
                                updatedAtEpochMillis = 100L,
                                lastArticleKey = "article-key",
                            ),
                        groups = listOf(EditionSyncGroupSnapshot("default", "Default", true)),
                        feeds =
                            listOf(
                                EditionSyncFeedSnapshot(
                                    key = "feed-key",
                                    name = "Feed",
                                    icon = null,
                                    url = "https://example.com/feed.xml",
                                    groupKey = "default",
                                    groupIsDefault = true,
                                    isNotification = false,
                                    isFullContent = true,
                                    isBrowser = false,
                                    sourceType = "RSS",
                                )
                            ),
                        articles =
                            listOf(
                                EditionSyncArticleSnapshot(
                                    key = "article-key",
                                    feedKey = "feed-key",
                                    dateEpochMillis = 10L,
                                    title = "Article",
                                    author = "Author",
                                    rawDescription = "body",
                                    shortDescription = "short",
                                    fullContent = "full",
                                    img = null,
                                    link = "https://example.com/article",
                                    isUnread = false,
                                    isStarred = true,
                                    isReadLater = true,
                                    updatedAtEpochMillis = 20L,
                                )
                            ),
                        archivedArticles = emptyList(),
                    ),
            )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(bundle)
        val decoded = json.decodeFromString<EditionSyncBundle>(encoded)

        assertEquals(bundle, decoded)
        // Common sync protocol must never silently grow LLM-only Chat/MCP/Skill payloads.
        assertEquals(false, encoded.contains("conversation", ignoreCase = true))
        assertEquals(false, encoded.contains("mcp", ignoreCase = true))
        assertEquals(false, encoded.contains("skill", ignoreCase = true))
    }

    @Test
    fun `schema v1 ignores additive unknown fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded =
            """
            {
              "schemaVersion":1,
              "appName":"OrigRead",
              "sourceEdition":"standard",
              "sourcePackageName":"me.ash.reader",
              "sourceVersion":"1.0.0",
              "createdAtEpochMillis":123,
              "configurationBackupJson":"{}",
              "configurationBackupPassword":"password",
              "futureTopLevel":"ignored",
              "reading":{
                "sourceAccount":{
                  "name":"OrigRead",
                  "typeId":1,
                  "securityKey":null,
                  "updatedAtEpochMillis":null,
                  "lastArticleKey":null,
                  "futureAccount":true
                },
                "groups":[{"key":"default","name":"Default","isDefault":true}],
                "feeds":[],
                "articles":[],
                "archivedArticles":[],
                "futureReading":{"nested":1}
              }
            }
            """.trimIndent()

        val decoded = json.decodeFromString<EditionSyncBundle>(encoded)

        assertEquals(1, decoded.schemaVersion)
        assertEquals("standard", decoded.sourceEdition)
        assertEquals("OrigRead", decoded.reading.sourceAccount.name)
    }

    @Test
    fun `large multi megabyte bundle survives serialization encryption and decryption`() {
        val body = "OrigRead large payload ".repeat(64)
        val articles =
            (0 until 3_000).map { index ->
                EditionSyncArticleSnapshot(
                    key = "article-$index",
                    feedKey = "feed-key",
                    dateEpochMillis = index.toLong(),
                    title = "Article $index",
                    author = "Author",
                    rawDescription = body,
                    shortDescription = "short-$index",
                    fullContent = body,
                    img = null,
                    link = "https://example.com/article/$index",
                    isUnread = index % 2 == 0,
                    isStarred = index % 3 == 0,
                    isReadLater = index % 5 == 0,
                    updatedAtEpochMillis = index.toLong() + 1,
                )
            }
        val bundle =
            EditionSyncBundle(
                sourceEdition = "standard",
                sourcePackageName = "me.ash.reader",
                sourceVersion = "1.0.0",
                createdAtEpochMillis = 123L,
                configurationBackupJson = "{\"schemaVersion\":1}",
                configurationBackupPassword = "one-time-password",
                reading =
                    EditionSyncReadingSnapshot(
                        sourceAccount =
                            EditionSyncAccountSnapshot(
                                name = "Local",
                                typeId = 1,
                                securityKey = null,
                                updatedAtEpochMillis = null,
                                lastArticleKey = null,
                            ),
                        groups = listOf(EditionSyncGroupSnapshot("default", "Default", true)),
                        feeds =
                            listOf(
                                EditionSyncFeedSnapshot(
                                    key = "feed-key",
                                    name = "Feed",
                                    icon = null,
                                    url = "https://example.com/feed.xml",
                                    groupKey = "default",
                                    groupIsDefault = true,
                                    isNotification = false,
                                    isFullContent = false,
                                    isBrowser = false,
                                    sourceType = "RSS",
                                )
                            ),
                        articles = articles,
                        archivedArticles = emptyList(),
                    ),
            )
        val json = Json { encodeDefaults = true }
        val plaintext = json.encodeToString(bundle).toByteArray()

        assertTrue("测试负载必须达到多 MB，避免只覆盖小 Bundle", plaintext.size > 4 * 1024 * 1024)
        val encrypted = EditionSyncCrypto.encrypt(plaintext)
        val decrypted = EditionSyncCrypto.decrypt(encrypted.ciphertext, encrypted.keyBase64, encrypted.ivBase64)
        val decoded = json.decodeFromString<EditionSyncBundle>(decrypted.toString(Charsets.UTF_8))

        assertArrayEquals(plaintext, decrypted)
        assertEquals(3_000, decoded.reading.articles.size)
        assertEquals("https://example.com/article/2999", decoded.reading.articles.last().link)
        assertEquals(true, decoded.reading.articles[2997].isStarred)
    }

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}
