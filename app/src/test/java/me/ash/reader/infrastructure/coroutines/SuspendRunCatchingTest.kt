package me.ash.reader.infrastructure.coroutines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class SuspendRunCatchingTest {
    @Test fun `cancellation identity is preserved and business failures remain visible`() = runTest {
        val cancellation = CancellationException("stop")
        try { suspendRunCatching { throw cancellation }; fail("swallowed") }
        catch (error: CancellationException) { assertSame(cancellation, error) }
        val failure = IllegalStateException("business")
        assertSame(failure, suspendRunCatching { throw failure }.exceptionOrNull())
        assertNull(suspendRunCatching<String?> { null }.getOrThrow())
    }
}
