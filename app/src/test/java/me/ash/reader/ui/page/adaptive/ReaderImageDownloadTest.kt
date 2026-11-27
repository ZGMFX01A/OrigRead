package me.ash.reader.ui.page.adaptive

import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class ReaderImageDownloadTest {
    @Test fun `IO failure calls failure without throwing`() = runBlocking {
        val error = IOException("disk full")
        var received: Throwable? = null
        executeDownloadImage({ Result.failure(error) }, { fail("unexpected success") }, { received = it })
        assertSame(error, received)
    }
    @Test fun `cancellation is rethrown without failure callback`() = runBlocking {
        val error = CancellationException("cancelled")
        try {
            executeDownloadImage({ Result.failure(error) }, { fail("unexpected success") }, { fail("unexpected failure callback") })
            fail("cancellation swallowed")
        } catch (received: CancellationException) { assertSame(error, received) }
    }
    @Test fun `success calls success`() = runBlocking {
        val uri = mock(Uri::class.java)
        var received: Uri? = null
        executeDownloadImage({ Result.success(uri) }, { received = it }, { fail("unexpected failure") })
        assertSame(uri, received)
    }
}
