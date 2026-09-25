package me.ash.reader.infrastructure.db

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.sync.core.SyncLocalLanePolicy
import me.ash.reader.infrastructure.sync.core.SyncReplicationLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticleFilterTransactionTest {
    @Test
    fun lanePolicySurvivesServiceRecreationWithoutAffectingOtherSpaces() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(app, AndroidDatabase::class.java).build()
        try {
            SyncLocalLanePolicy(database).set("first", SyncReplicationLane.AI_HISTORY, "PAUSED")
            assertEquals(mapOf("AI_HISTORY" to "PAUSED"), SyncLocalLanePolicy(database).read("first"))
            assertEquals(emptyMap<String, String>(), SyncLocalLanePolicy(database).read("second"))
            SyncLocalLanePolicy(database).set("first", SyncReplicationLane.AI_HISTORY, "ENABLED")
            assertEquals(mapOf("AI_HISTORY" to "ENABLED"), SyncLocalLanePolicy(database).read("first"))
        } finally { database.close() }
    }

    @Test
    fun legacyImportAndOutboxRollbackUseOneAuthoritativeStore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "filter-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
        val database = Room.inMemoryDatabaseBuilder(app, AndroidDatabase::class.java).build()
        try {
            ArticleFilterRepository(context).add("legacy")
            val repository = ArticleFilterRepository(context, database)
            assertEquals(listOf("legacy"), repository.getAll().map { it.keyword })
            val failure = runCatching {
                database.withTransaction {
                    repository.add("uncommitted")
                    assertEquals(2, repository.getAll().size)
                    error("Outbox insertion failed")
                }
            }
            assertTrue(failure.isFailure)
            assertEquals(listOf("legacy"), repository.getAll().map { it.keyword })
            database.withTransaction { repository.add("committed") }
            ArticleFilterRepository(context).add("obsolete-file-change")
            assertEquals(listOf("legacy", "committed"), ArticleFilterRepository(context, database).getAll().map { it.keyword })
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
