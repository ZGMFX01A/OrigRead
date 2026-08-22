package me.ash.reader.infrastructure.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NotionShareRequestGuardTest {
    @Test
    fun `rejects a second request while the first request is in flight`() {
        val guard = NotionShareRequestGuard(nowMillis = { 1_000L })

        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())
    }

    @Test
    fun `keeps the debounce window after the request completes`() {
        var now = 1_000L
        val guard = NotionShareRequestGuard(nowMillis = { now })

        assertTrue(guard.tryAcquire())
        now += 10_000L
        guard.release()
        now += 1_499L
        assertFalse(guard.tryAcquire())
        now += 1L
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun `release allows a later request`() {
        var now = 1_000L
        val guard = NotionShareRequestGuard(nowMillis = { now })

        assertTrue(guard.tryAcquire())
        guard.release()
        now += 1_500L
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun `only one concurrent caller acquires the guard`() {
        val guard = NotionShareRequestGuard(nowMillis = { 1_000L })
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val executor = Executors.newFixedThreadPool(8)

        repeat(8) {
            executor.submit {
                start.await()
                results += guard.tryAcquire()
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, results.count { it })
    }
}
