package me.ash.reader.infrastructure.filter

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.model.article.Article

data class ArticleFilterMatch(
    val rule: ArticleFilterRule,
)

internal data class CompiledFilterRule(
    val rule: ArticleFilterRule,
    val regex: Regex?,
)

@Singleton
class ArticleFilterEngine @Inject constructor(
    private val repository: ArticleFilterRepository,
) {
    /** 返回首条命中规则。 */
    fun match(article: Article): ArticleFilterMatch? =
        ArticleFilterMatcher.matchCompiled(
            title = article.title,
            feedId = article.feedId,
            rules = repository.getCompiledRules(),
        )

    /** 同步完成筛选后批量记录统计，避免逐篇文章写文件。 */
    fun recordMatches(matches: List<ArticleFilterMatch>) {
        repository.recordMatches(matches.size, matches.lastOrNull()?.rule)
    }
}

/** 不依赖 Android 环境的纯规则匹配器。来源规则优先于全局规则。 */
internal object ArticleFilterMatcher {
    fun compile(rules: List<ArticleFilterRule>): List<CompiledFilterRule> =
        rules.map { rule ->
            CompiledFilterRule(
                rule = rule,
                regex =
                    if (rule.type == ArticleFilterRuleType.REGEX) {
                        runCatching { Regex(rule.keyword, RegexOption.IGNORE_CASE) }.getOrNull()
                    } else {
                        null
                    },
            )
        }

    fun match(
        title: String,
        feedId: String,
        rules: List<ArticleFilterRule>,
    ): ArticleFilterMatch? = matchCompiled(title, feedId, compile(rules))

    fun matchCompiled(
        title: String,
        feedId: String,
        rules: List<CompiledFilterRule>,
    ): ArticleFilterMatch? {
        val feedMatch = rules.firstOrNull { it.rule.feedId == feedId && it.matches(title) }
        return (feedMatch ?: rules.firstOrNull { it.rule.feedId == null && it.matches(title) })
            ?.let { ArticleFilterMatch(it.rule) }
    }

    private fun CompiledFilterRule.matches(title: String): Boolean {
        if (!rule.enabled) return false
        return when (rule.type) {
            ArticleFilterRuleType.KEYWORD -> title.contains(rule.keyword.trim(), ignoreCase = true)
            ArticleFilterRuleType.REGEX -> regex?.containsMatchIn(title) == true
        }
    }
}
