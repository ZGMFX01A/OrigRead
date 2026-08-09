package me.ash.reader.infrastructure.rsshub

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssHubCatalogAssetTest {
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
}
