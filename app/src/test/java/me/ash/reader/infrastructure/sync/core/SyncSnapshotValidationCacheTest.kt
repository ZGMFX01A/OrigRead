package me.ash.reader.infrastructure.sync.core

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncSnapshotValidationCacheTest {
    @Test fun revisionOrRootChangeInvalidatesProof() {
        val db: SQLiteDatabase = mock()
        val revision = AtomicLong(1)
        whenever(db.rawQuery(any<String>(), any<Array<String>>())).thenAnswer {
            mock<Cursor>().also { c ->
                whenever(c.moveToFirst()).thenReturn(true)
                whenever(c.getLong(0)).thenReturn(revision.get())
            }
        }
        val cache = SyncSnapshotValidationCache(db)
        assertFalse(cache.reusable("b", "root"))
        cache.mark("b", "root")
        assertTrue(cache.reusable("b", "root"))
        assertFalse(cache.reusable("b", "other-root"))
        revision.incrementAndGet()
        assertFalse(cache.reusable("b", "root"))
    }

    @Test fun readerWaitingForDatabaseDoesNotBlockTransactionOwnerMarkingProof() {
        val db: SQLiteDatabase = mock()
        val dbLock = ReentrantLock()
        val readerEnteredQuery = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        whenever(db.rawQuery(any<String>(), any<Array<String>>())).thenAnswer {
            if (Thread.currentThread().name == "snapshot-proof-reader") readerEnteredQuery.countDown()
            dbLock.withLock {
                mock<Cursor>().also { c ->
                    whenever(c.moveToFirst()).thenReturn(true)
                    whenever(c.getLong(0)).thenReturn(1L)
                }
            }
        }
        val cache = SyncSnapshotValidationCache(db)
        cache.mark("b", "root")
        thread(name = "snapshot-transaction-owner", isDaemon = true) {
            try {
                dbLock.withLock {
                    thread(name = "snapshot-proof-reader", isDaemon = true) {
                        try { cache.reusable("b", "root") } catch (e: Throwable) { failure.set(e) }
                        finally { done.countDown() }
                    }
                    check(readerEnteredQuery.await(3, TimeUnit.SECONDS))
                    cache.mark("b", "root")
                }
            } catch (e: Throwable) { failure.set(e) }
            finally { done.countDown() }
        }
        assertTrue("cache monitor must not be held while waiting for SQLite", done.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("concurrent proof check failed", it) }
    }
}
