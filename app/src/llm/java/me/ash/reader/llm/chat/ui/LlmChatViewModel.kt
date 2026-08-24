package me.ash.reader.llm.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.availableModels
import me.ash.reader.infrastructure.ai.resolvedDefaultModel
import me.ash.reader.llm.chat.data.LlmChatRepository
import me.ash.reader.llm.chat.data.LlmChatRole
import me.ash.reader.llm.chat.data.LlmConversationEntity
import me.ash.reader.llm.chat.data.LlmMessageEntity
import me.ash.reader.llm.chat.data.LlmMessageStatus
import me.ash.reader.llm.chat.runtime.LlmChatRequestMessage
import me.ash.reader.llm.chat.runtime.LlmChatTransport
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextPolicy
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.runtime.LlmExecutionProfile
import me.ash.reader.llm.runtime.LlmReasoningEffort
import me.ash.reader.llm.runtime.LlmRuntime
import me.ash.reader.llm.runtime.ModelCapabilityOverride
import me.ash.reader.llm.runtime.estimateLlmTokens
import me.ash.reader.llm.settings.LlmSettingsRepository
import me.ash.reader.llm.skill.LlmSkillRouter
import me.ash.reader.ui.page.home.reading.ArticleAssistantContext

/** Chat 页面全部可观察状态；Provider/Model 继续复用现有 AI 设置，不另存密钥。 */
data class LlmChatUiState(
    val articleTitle: String? = null,
    val conversations: List<LlmConversationEntity> = emptyList(),
    val currentConversationId: String? = null,
    val messages: List<LlmMessageEntity> = emptyList(),
    val providers: List<AiProviderProfile> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModel: String? = null,
    val availableModels: List<String> = emptyList(),
    val showReasoning: Boolean = true,
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.AUTO,
    val isGenerating: Boolean = false,
    val transientError: String? = null,
)

/** 当前会话的运行时选择，只保存 Provider/Model 标识，不持有 API Key。 */
private data class RuntimeSelection(
    val providerId: String? = null,
    val model: String? = null,
)

@HiltViewModel
/**
 * P3 基础 Chat 的状态协调层。
 *
 * 负责会话持久化、Provider/Model 选择、多轮历史、流式生成、停止和重新生成；
 * 具体 HTTP/SSE 解析由 [LlmChatTransport] 负责，模型能力由 P2 [LlmRuntime] 负责。
 */
class LlmChatViewModel @Inject constructor(
    private val repository: LlmChatRepository,
    private val settingsRepository: AiSettingsRepository,
    private val llmSettingsRepository: LlmSettingsRepository,
    private val skillRouter: LlmSkillRouter,
    private val llmRuntime: LlmRuntime,
    private val transport: LlmChatTransport,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LlmChatUiState())
    val uiState = _uiState.asStateFlow()

    private val selectedConversationId = MutableStateFlow<String?>(null)
    private val articleContext = MutableStateFlow<ArticleAssistantContext?>(null)
    private val runtimeSelection = MutableStateFlow(RuntimeSelection())
    private var generationJob: Job? = null
    private var conversationSelectionInitialized = false

    init {
        recoverInterruptedGenerations()
        observeAiSettings()
        observeLlmSettings()
        observeConversations()
        observeMessages()
    }

    /** 进程被系统杀死时无法执行 finally；重进 Chat 后把遗留 STREAMING 状态收口为 STOPPED。 */
    private fun recoverInterruptedGenerations() {
        viewModelScope.launch { repository.recoverInterruptedGenerations() }
    }

    private fun observeAiSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val providers = settings.providers.filter(AiProviderProfile::enabled)
                val currentSelection = runtimeSelection.value
                val selectedProvider =
                    providers.firstOrNull { it.id == currentSelection.providerId }
                        ?: settings.defaultProvider()?.takeIf(AiProviderProfile::enabled)
                        ?: providers.firstOrNull()
                val selectedModel =
                    currentSelection.model
                        ?.takeIf { model -> selectedProvider?.availableModels()?.contains(model) == true }
                        ?: selectedProvider?.resolvedDefaultModel()
                runtimeSelection.value =
                    RuntimeSelection(
                        providerId = selectedProvider?.id,
                        model = selectedModel,
                    )
                publishRuntimeState(providers)
            }
        }
    }

    /** LLM edition 独有设置只影响 Runtime/Chat，不改变 Standard 的基础 AI 阅读配置。 */
    private fun observeLlmSettings() {
        viewModelScope.launch {
            llmSettingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        showReasoning = settings.showReasoning,
                        reasoningEffort = settings.reasoningEffort,
                    )
                }
            }
        }
    }

    /**
     * 将阅读页当前文章绑定到助手。
     * 同一文章的摘要/译文变化只更新 Context；articleId 改变才切换会话域。
     */
    fun bindArticleContext(context: ArticleAssistantContext) {
        val articleChanged = articleContext.value?.articleId != context.articleId
        if (articleChanged) {
            stopGeneration()
            conversationSelectionInitialized = false
            selectedConversationId.value = null
            _uiState.update {
                it.copy(
                    articleTitle = context.title,
                    currentConversationId = null,
                    conversations = emptyList(),
                    messages = emptyList(),
                    transientError = null,
                )
            }
        } else {
            _uiState.update { it.copy(articleTitle = context.title) }
        }
        articleContext.value = context
    }

    /** 只观察当前文章的会话列表，并在首次进入该文章时恢复最近活动会话。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeConversations() {
        viewModelScope.launch {
            articleContext
                .flatMapLatest { context ->
                    if (context == null) flowOf(emptyList())
                    else repository.observeConversations(context.articleId)
                }
                .collect { conversations ->
                    _uiState.update { it.copy(conversations = conversations) }
                    val selectedId = selectedConversationId.value
                    if (!conversationSelectionInitialized) {
                        conversationSelectionInitialized = true
                        if (selectedId == null && conversations.isNotEmpty()) {
                            selectConversationInternal(conversations.first().id)
                        }
                    } else if (selectedId != null && conversations.none { it.id == selectedId }) {
                        selectConversationInternal(conversations.firstOrNull()?.id)
                    }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    /** 随当前会话切换消息 Flow；新建会话时返回空列表。 */
    private fun observeMessages() {
        viewModelScope.launch {
            selectedConversationId
                .flatMapLatest { conversationId ->
                    if (conversationId == null) flowOf(emptyList())
                    else repository.observeMessages(conversationId)
                }
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
        }
    }

    /** 新建空白会话视图；真正的数据库会话在发送第一条消息时延迟创建。 */
    fun newConversation() {
        if (articleContext.value == null) return
        stopGeneration()
        conversationSelectionInitialized = true
        selectedConversationId.value = null
        val settings = settingsRepository.current()
        val provider = settings.defaultProvider()?.takeIf(AiProviderProfile::enabled)
        runtimeSelection.value =
            RuntimeSelection(
                providerId = provider?.id,
                model = provider?.resolvedDefaultModel(),
            )
        _uiState.update {
            it.copy(
                currentConversationId = null,
                messages = emptyList(),
                transientError = null,
            )
        }
        publishRuntimeState(settings.providers.filter(AiProviderProfile::enabled))
    }

    /** 切换历史会话，并恢复该会话保存的 Provider/Model。 */
    fun selectConversation(conversationId: String) {
        if (conversationId == selectedConversationId.value) return
        stopGeneration()
        conversationSelectionInitialized = true
        viewModelScope.launch { selectConversationInternal(conversationId) }
    }

    /** 从数据库加载会话运行时选择；已被删除/禁用的 Provider 回退到当前默认配置。 */
    private suspend fun selectConversationInternal(conversationId: String?) {
        val conversation = conversationId?.let { repository.getConversation(it) }
        val currentArticleId = articleContext.value?.articleId
        if (conversation != null && conversation.articleId != currentArticleId) return
        selectedConversationId.value = conversationId
        val settings = settingsRepository.current()
        val enabledProviders = settings.providers.filter(AiProviderProfile::enabled)
        val provider =
            enabledProviders.firstOrNull { it.id == conversation?.providerId }
                ?: settings.defaultProvider()?.takeIf(AiProviderProfile::enabled)
                ?: enabledProviders.firstOrNull()
        val model =
            conversation?.model
                ?.takeIf { it in provider.orEmptyModels() }
                ?: provider?.resolvedDefaultModel()
        runtimeSelection.value = RuntimeSelection(provider?.id, model)
        _uiState.update {
            it.copy(
                currentConversationId = conversationId,
                transientError = null,
            )
        }
        publishRuntimeState(enabledProviders)
    }

    /** 切换 Provider，并使用该 Provider 当前默认模型。生成期间禁止切换。 */
    fun selectProvider(providerId: String) {
        if (_uiState.value.isGenerating) return
        val provider =
            settingsRepository.current().providers.firstOrNull { it.id == providerId && it.enabled }
                ?: return
        runtimeSelection.value =
            runtimeSelection.value.copy(
                providerId = provider.id,
                model = provider.resolvedDefaultModel(),
            )
        publishRuntimeState(settingsRepository.current().providers.filter(AiProviderProfile::enabled))
        persistCurrentRuntimeSelection()
    }

    /** 切换当前模型；生成期间禁止切换。 */
    fun selectModel(model: String) {
        if (_uiState.value.isGenerating) return
        val normalized = model.trim()
        if (normalized.isBlank()) return
        runtimeSelection.value = runtimeSelection.value.copy(model = normalized)
        publishRuntimeState(settingsRepository.current().providers.filter(AiProviderProfile::enabled))
        persistCurrentRuntimeSelection()
    }

    /** 跨供应商模型抽屉一次性提交 Provider/Model，避免中间态闪动。 */
    fun selectProviderModel(providerId: String, model: String) {
        if (_uiState.value.isGenerating) return
        val provider =
            settingsRepository.current().providers.firstOrNull { it.id == providerId && it.enabled }
                ?: return
        val normalized = model.trim()
        if (normalized.isBlank() || normalized !in provider.availableModels()) return
        runtimeSelection.value =
            runtimeSelection.value.copy(providerId = provider.id, model = normalized)
        publishRuntimeState(settingsRepository.current().providers.filter(AiProviderProfile::enabled))
        persistCurrentRuntimeSelection()
    }

    /** 对话页底栏直接修改 LLM edition 的 Reasoning Effort。 */
    fun setReasoningEffort(value: LlmReasoningEffort) {
        if (_uiState.value.isGenerating) return
        llmSettingsRepository.setReasoningEffort(value)
    }

    /** 将当前 Provider/Model 绑定到已创建会话；Skill 改为逐请求自动路由。 */
    private fun persistCurrentRuntimeSelection() {
        val conversationId = selectedConversationId.value ?: return
        val selection = runtimeSelection.value
        viewModelScope.launch {
            repository.updateConversationRuntime(
                conversationId = conversationId,
                providerId = selection.providerId,
                model = selection.model,
                skillId = null,
            )
        }
    }

    /**
     * 发送用户消息并启动一轮 assistant 流式生成。
     * 首条消息会同时创建会话，避免用户打开页面但从未发言时产生空历史记录。
     */
    fun sendMessage(rawText: String) {
        val text = rawText.trim()
        val currentArticle = articleContext.value ?: return
        if (text.isBlank() || hasGenerationInFlight()) return
        startGenerationJob {
            val selection = runtimeSelection.value
            val conversationId =
                selectedConversationId.value
                    ?: repository
                        .createConversation(
                            providerId = selection.providerId,
                            model = selection.model,
                            skillId = null,
                            articleId = currentArticle.articleId,
                            articleTitle = currentArticle.title,
                            articleLink = currentArticle.link,
                            titleSeed = text,
                        )
                        .id
                        .also { createdId ->
                            selectedConversationId.value = createdId
                            _uiState.update { it.copy(currentConversationId = createdId) }
                        }

            repository.updateConversationRuntime(
                conversationId = conversationId,
                providerId = selection.providerId,
                model = selection.model,
                skillId = null,
            )
            repository.appendMessage(
                conversationId = conversationId,
                role = LlmChatRole.USER,
                content = text,
            )
            generateAssistant(conversationId)
        }
    }

    /**
     * 取消当前 Coroutine；Transport 会同步取消底层 OkHttp Call。
     * 不在这里提前清空 Job：取消后的 NonCancellable 状态落库完成前，禁止启动下一轮生成。
     */
    fun stopGeneration() {
        generationJob?.cancel(CancellationException("用户停止生成"))
    }

    /**
     * 删除当前会话最后一条 assistant 回复并从最后一条 user 消息重新请求。
     * 既用于“重新生成”，也作为网络/服务错误后的“重试”。
     */
    fun regenerateLast() {
        if (hasGenerationInFlight()) return
        val conversationId = selectedConversationId.value ?: return
        startGenerationJob {
            val messages = repository.getMessages(conversationId)
            val lastUserIndex = messages.indexOfLast { it.role == LlmChatRole.USER }
            if (lastUserIndex < 0) return@startGenerationJob
            messages.drop(lastUserIndex + 1).forEach { trailing ->
                if (trailing.role == LlmChatRole.ASSISTANT) {
                    repository.deleteMessage(trailing.id)
                }
            }
            generateAssistant(conversationId)
        }
    }

    /** 重命名指定会话。 */
    fun renameConversation(conversationId: String, title: String) {
        viewModelScope.launch { repository.renameConversation(conversationId, title) }
    }

    /** 删除指定会话；若正在生成该会话，先终止网络请求。 */
    fun deleteConversation(conversationId: String) {
        if (conversationId == selectedConversationId.value) stopGeneration()
        viewModelScope.launch { repository.deleteConversation(conversationId) }
    }

    /** Snackbar 消费错误后清除一次性错误状态。 */
    fun clearTransientError() {
        _uiState.update { it.copy(transientError = null) }
    }

    /**
     * 将持久化历史转换为模型消息并消费 SSE 增量。
     * 中途取消保存 STOPPED，服务错误保存 ERROR，正常结束保存 COMPLETE。
     */
    private suspend fun generateAssistant(conversationId: String) {
        var assistant =
            repository.appendMessage(
                conversationId = conversationId,
                role = LlmChatRole.ASSISTANT,
                content = "",
                status = LlmMessageStatus.STREAMING,
            )
        var content = ""
        var reasoning = ""
        var lastPersistAt = 0L
        var requestStartedAtNanos: Long? = null
        var fallbackPromptTokens: Int? = null
        var providerPromptTokens: Int? = null
        var providerCompletionTokens: Int? = null

        fun durationMs(): Long? =
            requestStartedAtNanos?.let { startedAt ->
                ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            }

        fun promptTokens(): Int? = providerPromptTokens ?: fallbackPromptTokens

        fun completionTokens(): Int =
            providerCompletionTokens
                ?: estimateLlmTokens(reasoning + content).coerceAtLeast(1)

        fun tokenUsageEstimated(): Boolean =
            providerPromptTokens == null || providerCompletionTokens == null

        try {
            val history =
                repository
                    .getMessages(conversationId)
                    .filter { message ->
                        message.id != assistant.id &&
                            message.role != LlmChatRole.SYSTEM &&
                            message.content.isNotBlank() &&
                            message.status != LlmMessageStatus.ERROR
                    }
                    .map { message ->
                        LlmChatRequestMessage(role = message.role, content = message.content)
                    }
            val selection = runtimeSelection.value
            val currentArticle =
                articleContext.value ?: error("当前文章上下文已失效，请重新打开阅读助手")
            val advancedSettings = llmSettingsRepository.current()
            val latestUserInput =
                history.lastOrNull { it.role == LlmChatRole.USER }?.content.orEmpty()
            val autoSkillId = skillRouter.resolve(latestUserInput)?.id
            val plan =
                llmRuntime.prepare(
                    profile =
                        LlmExecutionProfile(
                            providerId = selection.providerId,
                            model = selection.model,
                            skillId = autoSkillId,
                            reasoningEffort = advancedSettings.reasoningEffort,
                            capabilityOverride =
                                if (advancedSettings.streamResponses) {
                                    null
                                } else {
                                    ModelCapabilityOverride(supportsStreaming = false)
                                },
                            contextPolicy =
                                LlmContextPolicy(
                                    maxTokens = advancedSettings.contextMaxTokens
                                ),
                        ),
                    contextItems = buildArticleContextItems(currentArticle),
                )

            fallbackPromptTokens = transport.estimateRequestTokens(plan, history)
            requestStartedAtNanos = System.nanoTime()

            transport.stream(plan, history).collect { delta ->
                content += delta.content
                reasoning += delta.reasoning
                delta.promptTokens?.let { providerPromptTokens = it }
                delta.completionTokens?.let { providerCompletionTokens = it }
                val now = System.currentTimeMillis()
                if (now - lastPersistAt >= STREAM_PERSIST_INTERVAL_MS) {
                    assistant =
                        repository.updateMessage(
                            message = assistant,
                            content = content,
                            reasoning = reasoning.ifBlank { null },
                            status = LlmMessageStatus.STREAMING,
                            errorMessage = null,
                        )
                    lastPersistAt = now
                }
            }

            if (content.isBlank()) {
                error("AI 服务没有返回可显示内容")
            }
            repository.updateMessage(
                message = assistant,
                content = content,
                reasoning = reasoning.ifBlank { null },
                status = LlmMessageStatus.COMPLETE,
                errorMessage = null,
                promptTokens = promptTokens(),
                completionTokens = completionTokens(),
                durationMs = durationMs(),
                tokenUsageEstimated = tokenUsageEstimated(),
            )
        } catch (error: CancellationException) {
            // 取消发生时当前协程已经不可挂起，使用 NonCancellable 确保部分结果和 STOPPED 状态落库。
            withContext(NonCancellable) {
                repository.updateMessage(
                    message = assistant,
                    content = content,
                    reasoning = reasoning.ifBlank { null },
                    status = LlmMessageStatus.STOPPED,
                    errorMessage = null,
                    promptTokens = promptTokens(),
                    completionTokens = completionTokens(),
                    durationMs = durationMs(),
                    tokenUsageEstimated = tokenUsageEstimated(),
                )
            }
            throw error
        } catch (error: Throwable) {
            // 错误信息既持久化到消息，又作为一次性 Snackbar 暴露，便于历史恢复后仍能看见失败原因。
            val message = error.message?.takeIf(String::isNotBlank) ?: "AI 请求失败"
            repository.updateMessage(
                message = assistant,
                content = content,
                reasoning = reasoning.ifBlank { null },
                status = LlmMessageStatus.ERROR,
                errorMessage = message,
                promptTokens = promptTokens(),
                completionTokens = completionTokens(),
                durationMs = durationMs(),
                tokenUsageEstimated = tokenUsageEstimated(),
            )
            _uiState.update { it.copy(transientError = message) }
        }
    }

    /** 将内部运行时选择映射为 UI 可用的 Provider/Model 列表。 */
    private fun publishRuntimeState(providers: List<AiProviderProfile>) {
        val selection = runtimeSelection.value
        val selectedProvider = providers.firstOrNull { it.id == selection.providerId }
        _uiState.update {
            it.copy(
                providers = providers,
                selectedProviderId = selection.providerId,
                selectedModel = selection.model,
                availableModels = selectedProvider?.availableModels().orEmpty(),
            )
        }
    }

    /** 取消中的 Job 仍属于在途任务，直到 STOPPED/ERROR/COMPLETE 状态真正落库并完成 finally。 */
    private fun hasGenerationInFlight(): Boolean = generationJob?.isCompleted == false

    /**
     * 先保存 LAZY Job 引用再启动，避免极快完成的任务在 generationJob 赋值前进入 finally。
     * 同一时刻最多允许一条生成链，保证停止、重试和新消息不会互相覆盖 isGenerating 状态。
     */
    private fun startGenerationJob(block: suspend () -> Unit) {
        if (hasGenerationInFlight()) return
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                _uiState.update { it.copy(isGenerating = true, transientError = null) }
                try {
                    block()
                } finally {
                    generationJob = null
                    _uiState.update { it.copy(isGenerating = false) }
                }
            }
        generationJob = job
        job.start()
    }
}

/** 流式期间限制 Room 写入频率，避免每个 token 都触发一次持久化。 */
private const val STREAM_PERSIST_INTERVAL_MS = 90L

private fun AiProviderProfile?.orEmptyModels(): List<String> = this?.availableModels().orEmpty()

/**
 * 当前译文与摘要属于用户正在看的派生内容，优先于长原文进入有限 Context；
 * 原文仍作为基础事实来源参与剩余预算，P2 ContextComposer 负责安全截断。
 */
internal fun buildArticleContextItems(context: ArticleAssistantContext): List<LlmContextItem> =
    buildList {
        context.summary?.trim()?.takeIf(String::isNotBlank)?.let { summary ->
            add(
                LlmContextItem(
                    id = "article:${context.articleId}:summary",
                    type = LlmContextType.ARTICLE_SUMMARY,
                    title = context.title,
                    sourceId = context.link,
                    content = summary,
                    priority = 130,
                )
            )
        }
        context.translatedContent?.trim()?.takeIf(String::isNotBlank)?.let { translation ->
            add(
                LlmContextItem(
                    id = "article:${context.articleId}:translation",
                    type = LlmContextType.ARTICLE_TRANSLATION,
                    title = context.translatedTitle ?: context.title,
                    sourceId = context.link,
                    content = translation,
                    priority = 120,
                )
            )
        }
        context.originalContent.trim().takeIf(String::isNotBlank)?.let { original ->
            add(
                LlmContextItem(
                    id = "article:${context.articleId}:original",
                    type = LlmContextType.ARTICLE,
                    title = context.title,
                    sourceId = context.link,
                    content = original,
                    priority = 100,
                )
            )
        }
    }
