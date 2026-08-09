package me.ash.reader.infrastructure.content

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class FullContentFailureClassifierTest {
    @Test
    fun `dynamic shell should be classified as dynamic content`() {
        val html = "<html><body><div id=\"app\"></div><noscript>Please enable JavaScript</noscript></body></html>"
        assertEquals(
            FullContentFailureReason.DYNAMIC_CONTENT,
            FullContentFailureClassifier.classifyHtml(html),
        )
    }

    @Test
    fun `verification page should be classified as restricted`() {
        assertEquals(
            FullContentFailureReason.ACCESS_RESTRICTED,
            FullContentFailureClassifier.classifyHtml("<h1>Verify you are human</h1>"),
        )
    }

    @Test
    fun `network exception should keep a stable reason`() {
        assertEquals(
            FullContentFailureReason.NETWORK,
            FullContentFailureClassifier.classifyThrowable(SocketTimeoutException()),
        )
    }
}
