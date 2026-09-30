package me.ash.reader.infrastructure.rsshub

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssHubCatalogAssetTest {
    @Test
    fun `内置公共实例目录包含版本来源和唯一地址`() {
        val file =
            listOf(
                File("src/main/assets/rsshub_instances.json"),
                File("app/src/main/assets/rsshub_instances.json"),
            ).first(File::exists)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val instances = requireNotNull(root["instances"]).jsonArray
        val urls =
            instances.map { item ->
                item.jsonObject.getValue("url").jsonPrimitive.content.trimEnd('/')
            }

        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.int)
        assertTrue(root.getValue("generatedAt").jsonPrimitive.content.isNotBlank())
        assertTrue(root.getValue("source").jsonPrimitive.content.startsWith("https://"))
        assertTrue(instances.size >= 8)
        assertEquals(urls.size, urls.distinct().size)
        assertTrue("https://rsshub.app" in urls)
        assertTrue(urls.all { it.startsWith("https://") || it.startsWith("http://") })

        val instanceObjects = instances.map { it.jsonObject }
        val official = instanceObjects.first { it.getValue("id").jsonPrimitive.content == "official" }
        val enabledIds =
            instanceObjects
                .filter { it.getValue("enabled").jsonPrimitive.boolean }
                .map { it.getValue("id").jsonPrimitive.content }
        assertTrue("official rsshub.app must not be an automatic default", !official.getValue("enabled").jsonPrimitive.boolean)
        assertTrue(enabledIds.size >= 4)
        assertEquals("isrss", enabledIds.first())
        assertTrue(enabledIds.take(5).containsAll(listOf("slarker", "isrss", "cups", "rssforever")))
    }

    @Test
    fun `内置目录为 schema2 且包含足量动态参数路由`() {
        val file =
            listOf(
                File("src/main/assets/rsshub_routes.json"),
                File("app/src/main/assets/rsshub_routes.json"),
            ).first(File::exists)
        val catalog = Json.decodeFromString<RssHubRouteCatalogData>(file.readText())

        val dynamicRoutes = catalog.routes.filter { it.sourcePathTemplate != null || it.sourceQueryTemplate != null }
        assertEquals(2, catalog.schemaVersion)
        assertEquals(catalog.routes.size, catalog.routeCount)
        assertTrue(catalog.routes.size > 5_000)
        assertTrue(dynamicRoutes.size > 500)
        assertTrue(
            catalog.routes.all { route ->
                route.host.matches(
                    Regex(
                        "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
                            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
                    )
                )
            }
        )
        assertTrue(
            dynamicRoutes.any { route ->
                route.host == "dianping.com" &&
                    route.sourcePathTemplate == "/member/:id" &&
                    route.target.contains(":id")
            }
        )
    }

    @Test
    fun `真实目录覆盖静态动态查询可选和约束路由形态`() {
        val file =
            listOf(
                File("src/main/assets/rsshub_routes.json"),
                File("app/src/main/assets/rsshub_routes.json"),
            ).first(File::exists)
        val routes = Json.decodeFromString<RssHubRouteCatalogData>(file.readText()).routes

        val staticRoutes = routes.count { it.sourcePathTemplate == null && it.sourceQueryTemplate == null }
        val pathDynamicRoutes = routes.count { ':' in it.sourcePathTemplate.orEmpty() }
        val queryRoutes = routes.count { !it.sourceQueryTemplate.isNullOrBlank() }
        val optionalRoutes = routes.count {
            '?' in (it.sourcePathTemplate.orEmpty() + it.sourceQueryTemplate.orEmpty() + it.target)
        }
        val constrainedRoutes = routes.count {
            '{' in (it.sourcePathTemplate.orEmpty() + it.target)
        }

        // 这些不是对某两个故障样本的特判，而是整个内置目录的形态下限。
        assertTrue(staticRoutes > 3_000)
        assertTrue(pathDynamicRoutes > 1_000)
        assertTrue(queryRoutes > 100)
        assertTrue(optionalRoutes > 400)
        assertTrue(constrainedRoutes > 30)
    }

    @Test
    fun `真实目录代表路由可从来源网址解析出正确逻辑路由`() {
        val file =
            listOf(
                File("src/main/assets/rsshub_routes.json"),
                File("app/src/main/assets/rsshub_routes.json"),
            ).first(File::exists)
        val routes = Json.decodeFromString<RssHubRouteCatalogData>(file.readText()).routes
        val instance = "https://rsshub.example.com"

        val cases =
            listOf(
                "https://sspai.com/matrix" to "/sspai/matrix",
                "https://movie.douban.com/coming" to "/douban/movie/coming",
                "https://telegram.org/blog" to "/telegram/blog",
                "https://s.weibo.com/top/summary" to "/weibo/search/hot",
                "https://space.bilibili.com/1161918898" to "/bilibili/user/dynamic/1161918898",
                "https://asianfanfics.com/browse/text_search?q=reader" to "/asianfanfics/text-search/reader",
                "https://199it.com/industry" to "/199it/industry",
            )

        cases.forEach { (sourceUrl, expectedPath) ->
            val matches = RssHubRouteMatcher.matchRoutes(sourceUrl, routes, instance, maxResults = 32)
            assertTrue(
                "$sourceUrl should resolve to $expectedPath",
                matches.any { it.resolved && it.feedUrl == "$instance$expectedPath" },
            )
        }
    }

    @Test
    fun `财联社首页从真实目录返回全部可解析首页路由`() {
        val file =
            listOf(
                File("src/main/assets/rsshub_routes.json"),
                File("app/src/main/assets/rsshub_routes.json"),
            ).first(File::exists)
        val catalog = Json.decodeFromString<RssHubRouteCatalogData>(file.readText())

        val matches =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://www.cls.cn/",
                routes = catalog.routes,
                instanceBaseUrl = "https://rsshub.example.com",
                maxResults = 8,
            ).filter(RssHubRouteMatch::resolved)

        assertTrue(matches.any { it.route.target == "/cls/hot" })
        assertTrue(matches.any { it.route.target == "/cls/telegraph" })
    }
}
