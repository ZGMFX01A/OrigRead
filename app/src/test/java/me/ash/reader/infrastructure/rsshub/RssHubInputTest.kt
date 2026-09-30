package me.ash.reader.infrastructure.rsshub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssHubInputTest {
    @Test
    fun `encoded URLs are preserved in queries and transform route segments`() {
        val input = "rsshub://github/issue/DIYgod/RSSHub?filter_link=https%3A%2F%2Fgithub.com"
        val parsed = requireNotNull(RssHubInputParser.parseExplicit(input))
        assertEquals("/github/issue/DIYgod/RSSHub?filter_link=https%3A%2F%2Fgithub.com", parsed.routePath)
        val route = "/rsshub/transform/json/https%3A%2F%2Fapi.github.com%2Frepos%2Ftest%2Freleases/title=News&itemTitle=name"
        assertEquals(route, RssHubInputParser.parseExplicit("rsshub:/$route")?.routePath)
        assertEquals(route, RssHubInputParser.parseExplicit("https://rsshub.app$route")?.routePath)
    }

    @Test
    fun `rsshub scheme becomes logical route without https rewriting`() {
        val input = "rsshub://bilibili/user/dynamic/1161918898"
        val parsed = requireNotNull(RssHubInputParser.parseExplicit(input))

        assertEquals("/bilibili/user/dynamic/1161918898", parsed.routePath)
        assertEquals(input, parsed.originalInput)
        assertNull(parsed.preferredInstance)
    }

    @Test
    fun `official instance url becomes logical route and preferred instance`() {
        val input = "https://rsshub.app/zhihu/hot"
        val parsed = requireNotNull(RssHubInputParser.parseExplicit(input))

        assertEquals("/zhihu/hot", parsed.routePath)
        assertEquals("https://rsshub.app", parsed.preferredInstance)
        assertEquals(input, parsed.originalInput)
    }

    @Test
    fun `custom instance with base path extracts route relative to configured root`() {
        val parsed =
            requireNotNull(
                RssHubInputParser.parseExplicit(
                    "https://hub.example.com/rsshub/bilibili/user/video/2267573",
                    listOf("https://hub.example.com/rsshub"),
                )
            )

        assertEquals("/bilibili/user/video/2267573", parsed.routePath)
        assertEquals("https://hub.example.com/rsshub", parsed.preferredInstance)
        assertEquals(
            "https://hub.example.com/rsshub/bilibili/user/video/2267573",
            RssHubInputParser.buildFeedUrl(requireNotNull(parsed.preferredInstance), parsed.routePath),
        )
    }

    @Test
    fun `health endpoint and ordinary website are not explicit rsshub routes`() {
        assertNull(RssHubInputParser.parseExplicit("https://rsshub.app/healthz"))
        assertNull(RssHubInputParser.parseExplicit("https://rsshub.app/favicon.ico"))
        assertNull(RssHubInputParser.parseExplicit("https://www.bilibili.com/video/BV1xx"))
        assertFalse(RssHubInputParser.normalizeRoutePath("//evil.example/path") != null)
        assertTrue(RssHubInputParser.routeFamily("/bilibili/user/dynamic/1161918898") == "bilibili/user/dynamic")
    }

    @Test
    fun `logical route preserves query parameters`() {
        val parsed =
            requireNotNull(
                RssHubInputParser.parseExplicit(
                    "rsshub://github/trending/daily?language=Kotlin&since=weekly"
                )
            )

        assertEquals(
            "/github/trending/daily?language=Kotlin&since=weekly",
            parsed.routePath,
        )
        assertEquals("github/trending/daily", RssHubInputParser.routeFamily(parsed.routePath))
    }

    @Test
    fun `configured instance root without route is not subscribable`() {
        assertNull(
            RssHubInputParser.parseExplicit(
                "https://hub.example.com/rsshub",
                listOf("https://hub.example.com/rsshub"),
            )
        )
    }

    @Test
    fun `unsafe encoded or traversal routes are rejected`() {
        assertNull(RssHubInputParser.normalizeRoutePath("/github/%5Cadmin"))
        assertNull(RssHubInputParser.normalizeRoutePath("/github/../admin"))
        assertNull(RssHubInputParser.normalizeRoutePath("/github/%2e%2E/admin"))
        assertNull(RssHubInputParser.normalizeRoutePath("/github/.%2e/admin"))
        assertNull(RssHubInputParser.normalizeRoutePath("//github/trending"))
    }

    @Test
    fun `scheme completion preserves logical routes and identifies official URLs`() {
        listOf("rsshub.app/zhihu/hot", "//rsshub.app/zhihu/hot", "HTTPS://RSSHUB.APP/zhihu/hot")
            .forEach { input ->
                assertEquals("/zhihu/hot", RssHubInputParser.parseExplicit(input)?.routePath)
                assertEquals("https://rsshub.app", RssHubInputParser.parseExplicit(input)?.preferredInstance)
            }
    }

    @Test
    fun `most specific configured base wins regardless of settings order`() {
        val root = "https://hub.example.com"
        val nested = "$root/rsshub"
        listOf(listOf(root, nested), listOf(nested, root)).forEach { instances ->
            val parsed = requireNotNull(RssHubInputParser.parseExplicit("$nested/zhihu/hot", instances))
            assertEquals("/zhihu/hot", parsed.routePath)
            assertEquals(nested, parsed.preferredInstance)
            assertNull(RssHubInputParser.parseExplicit(nested, instances))
        }
    }

    @Test
    fun `custom port unicode routes and query encoding round trip`() {
        val base = "http://[::1]:1200/prefix"
        val route = "/example/%E4%B8%AD%E6%96%87?filter=one%26two&limit=5&filter=three"
        val parsed = requireNotNull(RssHubInputParser.parseExplicit(base + route, listOf(base)))
        assertEquals(route, parsed.routePath)
        assertEquals(base + route, RssHubInputParser.buildFeedUrl(base, parsed.routePath))
    }
}
