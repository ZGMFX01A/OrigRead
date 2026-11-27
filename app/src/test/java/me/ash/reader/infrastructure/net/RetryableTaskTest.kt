package me.ash.reader.infrastructure.net

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryableTaskTest {
    @Test fun `nullable result is successful without retries`() = runTest {
        var calls = 0
        val result = withRetries<String?> { calls++; null }
        assertTrue(result is RetryableTaskResult.Success)
        assertNull(result.getOrThrow())
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun `external cancellation is rethrown unchanged`() = runTest {
        val cancellation = CancellationException("caller")
        try {
            withRetries(RetryConfig(shouldRetry = { true })) { throw cancellation }
            fail("cancellation swallowed")
        } catch (error: CancellationException) { assertSame(cancellation, error) }
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun `non retryable failure does not wait`() = runTest {
        val error = IllegalStateException("stop")
        val result = withRetries(RetryConfig(shouldRetry = { false })) { throw error }
        assertSame(error, result.exceptionOrNull())
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun `only transitions to another attempt wait`() = runTest {
        var calls = 0
        val result = withRetries(RetryConfig(attempts = 3, initialDelay = 100, maxDelay = 1000)) {
            calls++; error("failure")
        }
        assertEquals(3, calls)
        assertEquals(300L, testScheduler.currentTime)
        assertEquals(3, (result as RetryableTaskResult.Failure).attemptExceptions.size)
    }
    @Test fun `per attempt timeout retries but outer timeout cancels`() = runTest {
        val result = withRetries(RetryConfig(attempts = 2, timeoutPerAttempt = 100, initialDelay = 10)) { delay(200) }
        assertTrue(result.exceptionOrNull() is TimeoutException)
        assertEquals(210L, testScheduler.currentTime)
        try {
            withTimeout(50) { withRetries(RetryConfig(timeoutPerAttempt = 100)) { delay(200) } }
            fail("outer timeout swallowed")
        } catch (expected: TimeoutCancellationException) { assertEquals(260L, testScheduler.currentTime) }
    }
}
