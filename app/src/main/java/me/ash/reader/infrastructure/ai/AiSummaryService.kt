package me.ash.reader.infrastructure.ai

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.di.IODispatcher
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

@Singleton
class AiSummaryService @Inject constructor(
    private val settingsRepository: AiSettingsRepository,
    private val provider: OpenAiCompatibleProvider,
    private val cache: AiSummaryCache,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** 获取指定 OpenAI Compatible 服务支持的模型列表。 */
    suspend fun listModels(
        providerId: String,
        apiKeyOverride: String? = null,
    ): Result<List<String>> =
        runCatching {
            withContext(ioDispatcher) {
                val profile = settingsRepository.provider(providerId)
                    ?: throw AiException(AiErrorCode.NOT_CONFIGURED, "AI 服务配置不存在")
                if (profile.endpoint.isBlank()) {
                    throw AiException(AiErrorCode.INVALID_REQUEST, "请先填写 AI 服务地址")
                }
                provider.listModels(
                    settingsRepository.runtimeConfig(
                        providerId = profile.id,
                        apiKeyOverride = apiKeyOverride,
                    )
                )
            }
        }

    /**
     * 对当前文章生成摘要；默认使用全局默认供应商和默认摘要档位。
     * providerId/modelOverride/lengthOverride 只影响本次生成，不改写设置页默认值。
     */
    suspend fun summarizeArticle(
        articleId: String,
        title: String,
        content: String,
        forceRefresh: Boolean = false,
        providerId: String? = null,
        modelOverride: String? = null,
        lengthOverride: AiSummaryLength? = null,
        onProgress: (AiSummaryProgressStage) -> Unit = {},
    ): AiSummaryDocument =
        withContext(ioDispatcher) {
            val settings = requireEnabledSettings()
            val profile =
                providerId?.let { id -> settings.providers.firstOrNull { it.id == id } }
                    ?: settings.defaultProvider()
                    ?: throw AiException(AiErrorCode.NOT_CONFIGURED, "没有可用的 AI 服务")
            if (!profile.enabled) {
                throw AiException(AiErrorCode.DISABLED, "所选 AI 服务未启用")
            }
            val model = normalizeAiModelName(modelOverride ?: profile.resolvedDefaultModel().orEmpty())
            if (profile.endpoint.isBlank() || model.isBlank()) {
                throw AiException(AiErrorCode.NOT_CONFIGURED, "请先配置所选 AI 服务的地址和模型")
            }
            val length = lengthOverride ?: settings.summaryLength
            onProgress(AiSummaryProgressStage.PREPARING)
            if (!forceRefresh) {
                cache
                    .read(
                        articleId = articleId,
                        title = title,
                        content = content,
                        provider = profile,
                        model = model,
                        outputLanguage = settings.outputLanguage,
                        length = length,
                    )
                    ?.let { return@withContext it }
            }
            val articleSource = prepareArticleForSummary(content)
            if (articleSource.isBlank()) {
                throw AiException(AiErrorCode.INVALID_REQUEST, "当前文章没有可用于摘要的正文")
            }
            onProgress(AiSummaryProgressStage.GENERATING)
            val result =
                provider.completeDetailedCancellable(
                    systemPrompt = buildAiSummarySystemPrompt(settings.outputLanguage),
                    userPrompt =
                        buildAiSummaryUserPrompt(
                            title = title,
                            content = articleSource,
                            length = length,
                        ),
                    config =
                        settingsRepository.runtimeConfig(
                            providerId = profile.id,
                            modelOverride = model,
                        ),
                )
            onProgress(AiSummaryProgressStage.FINALIZING)
            val document =
                AiSummaryDocument(
                    articleId = articleId,
                    providerId = profile.id,
                    providerName = profile.name,
                    model = model,
                    outputLanguage = settings.outputLanguage.trim(),
                    length = length,
                    summary = result.content,
                    reasoning = result.reasoning,
                )
            cache.write(title, content, profile, document)
            document
        }

    /** 使用最小请求验证指定服务、模型和可选凭据是否可用。 */
    suspend fun testProvider(
        providerId: String,
        modelOverride: String? = null,
    ): Result<String> =
        runCatching {
            withContext(ioDispatcher) {
                requireEnabledSettings()
                val profile = settingsRepository.provider(providerId)
                    ?: throw AiException(AiErrorCode.NOT_CONFIGURED, "AI 服务配置不存在")
                val model = normalizeAiModelName(modelOverride ?: profile.defaultModel)
                if (!profile.enabled || profile.endpoint.isBlank() || model.isBlank()) {
                    throw AiException(AiErrorCode.NOT_CONFIGURED, "请先启用并填写 AI 服务地址和模型")
                }
                provider.complete(
                    systemPrompt = "You are a connection test. Follow the user instruction exactly.",
                    userPrompt = "Reply with exactly: OK",
                    config =
                        settingsRepository.runtimeConfig(
                            providerId = profile.id,
                            modelOverride = model,
                        ),
                )
            }
        }

    private fun requireEnabledSettings(): AiSettings {
        val settings = settingsRepository.current()
        if (!settings.enabled) {
            throw AiException(AiErrorCode.DISABLED, "请先启用 AI 阅读")
        }
        return settings
    }

}

/**
 * 将阅读器正文整理成保留结构的 Markdown 风格输入。
 * 相比直接 document.text()，标题层级、列表和引用能帮助模型恢复作者的论证结构。
 */
internal fun prepareArticleForSummary(content: String): String {
    val document = Jsoup.parse(content)
    val elements = document.select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre")
    val blocks = mutableListOf<String>()

    fun appendBlock(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) return
        // 只去掉相邻重复块，避免导航或解析器重复节点；不再全局 distinct，防止误删正文中的重复论证。
        if (blocks.lastOrNull() != normalized) blocks += normalized
    }

    elements.forEach { element -> appendBlock(element.toPromptBlock()) }
    val structured =
        blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
            ?: document.body()?.text().orEmpty().trim()
    if (structured.length <= MAX_AI_SUMMARY_INPUT_CHARACTERS) return structured
    return structured.take(AI_SUMMARY_HEAD_CHARACTERS) +
        "\n\n> [原文中间部分因上下文长度限制已截断]\n\n" +
        structured.takeLast(AI_SUMMARY_TAIL_CHARACTERS)
}

private fun Element.toPromptBlock(): String {
    val text = text().trim()
    if (text.isBlank()) return ""
    return when (tagName().lowercase()) {
        "h1" -> "# $text"
        "h2" -> "## $text"
        "h3" -> "### $text"
        "h4" -> "#### $text"
        "h5" -> "##### $text"
        "h6" -> "###### $text"
        "li" -> "- $text"
        "blockquote" -> "> $text"
        "pre" -> "```text\n$text\n```"
        else -> text
    }
}

/**
 * 摘要的编辑原则。重点不是复述段落，而是恢复文章的核心问题、结论、论证链和信息层级。
 */
internal fun buildAiSummarySystemPrompt(language: String): String =
    """
    你是一名高信息密度的新闻与长文编辑，负责把原文压缩成“读者看完摘要即可理解作者真正想表达什么”的结构化摘要。

    基本原则：
    1. 只能使用原文提供的信息。不得补充常识、外部事实、推测或模型自己的立场。
    2. 不按段落流水账复述。先在内部识别文章类型（新闻、评论/分析、教程、研究、复盘等），再提取最适合该类型的信息骨架。
    3. 优先回答：文章在讨论什么核心问题、作者/原文给出的核心结论是什么、完整论证链是什么、结论通过哪些关键事实/机制/案例/数据被支撑、这些信息之间是什么因果或层级关系。
    4. 区分“可核对事实”“作者观点/判断”“引用他人的观点或案例”。不要把作者判断改写成确定事实。
    5. 保留真正影响理解的专有名词、产品/机构名、数字、时间、版本、比例、方法名称和限制条件；删掉寒暄、重复铺垫、宣传语和不影响结论的人名罗列。
    6. 对有框架或方法论的文章，必须保留框架层级和各层含义；对有转折或争议的文章，必须保留“过去为何如此 → 现在为何变化 → 应该怎么做”的逻辑链。
    7. 每个关键要点必须是一个完整判断，而不是只摘一个名词。必要时用 1 至 3 句补充它的依据、机制或影响。
    8. 输出必须是规范 Markdown，不要使用 Markdown 代码围栏包住整份摘要，不要输出思考过程、免责声明或“以下是摘要”等套话。

    输出语言：${language.ifBlank { "zh-CN" }}。
    """.trimIndent()

/**
 * 针对不同摘要档位约束输出结构。标准档目标风格参考“摘要 + 主要内容”，同时强调论证链而非关键词堆砌。
 */
internal fun buildAiSummaryUserPrompt(
    title: String,
    content: String,
    length: AiSummaryLength,
): String {
    val formatRequirement =
        when (length) {
            AiSummaryLength.BRIEF ->
                """
                只输出：
                ## 摘要
                用一个高密度段落概括核心问题、核心结论和最关键依据。中文建议约 120～220 字；不要列要点。
                """.trimIndent()
            AiSummaryLength.STANDARD ->
                """
                严格使用以下结构：
                ## 摘要
                用 1～2 个自然段说明：核心问题/主题、作者或原文的核心结论、文章整体论证结构。不要只写背景。

                ## 主要内容
                列出 4～6 个编号要点。每个要点格式为：
                1. **一句完整的结论性标题。** 随后用 1～3 句解释关键依据、机制、案例、数据或影响。

                要点之间应覆盖文章不同层级，避免把同一个观点拆成多条重复表达。
                """.trimIndent()
            AiSummaryLength.DETAILED ->
                """
                严格使用以下结构：
                ## 摘要
                用 2～3 个自然段说明核心问题、核心结论、文章结构以及最重要的变化/矛盾。

                ## 论证结构
                用 3～6 条简洁条目还原作者从问题到结论的推导路径，不逐段复述。

                ## 主要内容
                列出 5～8 个编号要点。每个要点先用 **加粗的结论性标题**，再解释事实依据、机制、案例、数据、限制或影响。

                ## 值得关注
                只列原文明确提出的后续影响、适用边界、风险或仍未解决的问题；原文没有则省略本节。
                """.trimIndent()
        }

    return """
        请按照上面的编辑原则处理下面这篇文章。先在内部完成信息分层与取舍，只输出最终 Markdown 摘要。

        $formatRequirement

        <article>
        <title>${title.ifBlank { "（无标题）" }}</title>
        <body>
        $content
        </body>
        </article>
    """.trimIndent()
}

private const val MAX_AI_SUMMARY_INPUT_CHARACTERS = 24_000
private const val AI_SUMMARY_HEAD_CHARACTERS = 18_000
private const val AI_SUMMARY_TAIL_CHARACTERS = 6_000

