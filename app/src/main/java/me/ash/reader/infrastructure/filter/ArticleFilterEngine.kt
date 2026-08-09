package me.ash.reader.infrastructure.filter

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.model.article.Article

data class ArticleFilterMatch(
    val rule: ArticleFilterRule,
)

@Singleton
class ArticleFilterEngine @Inject constructor(
    private val repository: ArticleFilterRepository,
) {
    /** 返回首条命中规则。 */
    fun match(article: Article): ArticleFilterMatch? =
        ArticleFilterMatcher.match(
            title = article.title,
            feedId = article.feedId,
            rules = repository.getAll(),
        )

    /** 同步完成筛选后批量记录统计，避免逐篇文章写文件。 */
    fun recordMatches(matches: List<ArticleFilterMatch>) {
        repository.recordMatches(matches.size, matches.lastOrNull()?.rule)
    }
}

/** 不依赖 Android 环境的纯规则匹配器。来源规则优先于全局规则。 */
internal object ArticleFilterMatcher {
    fun match(
        title: String,
        feedId: String,
        rules: List<ArticleFilterRule>,
    ): ArticleFilterMatch? =
        rules
            .asSequence()
            .filter { it.enabled }
            .filter { it.feedId == null || it.feedId == feedId }
            .sortedByDescending { it.feedId != null }
            .firstOrNull { rule ->
                when (rule.type) {
                    ArticleFilterRuleType.KEYWORD -> title.contains(rule.keyword.trim(), ignoreCase = true)
                    ArticleFilterRuleType.REGEX -> runCatching {
                        Regex(rule.keyword, RegexOption.IGNORE_CASE).containsMatchIn(title)
                    }.getOrDefault(false)
                }
            }
            ?.let(::ArticleFilterMatch)
}
