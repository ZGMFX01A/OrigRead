package me.ash.reader.infrastructure.website

import java.net.URI
import java.net.URLDecoder
import org.jsoup.nodes.Element

/** 自动 DOM 文章链接的语义过滤规则，避免把作者、标签、搜索和登录页面识别成文章。 */
object ArticleLinkHeuristics {
    private val blockedRouteSegments = setOf(
        "login", "signin", "sign-in", "signup", "sign-up", "register",
        "account", "accounts", "profile", "profiles", "user", "users",
        "author", "authors", "tag", "tags", "category", "categories",
        "topic", "topics", "search", "query", "help", "faq", "about",
        "contact", "privacy", "terms", "policy",
        "sitemap", "subscribe", "membership", "settings", "hide", "from", "u",
        "column", "columns",
    )

    private val blockedQueryKeys = setOf(
        "s", "q", "query", "search", "keyword", "tag", "category",
        "author", "user", "username", "page", "paged",
    )

    private val articleIdQueryKeys = setOf(
        "id", "aid", "articleid", "article_id", "newsid", "news_id",
        "post", "postid", "post_id", "contentid", "content_id",
    )

    private val blockedElementTokens = setOf(
        "author", "category", "tag", "topic", "login", "signin", "signup",
        "register", "search", "help", "more-link", "read-more-category",
    )

    private val blockedExactTitles = setOf(
        "login", "sign in", "sign up", "register", "my account", "profile",
        "search", "help", "faq", "about", "about us", "contact", "contact us",
        "privacy", "privacy policy", "terms", "terms of service", "subscribe", "more",
        "hide", "flag", "favorite", "unfavorite", "reply",
        "登录", "登陆", "注册", "账户", "账号", "个人中心", "用户中心", "搜索",
        "帮助", "常见问题", "关于", "关于我们", "联系我们", "隐私", "隐私政策",
        "服务条款", "订阅", "更多", "查看更多", "作者主页", "全部分类", "全部标签",
    )

    private val blockedTitlePrefixes = listOf(
        "作者：", "作者:", "标签：", "标签:", "分类：", "分类:",
        "专题：", "专题:", "搜索：", "搜索:", "author:", "tag:", "category:",
    )

    /** 返回 true 表示链接明显属于导航或聚合页面，不应进入文章候选。 */
    fun shouldReject(element: Element, title: String, url: String): Boolean {
        val normalizedTitle = title.trim().lowercase()
        if (normalizedTitle in blockedExactTitles) return true
        if (blockedTitlePrefixes.any { normalizedTitle.startsWith(it) }) return true

        val elementTokens = buildSet {
            addAll(element.classNames().map(String::lowercase))
            element.id().takeIf(String::isNotBlank)?.lowercase()?.let(::add)
            element.attr("rel").split(' ').filter(String::isNotBlank).mapTo(this, String::lowercase)
        }
        if (elementTokens.any { it in blockedElementTokens }) return true

        val uri = runCatching { URI(url) }.getOrNull() ?: return true
        val pathSegments = uri.rawPath.orEmpty()
            .split('/')
            .mapNotNull(::decodeSegment)
            .map { it.lowercase() }
            .filter(String::isNotBlank)
        if (pathSegments.any { it in blockedRouteSegments }) return true
        // archive/archives 既可能是归档首页，也常被站点直接用作文章永久链接前缀。
        // 不能仅凭路径词一刀切；只拒绝明显的归档根页与分页入口，带文章 ID/slug 的路径继续参与候选竞争。
        if (isArchiveListingPath(pathSegments)) return true

        val queryKeys = uri.rawQuery.orEmpty()
            .split('&')
            .map { it.substringBefore('=').trim().lowercase() }
            .filter(String::isNotBlank)
            .toSet()
        if (queryKeys.any { it in blockedQueryKeys } && queryKeys.none { it in articleIdQueryKeys }) return true

        return false
    }

    private fun isArchiveListingPath(pathSegments: List<String>): Boolean {
        val archiveIndex = pathSegments.indexOfFirst { it == "archive" || it == "archives" }
        if (archiveIndex < 0) return false
        val tail = pathSegments.drop(archiveIndex + 1)
        return tail.isEmpty() || tail.firstOrNull() in setOf("page", "paged")
    }

    private fun decodeSegment(value: String): String? =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull()
}
