package me.ash.reader.infrastructure.json

import org.jsoup.Jsoup

/** 从 HTML 中读取 Next.js/Nuxt 等框架内嵌的 JSON 数据。 */
object EmbeddedJsonExtractor {
    fun extractNextData(html: String, baseUrl: String): String? =
        Jsoup.parse(html, baseUrl)
            .selectFirst("script#__NEXT_DATA__[type=application/json]")
            ?.data()
            ?.ifBlank { null }

    fun extractNuxtData(html: String, baseUrl: String): String? {
        val document = Jsoup.parse(html, baseUrl)
        return document.selectFirst("script#__NUXT_DATA__[type=application/json]")
            ?.data()
            ?.ifBlank { null }
            ?: document.selectFirst("script[type=application/json][data-nuxt-data]")
                ?.data()
                ?.ifBlank { null }
    }
}

