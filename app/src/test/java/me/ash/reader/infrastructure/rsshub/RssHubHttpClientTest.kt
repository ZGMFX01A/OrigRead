package me.ash.reader.infrastructure.rsshub

import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class RssHubHttpClientTest {
    @Test
    fun `different rsshub instances use isolated connection pools while preserving base network stack`() {
        val base = OkHttpClient.Builder().build()
        val clients = RssHubHttpClients(base)

        val first = clients.forInstance("https://first.example.com")
        val firstAgain = clients.forInstance("https://first.example.com/")
        val second = clients.forInstance("https://second.example.com")

        assertSame(first, firstAgain)
        assertNotSame(base.connectionPool, first.connectionPool)
        assertNotSame(first.connectionPool, second.connectionPool)
        assertSame(base.dns, first.dns)
        assertSame(base.proxySelector, first.proxySelector)
        assertEquals(listOf(Protocol.HTTP_1_1), first.protocols)
        assertEquals(11_000, first.callTimeoutMillis)
        assertEquals(2, clients.cachedClientCount())
    }

    @Test
    fun `feed urls on the same origin reuse only that origin rsshub client`() {
        val clients = RssHubHttpClients(OkHttpClient())

        val firstRoute = clients.forUrl("https://hub.example.com/zhihu/hot")
        val secondRoute = clients.forUrl("https://hub.example.com/bilibili/user/dynamic/1")
        val anotherOrigin = clients.forUrl("https://other.example.com/zhihu/hot")

        assertSame(firstRoute, secondRoute)
        assertNotSame(firstRoute.connectionPool, anotherOrigin.connectionPool)
        assertEquals(2, clients.cachedClientCount())
    }
}
