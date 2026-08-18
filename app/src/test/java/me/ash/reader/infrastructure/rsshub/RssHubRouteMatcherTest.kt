package me.ash.reader.infrastructure.rsshub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssHubRouteMatcherTest {
    private val routes =
        listOf(
            RssHubRouteDefinition(
                id = "cls-telegraph",
                name = "电报",
                host = "cls.cn",
                pathPrefix = "/telegraph",
                target = "/cls/telegraph",
            ),
            RssHubRouteDefinition(
                id = "cls-hot",
                name = "热门文章排行榜",
                host = "cls.cn",
                pathPrefix = "/",
                target = "/cls/hot",
            ),
        )

    @Test
    fun `优先返回路径更精确的路由`() {
        val result =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://www.cls.cn/telegraph",
                routes = routes,
                instanceBaseUrl = "https://rsshub.example.com/",
            )

        assertEquals("电报", result.first().route.name)
        assertEquals("https://rsshub.example.com/cls/telegraph", result.first().feedUrl)
    }

    @Test
    fun `财联社首页可直接匹配热门文章路由`() {
        val result =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://www.cls.cn/",
                routes = routes,
                instanceBaseUrl = "https://rsshub.example.com/",
            )

        assertTrue(result.any { it.route.name == "热门文章排行榜" && it.feedUrl == "https://rsshub.example.com/cls/hot" })
    }

    @Test
    fun `不匹配其他域名`() {
        val result =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://example.com/telegraph",
                routes = routes,
                instanceBaseUrl = "https://rsshub.example.com",
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `实例按最近成功自定义和默认顺序去重`() {
        val result =
            RssHubSettingsRepository.orderInstances(
                "https://backup.example.com/",
                "backup.example.com",
                RssHubResolver.DEFAULT_INSTANCE,
            )

        assertEquals(
            listOf("https://backup.example.com", RssHubResolver.DEFAULT_INSTANCE),
            result,
        )
    }

    @Test
    fun `默认实例列表包含用户要求的公共实例`() {
        val urls = RssHubSettingsRepository.defaultInstances().map { it.url }

        assertTrue("https://rsshub.app" in urls)
        assertTrue("https://rsshub.umzzz.com" in urls)
        assertTrue("https://rsshub-balancer.virworks.moe" in urls)
        assertEquals(16, urls.size)
    }

    @Test
    fun `从路径提取参数并安全编码到 RSSHub 地址`() {
        val dynamicRoute =
            RssHubRouteDefinition(
                id = "dianping-user",
                name = "用户点评",
                host = "dianping.com",
                pathPrefix = "/member",
                target = "/dianping/user/:id",
                sourcePathTemplate = "/member/:id",
            )

        val result =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://www.dianping.com/member/用户-123",
                routes = listOf(dynamicRoute),
                instanceBaseUrl = "https://rsshub.example.com",
            ).single()

        assertTrue(result.resolved)
        assertEquals("用户-123", result.parameters["id"])
        assertEquals(
            "https://rsshub.example.com/dianping/user/%E7%94%A8%E6%88%B7-123",
            result.feedUrl,
        )
    }

    @Test
    fun `支持查询参数和多个动态候选`() {
        val routes =
            listOf(
                RssHubRouteDefinition(
                    id = "query-one",
                    name = "查询一",
                    host = "example.com",
                    pathPrefix = "/channel",
                    target = "/example/channel/:id",
                    sourcePathTemplate = "/channel",
                    sourceQueryTemplate = "id=:id",
                ),
                RssHubRouteDefinition(
                    id = "query-two",
                    name = "查询二",
                    host = "example.com",
                    pathPrefix = "/channel",
                    target = "/example/alternate/:id",
                    sourcePathTemplate = "/channel",
                    sourceQueryTemplate = "id=:id",
                ),
            )

        val result =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://example.com/channel?id=42",
                routes = routes,
                instanceBaseUrl = "https://rsshub.example.com",
            )

        assertEquals(2, result.size)
        assertTrue(result.all(RssHubRouteMatch::resolved))
        assertEquals(setOf("42"), result.mapNotNull { it.parameters["id"] }.toSet())
    }

    @Test
    fun `目标参数无法从输入网址获得时返回补充提示且不生成请求地址`() {
        val route =
            RssHubRouteDefinition(
                id = "missing-app-id",
                name = "应用评论",
                host = "app.example.com",
                pathPrefix = "/",
                target = "/app/comments/:country/:appId",
                sourcePathTemplate = "/",
            )

        val result =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://app.example.com/",
                routes = listOf(route),
                instanceBaseUrl = "https://rsshub.example.com",
            ).single()

        assertFalse(result.resolved)
        assertNull(result.feedUrl)
        assertEquals(listOf("country", "appId"), result.missingParameters)
    }

    @Test
    fun `来源路径或查询缺少必填参数时返回补充提示`() {
        val pathRoute =
            RssHubRouteDefinition(
                id = "missing-path",
                name = "缺少用户 ID",
                host = "example.com",
                pathPrefix = "/user",
                target = "/example/user/:id",
                sourcePathTemplate = "/user/:id",
            )
        val queryRoute =
            RssHubRouteDefinition(
                id = "missing-query",
                name = "缺少频道 ID",
                host = "example.com",
                pathPrefix = "/channel",
                target = "/example/channel/:id",
                sourcePathTemplate = "/channel",
                sourceQueryTemplate = "id=:id",
            )

        val missingPath =
            RssHubRouteMatcher.matchRoutes(
                "https://example.com/user",
                listOf(pathRoute),
                "https://rsshub.example.com",
            ).single()
        val missingQuery =
            RssHubRouteMatcher.matchRoutes(
                "https://example.com/channel",
                listOf(queryRoute),
                "https://rsshub.example.com",
            ).single()

        assertFalse(missingPath.resolved)
        assertEquals(listOf("id"), missingPath.missingParameters)
        assertFalse(missingQuery.resolved)
        assertEquals(listOf("id"), missingQuery.missingParameters)
    }

    @Test
    fun `拒绝路径注入和超长参数`() {
        val route =
            RssHubRouteDefinition(
                id = "unsafe",
                name = "不安全参数",
                host = "example.com",
                pathPrefix = "/user",
                target = "/example/user/:id",
                sourcePathTemplate = "/user/:id",
            )

        val encodedSlash =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://example.com/user/a%2Fb",
                routes = listOf(route),
                instanceBaseUrl = "https://rsshub.example.com",
            )
        val tooLong =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://example.com/user/${"a".repeat(300)}",
                routes = listOf(route),
                instanceBaseUrl = "https://rsshub.example.com",
            )

        assertTrue(encodedSlash.isEmpty())
        assertTrue(tooLong.isEmpty())
    }

    @Test
    fun `可选参数缺失时从目标路径安全省略`() {
        val route =
            RssHubRouteDefinition(
                id = "optional",
                name = "可选分类",
                host = "example.com",
                pathPrefix = "/posts",
                target = "/example/posts/:category?",
                sourcePathTemplate = "/posts/:category?",
            )

        val result =
            RssHubRouteMatcher.matchRoutes(
                inputUrl = "https://example.com/posts",
                routes = listOf(route),
                instanceBaseUrl = "https://rsshub.example.com",
            ).single()

        assertTrue(result.resolved)
        assertEquals("https://rsshub.example.com/example/posts", result.feedUrl)
    }

    @Test
    fun `安全执行数字和枚举类型约束但不执行任意正则`() {
        assertTrue(RssHubParameterConstraint.matches("123", """\d+"""))
        assertFalse(RssHubParameterConstraint.matches("abc", """\d+"""))
        assertTrue(RssHubParameterConstraint.matches("hot", "hot|new"))
        assertFalse(RssHubParameterConstraint.matches("other", "hot|new"))

        val numericRoute =
            RssHubRouteDefinition(
                id = "numeric",
                name = "数字 ID",
                host = "example.com",
                pathPrefix = "/user",
                target = """/example/user/:id{\d+}""",
                sourcePathTemplate = """/user/:id{\d+}""",
            )
        val enumRoute =
            RssHubRouteDefinition(
                id = "enum",
                name = "固定类型",
                host = "example.com",
                pathPrefix = "/list",
                target = "/example/list/:type{hot|new}",
                sourcePathTemplate = "/list/:type{hot|new}",
            )

        assertTrue(
            RssHubRouteMatcher.matchRoutes(
                "https://example.com/user/123",
                listOf(numericRoute),
                "https://rsshub.example.com",
            ).single().resolved
        )
        assertTrue(
            RssHubRouteMatcher.matchRoutes(
                "https://example.com/user/abc",
                listOf(numericRoute),
                "https://rsshub.example.com",
            ).isEmpty()
        )
        assertTrue(
            RssHubRouteMatcher.matchRoutes(
                "https://example.com/list/hot",
                listOf(enumRoute),
                "https://rsshub.example.com",
            ).single().resolved
        )
        assertTrue(
            RssHubRouteMatcher.matchRoutes(
                "https://example.com/list/other",
                listOf(enumRoute),
                "https://rsshub.example.com",
            ).isEmpty()
        )
    }
}
