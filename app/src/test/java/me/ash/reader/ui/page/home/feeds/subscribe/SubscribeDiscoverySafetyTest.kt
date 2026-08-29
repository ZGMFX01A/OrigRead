package me.ash.reader.ui.page.home.feeds.subscribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubscribeDiscoverySafetyTest {
    @Test
    fun `stage timeout remains a normal probe failure so fallback can continue`() = runBlocking {
        val result =
            runSuspendCatching {
                withTimeout(1) {
                    delay(100)
                    "unreachable"
                }
            }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun `active cancellation is never swallowed by fallback wrapper`() = runBlocking {
        try {
            runSuspendCatching<String> { throw CancellationException("user cancelled") }
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
