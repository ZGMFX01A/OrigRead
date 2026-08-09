package me.ash.reader.infrastructure.json

import java.net.URI

/** 为标准 WordPress REST API 构造通用文章列表规则。 */
object WordPressJsonRuleFactory {
    /**
     * 为站点地址生成有限数量的标准 WordPress REST 候选。
     * 子目录安装优先尝试当前路径，随后回退到根目录安装。
     */
    fun createCandidates(siteUrl: String): List<JsonRule> {
        val uri = URI(siteUrl)
        val host = requireNotNull(uri.host) { "WordPress 地址缺少域名" }
        val origin = URI(uri.scheme ?: "https", uri.userInfo, host, uri.port, "/", null, null)
            .toString()
            .trimEnd('/')
        val path = uri.path.orEmpty().trim('/')
        val bases = buildList {
            if (path.isNotBlank()) add("$origin/$path")
            add(origin)
        }.distinct()

        return bases.mapIndexed { index, base ->
            create(siteUrl).copy(
                id = "wordpress-${host.lowercase()}-$index",
                endpoint = "$base/wp-json/wp/v2/posts?_embed=1&per_page=30",
            )
        }
    }

    fun create(siteUrl: String): JsonRule {
        val uri = URI(siteUrl)
        val host = requireNotNull(uri.host) { "WordPress 地址缺少域名" }
        val siteBase =
            URI(uri.scheme ?: "https", uri.userInfo, host, uri.port, "/", null, null)
                .toString()
                .trimEnd('/')
        return JsonRule(
            id = "wordpress-${host.lowercase()}",
            name = "WordPress · $host",
            hosts = listOf(host),
            endpoint = "$siteBase/wp-json/wp/v2/posts?_embed=1&per_page=30",
            itemsPath = "$[*]",
            titlePath = "$.title.rendered",
            linkPath = "$.link",
            datePath = "$.date_gmt",
            descriptionPath = "$.excerpt.rendered",
            idPath = "$.id",
            dateFormat = "yyyy-MM-dd'T'HH:mm:ss",
            maxItems = 30,
        )
    }

    /** 已保存的标准 WordPress posts endpoint 可直接恢复为内置规则，无需写入用户规则文件。 */
    fun createFromEndpoint(endpointUrl: String): JsonRule? {
        val uri = runCatching { URI(endpointUrl) }.getOrNull() ?: return null
        if (!uri.path.orEmpty().contains("/wp-json/wp/v2/posts")) return null
        return runCatching { create(endpointUrl).copy(endpoint = endpointUrl) }.getOrNull()
    }
}

