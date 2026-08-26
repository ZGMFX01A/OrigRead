package me.ash.reader.llm.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
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
import me.ash.reader.llm.chat.data.LlmContextRefEntity
import me.ash.reader.llm.chat.data.LlmConversationEntity
import me.ash.reader.llm.chat.data.LlmMessageEntity
import me.ash.reader.llm.chat.data.LlmMessageStatus
import me.ash.reader.llm.chat.data.LlmToolCallEntity
import me.ash.reader.llm.chat.data.LlmToolCallStatus
import me.ash.reader.llm.chat.data.buildRequestContextRefEntities
import me.ash.reader.llm.chat.runtime.LlmChatRequestMessage
import me.ash.reader.llm.chat.runtime.LlmChatRequestToolCall
import me.ash.reader.llm.chat.runtime.LlmChatToolCallDelta
import me.ash.reader.llm.chat.runtime.LlmChatTransport
import me.ash.reader.llm.chat.runtime.resolveToolByApiName
import me.ash.reader.llm.mcp.McpToolRegistry
import me.ash.reader.llm.quickmessage.LlmQuickMessage
import me.ash.reader.llm.quickmessage.LlmQuickMessageContext
import me.ash.reader.llm.quickmessage.LlmQuickMessageRepository
import me.ash.reader.llm.quickmessage.LlmQuickMessageResolution
import me.ash.reader.llm.quickmessage.resolveQuickMessageTemplate
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextPolicy
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.runtime.LlmExecutionProfile
import me.ash.reader.llm.runtime.LlmExecutionTask
import me.ash.reader.llm.runtime.LlmReasoningEffort
import me.ash.reader.llm.runtime.LlmRuntime
import me.ash.reader.llm.runtime.LlmToolCall
import me.ash.reader.llm.runtime.LlmToolDescriptor
import me.ash.reader.llm.runtime.LlmToolResult
import me.ash.reader.llm.runtime.LlmToolRuntime
import me.ash.reader.llm.runtime.LlmToolSource
import me.ash.reader.llm.runtime.ModelCapabilityOverride
import me.ash.reader.llm.runtime.estimateLlmTokens
import me.ash.reader.llm.search.WebSearchMode
import me.ash.reader.llm.search.WebSearchRouter
import me.ash.reader.llm.search.toContextItems
import me.ash.reader.llm.settings.LlmSettingsRepository
import me.ash.reader.llm.skill.LlmSkillRepository
import me.ash.reader.llm.skill.LlmSkillRouter
import me.ash.reader.llm.skill.LlmSkillTask
import me.ash.reader.ui.page.home.reading.ArticleAssistantContext

/** Chat 页面全部可观察状态；Provider/Model 继续复用现有 AI 设置，不另存密钥。 */
data class LlmChatUiState(
    val articleTitle: String? = null,
    val conversations: List<LlmConversationEntity> = emptyList(),
    val currentConversationId: String? = null,
    val messages: List<LlmMessageEntity> = emptyList(),
    val toolCalls: List<LlmToolCallEntity> = emptyList(),
    val contextRefs: List<LlmContextRefEntity> = emptyList(),
    val providers: List<AiProviderProfile> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModel: String? = null,
    val availableModels: List<String> = emptyList(),
    val showReasoning: Boolean = true,
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.AUTO,
    val webSearchEnabled: Boolean = false,
    val webSearchMode: WebSearchMode = WebSearchMode.AUTO,
    val quickMessages: List<LlmQuickMessage> = emptyList(),
    val manualToolFallbackAvailable: Boolean = false,
    val manualTools: List<LlmToolDescriptor> = emptyList(),
    val manualToolContexts: List<LlmManualToolContext> = emptyList(),
    val pendingManualTool: LlmPendingManualTool? = null,
    val manualToolRunning: Boolean = false,
    val isGenerating: Boolean = false,
    val transientError: String? = null,
)

/** 手动 Tool 结果只作为当前文章助手的参考资料，不伪造 Provider Function Calling 历史。 */
data class LlmManualToolContext(
    val id: String,
    val toolId: String,
    val toolName: String,
    val sourceId: String?,
    val content: String,
) {
    fun toContextItem(): LlmContextItem =
        LlmContextItem(
            id = "manual-tool:$id",
            type = LlmContextType.TOOL_RESULT,
            content = content,
            title = toolName,
            sourceId = sourceId,
            priority = MANUAL_TOOL_CONTEXT_PRIORITY,
        )
}

/** 敏感/写入 Tool 的待确认请求；批准后 Runtime 仍会再次检查 allowed Tool 与 confirmed 标志。 */
data class LlmPendingManualTool(
    val callId: String,
    val descriptor: LlmToolDescriptor,
    val argumentsJson: String,
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
    private val quickMessageRepository: LlmQuickMessageRepository,
    private val skillRepository: LlmSkillRepository,
    private val skillRouter: LlmSkillRouter,
    private val webSearchRouter: WebSearchRouter,
    private val llmRuntime: LlmRuntime,
    private val toolRuntime: LlmToolRuntime,
    private val mcpToolRegistry: McpToolRegistry,
    private val transport: LlmChatTransport,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LlmChatUiState())
    val uiState = _uiState.asStateFlow()

    private val selectedConversationId = MutableStateFlow<String?>(null)
    private val articleContext = MutableStateFlow<ArticleAssistantContext?>(null)
    private val runtimeSelection = MutableStateFlow(RuntimeSelection())
    private var forceWebSearchNextRequest = false
    private var generationJob: Job? = null
    private var manualToolJob: Job? = null
    private var conversationSelectionInitialized = false

    init {
        // Chat 不依赖用户先进入 MCP 设置页；重启后恢复已缓存且启用的 MCP Tool。
        mcpToolRegistry.restoreCachedTools()
        recoverInterruptedGenerations()
        observeAiSettings()
        observeLlmSettings()
        observeQuickMessages()
        observeConversations()
        observeMessages()
        observeToolCalls()
        observeContextRefs()
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
                if (!settings.webSearchEnabled) {
                    // 总开关关闭时立即解除“一次性强制联网”，避免以后重新打开后意外消费一次搜索请求。
                    forceWebSearchNextRequest = false
                }
                _uiState.update {
                    it.copy(
                        showReasoning = settings.showReasoning,
                        reasoningEffort = settings.reasoningEffort,
                        webSearchEnabled = settings.webSearchEnabled,
                        webSearchMode =
                            if (settings.webSearchEnabled && forceWebSearchNextRequest) WebSearchMode.FORCE
                            else settings.webSearchMode,
                    )
                }
                refreshManualToolFallback()
            }
        }
    }

    /** Quick Messages 只作为普通 USER 消息模板进入 UI；这里不会把它写进 Runtime/Profile/System Prompt。 */
    private fun observeQuickMessages() {
        viewModelScope.launch {
            quickMessageRepository.messages.collect { messages ->
                _uiState.update {
                    it.copy(quickMessages = messages.filter(LlmQuickMessage::enabled))
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
            manualToolJob?.cancel(CancellationException("文章已切换"))
            conversationSelectionInitialized = false
            selectedConversationId.value = null
            _uiState.update {
                it.copy(
                    articleTitle = context.title,
                    currentConversationId = null,
                    conversations = emptyList(),
                    messages = emptyList(),
                    contextRefs = emptyList(),
                    manualToolContexts = emptyList(),
                    pendingManualTool = null,
                    manualToolRunning = false,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    /** Tool Call 与消息分表持久化；切换会话时同步恢复审批/执行状态。 */
    private fun observeToolCalls() {
        viewModelScope.launch {
            selectedConversationId
                .flatMapLatest { conversationId ->
                    if (conversationId == null) flowOf(emptyList())
                    else repository.observeToolCalls(conversationId)
                }
                .collect { toolCalls ->
                    _uiState.update { it.copy(toolCalls = toolCalls) }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    /** ContextRef 与请求级 assistant 消息绑定；切换会话时同步恢复当时实际使用的来源快照。 */
    private fun observeContextRefs() {
        viewModelScope.launch {
            selectedConversationId
                .flatMapLatest { conversationId ->
                    if (conversationId == null) flowOf(emptyList())
                    else repository.observeContextRefs(conversationId)
                }
                .collect { contextRefs ->
                    _uiState.update { it.copy(contextRefs = contextRefs) }
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
                contextRefs = emptyList(),
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

    /**
     * Chat 底栏切换联网策略。
     * FORCE 只武装下一条请求，不写入全局设置；发送后自动恢复当前持久化的 AUTO/OFF。
     */
    fun setWebSearchMode(value: WebSearchMode) {
        if (_uiState.value.isGenerating) return
        if (value == WebSearchMode.FORCE) {
            if (!_uiState.value.webSearchEnabled) return
            forceWebSearchNextRequest = true
            _uiState.update { it.copy(webSearchMode = WebSearchMode.FORCE) }
        } else {
            forceWebSearchNextRequest = false
            llmSettingsRepository.setWebSearchMode(value)
        }
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
        sendUserRequest(rawText, LlmExecutionTask.CHAT)
    }

    /**
     * 将 Quick Message 按点击瞬间的阅读上下文展开后，作为普通 USER / CHAT 请求发送。
     *
     * Quick Message 不进入 System Prompt，不改变 Skill 路由，也不会修改 Tool/MCP 权限；
     * 如果模板依赖的阅读变量当前不可用，则返回解析结果并拒绝发送，交给 UI 明确提示。
     */
    fun sendQuickMessage(message: LlmQuickMessage): LlmQuickMessageResolution {
        val currentArticle = articleContext.value
        val resolution =
            resolveQuickMessageTemplate(
                template = message.content,
                context =
                    LlmQuickMessageContext(
                        articleTitle = currentArticle?.title.orEmpty(),
                        articleUrl = currentArticle?.link,
                        selection = currentArticle?.selectedText,
                        summary = currentArticle?.summary,
                    ),
            )
        resolution.content?.takeIf { resolution.ready }?.let { resolved ->
            sendUserRequest(resolved, LlmExecutionTask.CHAT)
        }
        return resolution
    }

    /**
     * P6.3 一键 Article Analysis。
     *
     * 任务类型随 user message 持久化；后续重新生成、Tool 审批续接或进程恢复都能重新解析到
     * ARTICLE_ANALYSIS，而不是依赖一次性的 Compose flag 或根据提示词文本猜测任务。
     */
    fun analyzeArticle(rawText: String) {
        sendUserRequest(rawText, LlmExecutionTask.ARTICLE_ANALYSIS)
    }

    /** 普通 Ask 与一键分析共用同一条会话、Provider/Model、Tool、ContextRef 执行链。 */
    private fun sendUserRequest(
        rawText: String,
        requestTask: LlmExecutionTask,
    ) {
        val text = rawText.trim()
        val currentArticle = articleContext.value ?: return
        if (text.isBlank() || hasGenerationInFlight()) return
        if (_uiState.value.toolCalls.any { it.status == LlmToolCallStatus.PENDING_APPROVAL }) {
            _uiState.update { it.copy(transientError = "请先处理当前待确认的 Tool Call") }
            return
        }
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
                requestTask = requestTask,
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

    /** 用户打开手动 Tool 面板前重新读取 Registry，兼容其在 MCP 设置页刷新 Catalog 后返回 Chat 的场景。 */
    fun refreshManualTools() {
        if (!hasGenerationInFlight() && manualToolJob?.isCompleted != false) {
            refreshManualToolFallback()
        }
    }

    /**
     * 不支持标准 Tool Calling 的模型可以显式运行 MCP Tool；结果作为 TOOL_RESULT Context 附加。
     * JSON 参数先本地验证，真正执行仍经过 [LlmToolRuntime] 的 enabledToolIds / risk 门控。
     */
    fun runManualTool(toolId: String, rawArgumentsJson: String) {
        refreshManualToolFallback()
        val state = _uiState.value
        if (!state.manualToolFallbackAvailable || hasGenerationInFlight() || manualToolJob?.isCompleted == false) return
        val descriptor = state.manualTools.firstOrNull { it.id == toolId } ?: return
        val arguments = rawArgumentsJson.trim().ifBlank { "{}" }
        runCatching { org.json.JSONObject(arguments) }
            .onFailure {
                _uiState.update { current -> current.copy(transientError = "Tool 参数必须是有效 JSON Object") }
                return
            }
        val request =
            LlmPendingManualTool(
                callId = "manual:${UUID.randomUUID()}",
                descriptor = descriptor,
                argumentsJson = arguments,
            )
        startManualToolJob(request, confirmed = false)
    }

    /** 用户批准敏感/写入 Tool；Runtime 仍会再次检查确认标记，不能由 UI 直接越权。 */
    fun approveManualTool() {
        if (hasGenerationInFlight() || manualToolJob?.isCompleted == false) return
        val pending = _uiState.value.pendingManualTool ?: return
        startManualToolJob(pending, confirmed = true)
    }

    /** 拒绝手动 Tool 不调用远端，也不会产生 Tool Context。 */
    fun denyManualTool() {
        if (manualToolJob?.isCompleted == false) return
        _uiState.update { it.copy(pendingManualTool = null) }
    }

    /** Tool Context 是一次显式附件，用户可在发送前或后随时移除。 */
    fun removeManualToolContext(contextId: String) {
        if (hasGenerationInFlight()) return
        _uiState.update { state ->
            state.copy(manualToolContexts = state.manualToolContexts.filterNot { it.id == contextId })
        }
    }

    private fun startManualToolJob(
        request: LlmPendingManualTool,
        confirmed: Boolean,
    ) {
        if (manualToolJob?.isCompleted == false) return
        val articleId = articleContext.value?.articleId ?: return
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                _uiState.update { it.copy(manualToolRunning = true, transientError = null) }
                try {
                    val result =
                        toolRuntime.execute(
                            call =
                                LlmToolCall(
                                    id = request.callId,
                                    toolId = request.descriptor.id,
                                    argumentsJson = request.argumentsJson,
                                ),
                            profile = LlmExecutionProfile(enabledToolIds = setOf(request.descriptor.id)),
                            confirmed = confirmed,
                        )
                    // 切文章期间即使远端已经完成，也不允许把旧文章 Tool Result 附到新文章。
                    if (articleContext.value?.articleId != articleId) return@launch
                    when (result) {
                        is LlmToolResult.Success -> {
                            val context =
                                LlmManualToolContext(
                                    id = UUID.randomUUID().toString(),
                                    toolId = request.descriptor.id,
                                    toolName = request.descriptor.name,
                                    sourceId = request.descriptor.sourceId,
                                    content = result.content.ifBlank { "Tool completed without text output." },
                                )
                            _uiState.update {
                                it.copy(
                                    manualToolContexts = it.manualToolContexts + context,
                                    pendingManualTool = null,
                                )
                            }
                        }
                        is LlmToolResult.ConfirmationRequired -> {
                            _uiState.update { it.copy(pendingManualTool = request) }
                        }
                        is LlmToolResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    pendingManualTool = null,
                                    transientError = result.message,
                                )
                            }
                        }
                    }
                } finally {
                    manualToolJob = null
                    _uiState.update { it.copy(manualToolRunning = false) }
                }
            }
        manualToolJob = job
        job.start()
    }

    /** 用户明确允许敏感/写入 Tool 后才执行；执行结果完成后自动续接同一轮模型回复。 */
    fun approveToolCall(toolCallId: String) {
        if (hasGenerationInFlight()) return
        val call = _uiState.value.toolCalls.firstOrNull { it.id == toolCallId } ?: return
        if (call.status != LlmToolCallStatus.PENDING_APPROVAL) return
        startGenerationJob {
            executePersistedToolCall(call, confirmed = true)
            resumeAfterResolvedToolCalls(call.conversationId)
        }
    }

    /** 拒绝不会调用远端 Tool；拒绝结果仍回传模型，让模型可以解释限制或改用其他方式回答。 */
    fun denyToolCall(toolCallId: String) {
        if (hasGenerationInFlight()) return
        val call = _uiState.value.toolCalls.firstOrNull { it.id == toolCallId } ?: return
        if (call.status != LlmToolCallStatus.PENDING_APPROVAL) return
        startGenerationJob {
            repository.updateToolCall(
                toolCall = call,
                status = LlmToolCallStatus.DENIED,
                resultContent = "Tool execution was denied by the user.",
                errorMessage = null,
            )
            resumeAfterResolvedToolCalls(call.conversationId)
        }
    }

    /** 所有同轮 Tool 都已落到终态后再继续请求模型，避免跳过另一个仍待审批的 Tool。 */
    private suspend fun resumeAfterResolvedToolCalls(conversationId: String) {
        val calls = repository.getToolCalls(conversationId)
        if (calls.any { it.status == LlmToolCallStatus.PENDING_APPROVAL || it.status == LlmToolCallStatus.RUNNING }) {
            return
        }
        val completedRounds = calls.map(LlmToolCallEntity::assistantMessageId).distinct().size
        generateAssistant(
            conversationId = conversationId,
            allowWebSearch = false,
            toolRound = completedRounds.coerceAtLeast(1),
        )
    }

    /**
     * 执行已经持久化的 Tool Call，并把结果转换为可恢复的终态。
     * Runtime 仍会再次检查 enabledToolIds 与确认标记，UI 批准不能绕过执行层授权。
     */
    private suspend fun executePersistedToolCall(
        call: LlmToolCallEntity,
        confirmed: Boolean,
    ): LlmToolCallEntity {
        val running =
            repository.updateToolCall(
                toolCall = call,
                status = LlmToolCallStatus.RUNNING,
                resultContent = null,
                errorMessage = null,
            )
        val result =
            toolRuntime.execute(
                call =
                    LlmToolCall(
                        id = running.providerCallId,
                        toolId = running.toolId,
                        argumentsJson = running.argumentsJson,
                    ),
                profile = LlmExecutionProfile(enabledToolIds = setOf(running.toolId)),
                confirmed = confirmed,
            )
        return when (result) {
            is LlmToolResult.Success ->
                repository.updateToolCall(
                    toolCall = running,
                    status = LlmToolCallStatus.COMPLETE,
                    resultContent = result.content,
                    errorMessage = null,
                )
            is LlmToolResult.Failure ->
                repository.updateToolCall(
                    toolCall = running,
                    status = LlmToolCallStatus.ERROR,
                    resultContent = "Tool execution failed: ${result.message}",
                    errorMessage = result.message,
                )
            is LlmToolResult.ConfirmationRequired ->
                repository.updateToolCall(
                    toolCall = running,
                    status = LlmToolCallStatus.PENDING_APPROVAL,
                    resultContent = null,
                    errorMessage = null,
                )
        }
    }

    /**
     * 从 Room 的消息 + Tool Call 两张表重建 OpenAI-compatible 历史拓扑。
     * Tool result 不单独存成聊天气泡，避免污染用户可见消息；Provider 历史仍严格保持
     * assistant(tool_calls) → tool(tool_call_id) 的结构。
     */
    private suspend fun buildRequestHistory(
        conversationId: String,
        excludedAssistantId: String,
    ): List<LlmChatRequestMessage> {
        val messages = repository.getMessages(conversationId)
        val callsByAssistant = repository.getToolCalls(conversationId).groupBy(LlmToolCallEntity::assistantMessageId)
        return buildList {
            messages.forEach { message ->
                if (message.id == excludedAssistantId || message.role == LlmChatRole.SYSTEM) return@forEach
                if (message.status == LlmMessageStatus.ERROR) return@forEach
                // TOOL 只属于传输拓扑；本地 UI/Room 不单独保存 Tool result 消息。
                if (message.role == LlmChatRole.TOOL) return@forEach
                val calls = callsByAssistant[message.id].orEmpty()
                if (message.content.isBlank() && calls.isEmpty()) return@forEach

                add(
                    LlmChatRequestMessage(
                        role = message.role,
                        content = message.content,
                        toolCalls =
                            if (message.role == LlmChatRole.ASSISTANT) {
                                calls.map { call ->
                                    LlmChatRequestToolCall(
                                        id = call.providerCallId,
                                        name = call.apiName,
                                        argumentsJson = call.argumentsJson,
                                    )
                                }
                            } else {
                                emptyList()
                            },
                    )
                )

                if (message.role == LlmChatRole.ASSISTANT) {
                    calls.forEach { call ->
                        val result =
                            when (call.status) {
                                LlmToolCallStatus.COMPLETE -> call.resultContent.orEmpty()
                                LlmToolCallStatus.DENIED ->
                                    call.resultContent ?: "Tool execution was denied by the user."
                                LlmToolCallStatus.ERROR ->
                                    call.resultContent
                                        ?: "Tool execution failed: ${call.errorMessage.orEmpty()}"
                                LlmToolCallStatus.PENDING_APPROVAL,
                                LlmToolCallStatus.RUNNING -> null
                            }
                        result?.let { content ->
                            add(
                                LlmChatRequestMessage(
                                    role = LlmChatRole.TOOL,
                                    content = content,
                                    toolCallId = call.providerCallId,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 将持久化历史转换为模型消息并消费 SSE 增量。
     * 中途取消保存 STOPPED，服务错误保存 ERROR，正常结束保存 COMPLETE。
     */
    private suspend fun generateAssistant(
        conversationId: String,
        allowWebSearch: Boolean = true,
        toolRound: Int = 0,
    ) {
        if (toolRound > MAX_AUTOMATIC_TOOL_ROUNDS) {
            _uiState.update { it.copy(transientError = "Tool Calling 超过安全轮次限制") }
            return
        }
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
        val toolCallParts = sortedMapOf<Int, MutableToolCallPart>()
        var continueAfterTools = false

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
            val history = buildRequestHistory(conversationId, assistant.id)
            val selection = runtimeSelection.value
            val currentArticle =
                articleContext.value ?: error("当前文章上下文已失效，请重新打开阅读助手")
            val advancedSettings = llmSettingsRepository.current()
            val requestTask =
                repository.getMessages(conversationId)
                    .lastOrNull { it.role == LlmChatRole.USER }
                    ?.requestTask
                    ?: LlmExecutionTask.CHAT
            val latestUserInput =
                history.lastOrNull { it.role == LlmChatRole.USER }?.content.orEmpty()
            val requestSkillId =
                resolveRequestSkillId(
                    requestTask = requestTask,
                    autoChatSkillId =
                        if (requestTask == LlmExecutionTask.CHAT) {
                            skillRouter.resolve(latestUserInput)?.id
                        } else {
                            null
                        },
                    articleAnalysisSkillId =
                        if (
                            requestTask == LlmExecutionTask.ARTICLE_ANALYSIS &&
                                advancedSettings.skillsEnabled
                        ) {
                            skillRepository.boundSkill(LlmSkillTask.ARTICLE_ANALYSIS)?.id
                        } else {
                            null
                        },
                )
            val effectiveWebSearchMode =
                if (allowWebSearch && forceWebSearchNextRequest) WebSearchMode.FORCE
                else if (allowWebSearch) advancedSettings.webSearchMode
                else WebSearchMode.OFF
            if (allowWebSearch && forceWebSearchNextRequest) {
                // 一次性强制联网在请求开始时即消费；即使搜索失败也不能污染之后的消息。
                forceWebSearchNextRequest = false
                _uiState.update { it.copy(webSearchMode = advancedSettings.webSearchMode) }
            }
            val webSearch =
                webSearchRouter.searchIfNeeded(
                    enabled = allowWebSearch && advancedSettings.webSearchEnabled,
                    mode = effectiveWebSearchMode,
                    userInput = latestUserInput,
                    articleTitle = currentArticle.title,
                )
            val contextItems =
                buildArticleContextItems(currentArticle) +
                    _uiState.value.manualToolContexts.map(LlmManualToolContext::toContextItem) +
                    webSearch?.toContextItems().orEmpty()
            val enabledToolIds =
                if (advancedSettings.mcpEnabled) {
                    toolRuntime.descriptors()
                        .filter { it.source == LlmToolSource.MCP && it.enabled }
                        .map { it.id }
                        .toSet()
                } else {
                    emptySet()
                }
            val plan =
                llmRuntime.prepare(
                    profile =
                        LlmExecutionProfile(
                            task = requestTask,
                            providerId = selection.providerId,
                            model = selection.model,
                            skillId = requestSkillId,
                            customInstructions = advancedSettings.customInstructions,
                            enabledToolIds = enabledToolIds,
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
                    contextItems = contextItems,
                )

            // P6 ContextRef 必须在真正发请求前冻结。即使后续网络失败，错误消息也能解释当次请求准备使用了哪些来源；
            // 同一 assistant placeholder 若重新 prepare，则 Repository 事务替换旧快照，不留下半套来源。
            val contextRefCreatedAt = System.currentTimeMillis()
            val contextRefs =
                buildRequestContextRefEntities(
                    conversationId = conversationId,
                    assistantMessageId = assistant.id,
                    candidates = contextItems,
                    composed = plan.context,
                    toolCalls = repository.getToolCalls(conversationId),
                    createdAt = contextRefCreatedAt,
                )
            repository.replaceContextRefsForAssistant(
                assistantMessageId = assistant.id,
                contextRefs = contextRefs,
            )

            fallbackPromptTokens = transport.estimateRequestTokens(plan, history)
            requestStartedAtNanos = System.nanoTime()

            transport.stream(plan, history).collect { delta ->
                content += delta.content
                reasoning += delta.reasoning
                mergeToolCallDeltas(toolCallParts, delta.toolCalls)
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

            if (content.isBlank() && toolCallParts.isEmpty()) {
                error("AI 服务没有返回可显示内容")
            }
            assistant = repository.updateMessage(
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

            if (toolCallParts.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val calls =
                    toolCallParts.values.map { part ->
                        val providerCallId = part.id?.takeIf(String::isNotBlank)
                            ?: error("AI Tool Call 缺少 id")
                        val apiName = part.name?.takeIf(String::isNotBlank)
                            ?: error("AI Tool Call 缺少 function name")
                        val descriptor = resolveToolByApiName(plan.tools, apiName)
                        val arguments = part.arguments.toString().ifBlank { "{}" }
                        // Function Calling 参数必须是 JSON Object；第三方兼容服务返回破损参数时禁止执行。
                        runCatching { org.json.JSONObject(arguments) }
                            .getOrElse { error("Tool $apiName 返回了无效 arguments JSON") }
                        LlmToolCallEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            assistantMessageId = assistant.id,
                            providerCallId = providerCallId,
                            toolId = descriptor?.id ?: "unresolved:$apiName",
                            apiName = apiName,
                            argumentsJson = arguments,
                            status =
                                when {
                                    descriptor == null -> LlmToolCallStatus.ERROR
                                    descriptor.requiresConfirmation -> LlmToolCallStatus.PENDING_APPROVAL
                                    else -> LlmToolCallStatus.RUNNING
                                },
                            errorMessage = descriptor?.let { null } ?: "模型请求了当前未授权的 Tool：$apiName",
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                repository.appendToolCalls(calls)

                // 只读 Tool 可自动执行；敏感/写入 Tool 保持 Pending，等待用户明确批准。
                calls.filter { it.status == LlmToolCallStatus.RUNNING }.forEach { call ->
                    executePersistedToolCall(call, confirmed = false)
                }
                val refreshedCalls = repository.getToolCalls(conversationId)
                val hasPending = refreshedCalls.any { it.status == LlmToolCallStatus.PENDING_APPROVAL }
                val hasRunning = refreshedCalls.any { it.status == LlmToolCallStatus.RUNNING }
                continueAfterTools = !hasPending && !hasRunning
            }
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

        if (continueAfterTools) {
            generateAssistant(
                conversationId = conversationId,
                allowWebSearch = false,
                toolRound = toolRound + 1,
            )
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
        refreshManualToolFallback()
    }

    /**
     * 只有 MCP 已启用、有已加载 MCP Tool，且当前模型无法标准 Tool Calling 时才显示手动 Tool 入口。
     * 复用 Runtime 的 capability / override 解析，避免 UI 与真正请求链出现两套能力判断。
     */
    private fun refreshManualToolFallback() {
        val settings = llmSettingsRepository.current()
        val descriptors =
            if (settings.mcpEnabled) {
                toolRuntime.descriptors().filter { it.source == LlmToolSource.MCP && it.enabled }
            } else {
                emptyList()
            }
        if (descriptors.isEmpty()) {
            _uiState.update {
                it.copy(
                    manualToolFallbackAvailable = false,
                    manualTools = emptyList(),
                    pendingManualTool = null,
                )
            }
            return
        }

        val selection = runtimeSelection.value
        val available =
            runCatching {
                    val plan =
                        llmRuntime.prepare(
                            profile =
                                LlmExecutionProfile(
                                    providerId = selection.providerId,
                                    model = selection.model,
                                    enabledToolIds = descriptors.map(LlmToolDescriptor::id).toSet(),
                                    reasoningEffort = settings.reasoningEffort,
                                    capabilityOverride =
                                        if (settings.streamResponses) null
                                        else ModelCapabilityOverride(supportsStreaming = false),
                                ),
                        )
                    shouldExposeManualToolFallback(plan)
                }
                .getOrDefault(false)
        _uiState.update {
            it.copy(
                manualToolFallbackAvailable = available,
                manualTools = if (available) descriptors else emptyList(),
                pendingManualTool = if (available) it.pendingManualTool else null,
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

/** 单条用户请求最多允许的自动 Tool 往返轮数，防止模型与外部 Tool 进入无限循环。 */
private const val MAX_AUTOMATIC_TOOL_ROUNDS = 8

/** 用户显式运行的 Tool Result 优先级：摘要 130 > 译文 120 > 手动 Tool 115 > Web Search 110 > 原文 100。 */
private const val MANUAL_TOOL_CONTEXT_PRIORITY = 115

/**
 * 手动 Tool 入口只用于“Tool 已进入本轮执行计划，但模型本身不能标准 Tool Calling”的场景。
 * 独立成纯函数后可直接回归，避免 UI 与 Runtime 的能力结论出现两套判断。
 */
internal fun shouldExposeManualToolFallback(plan: me.ash.reader.llm.runtime.LlmExecutionPlan): Boolean =
    plan.tools.isNotEmpty() && !plan.automaticToolCalling

/**
 * P6.3 的任务级 Skill 选择必须与普通 Chat 自动路由互斥：
 * Article Analysis 只消费 P4 的固定 ARTICLE_ANALYSIS 绑定，不能被用户可见的分析请求文本误路由到其他 Skill。
 */
internal fun resolveRequestSkillId(
    requestTask: LlmExecutionTask,
    autoChatSkillId: String?,
    articleAnalysisSkillId: String?,
): String? =
    when (requestTask) {
        LlmExecutionTask.CHAT -> autoChatSkillId
        LlmExecutionTask.ARTICLE_ANALYSIS -> articleAnalysisSkillId
    }

/** Streaming Tool Call 的可变聚合状态；Provider 会把 arguments 拆成多个增量 chunk。 */
private data class MutableToolCallPart(
    var id: String? = null,
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
)

/** 按 Provider index 合并 Tool Call 增量；id/name 只在非空 chunk 到达时更新。 */
private fun mergeToolCallDeltas(
    parts: MutableMap<Int, MutableToolCallPart>,
    deltas: List<LlmChatToolCallDelta>,
) {
    deltas.forEach { delta ->
        val part = parts.getOrPut(delta.index) { MutableToolCallPart() }
        delta.id?.takeIf(String::isNotBlank)?.let { part.id = it }
        delta.name?.takeIf(String::isNotBlank)?.let { part.name = it }
        if (delta.argumentsDelta.isNotEmpty()) part.arguments.append(delta.argumentsDelta)
    }
}

private fun AiProviderProfile?.orEmptyModels(): List<String> = this?.availableModels().orEmpty()

/**
 * 当前选区、译文与摘要属于用户正在看的高相关派生内容，优先于长原文进入有限 Context；
 * 原文仍作为基础事实来源参与剩余预算，P2 ContextComposer 负责安全截断。
 */
internal fun buildArticleContextItems(context: ArticleAssistantContext): List<LlmContextItem> =
    buildList {
        context.selectedText?.trim()?.takeIf(String::isNotBlank)?.let { selection ->
            add(
                LlmContextItem(
                    id = "article:${context.articleId}:selection",
                    type = LlmContextType.SELECTED_TEXT,
                    title = context.title,
                    sourceId = context.link,
                    content = selection,
                    // 用户刚刚显式选中的正文与当前问题相关度最高，必须优先于摘要/译文/整篇正文进入预算。
                    priority = 160,
                )
            )
        }
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
