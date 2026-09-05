package me.ash.reader.llm.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.availableModels
import me.ash.reader.infrastructure.ai.resolvedDefaultModel
import me.ash.reader.llm.chat.data.LlmArticleCandidate
import me.ash.reader.llm.chat.data.LlmArticleCandidateRepository
import me.ash.reader.llm.chat.data.LlmChatRepository
import me.ash.reader.llm.chat.data.LlmChatRole
import me.ash.reader.llm.chat.data.LlmCitationRefEntity
import me.ash.reader.llm.chat.data.LlmCitationAnnotationWithRefs
import me.ash.reader.llm.chat.data.LlmContextRefEntity
import me.ash.reader.llm.chat.data.LlmConversationEntity
import me.ash.reader.llm.chat.data.LlmEvidenceSourceKind
import me.ash.reader.llm.chat.data.LlmMessageEntity
import me.ash.reader.llm.chat.data.LlmMessageCitationPresentation
import me.ash.reader.llm.chat.data.LlmMessageStatus
import me.ash.reader.llm.chat.data.LlmToolCallEntity
import me.ash.reader.llm.chat.data.LlmToolCallStatus
import me.ash.reader.llm.chat.data.LLM_EVIDENCE_CITATION_ENABLED
import me.ash.reader.llm.chat.data.LlmCitationProtocolEntry
import me.ash.reader.llm.chat.data.LlmArticleEvidenceSource
import me.ash.reader.llm.chat.data.buildArticleEvidenceBlocks
import me.ash.reader.llm.chat.data.buildEvidencePersistence
import me.ash.reader.llm.chat.data.buildCitationRefsFromAssistantOutput
import me.ash.reader.llm.chat.data.buildCitationPersistenceFromAssistantOutput
import me.ash.reader.llm.chat.data.CitationTransportAccumulator
import me.ash.reader.llm.chat.data.buildRequestContextRefEntities
import me.ash.reader.llm.chat.data.buildUnconsumedContextRefEntities
import me.ash.reader.llm.chat.data.prepareCitationProtocol
import me.ash.reader.llm.chat.data.stripHistoricalCitationProtocolTokens
import me.ash.reader.llm.chat.data.withBuiltEvidenceBlocks
import me.ash.reader.llm.chat.data.withSelectionEvidenceBlock
import me.ash.reader.llm.chat.data.withToolResultEvidenceBlock
import me.ash.reader.llm.chat.data.withWebSearchEvidenceBlock
import me.ash.reader.llm.chat.data.wrapCitationEvidenceContent
import me.ash.reader.llm.chat.runtime.LlmChatRequestMessage
import me.ash.reader.llm.chat.runtime.LlmChatRequestToolCall
import me.ash.reader.llm.chat.runtime.LlmChatToolCallDelta
import me.ash.reader.llm.chat.runtime.LlmChatTransport
import me.ash.reader.llm.chat.runtime.LlmFinishReason
import me.ash.reader.llm.chat.runtime.LlmGenerationTerminalDecision
import me.ash.reader.llm.chat.runtime.resolveLlmGenerationTerminalDecision
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
import me.ash.reader.llm.search.WebSearchException
import me.ash.reader.llm.search.WebSearchMode
import me.ash.reader.llm.search.WebSearchRequestStatus
import me.ash.reader.llm.search.WebSearchRouter
import me.ash.reader.llm.search.toContextItems
import me.ash.reader.llm.search.webSearchStatusAfterGenerationStopped
import me.ash.reader.llm.settings.LlmSettingsRepository
import me.ash.reader.llm.skill.LlmSkillRepository
import me.ash.reader.llm.skill.LlmSkillRouter
import me.ash.reader.llm.skill.LlmSkillTask
import me.ash.reader.ui.page.home.reading.ArticleAssistantContext

/** Chat 页面全部可观察状态；Provider/Model 继续复用现有 AI 设置，不另存密钥。 */
data class LlmChatUiState(
    val articleTitle: String? = null,
    /** P6.7 当前会话持久化的相关文章；真正请求仍统一转换为 Runtime LlmContextItem。 */
    val additionalArticleAttachments: List<LlmArticleAttachment> = emptyList(),
    val articleCandidates: List<LlmArticleCandidate> = emptyList(),
    val articleCandidatesLoading: Boolean = false,
    val articleCandidatesLoadFailed: Boolean = false,
    val conversations: List<LlmConversationEntity> = emptyList(),
    val currentConversationId: String? = null,
    val messages: List<LlmMessageEntity> = emptyList(),
    val toolCalls: List<LlmToolCallEntity> = emptyList(),
    val contextRefs: List<LlmContextRefEntity> = emptyList(),
    val citationRefs: List<LlmCitationRefEntity> = emptyList(),
    val citationAnnotations: List<LlmCitationAnnotationWithRefs> = emptyList(),
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
            toolCallId = id,
            toolId = toolId,
            toolName = toolName,
            toolSourceId = sourceId,
            priority = MANUAL_TOOL_CONTEXT_PRIORITY,
        ).let { item ->
            if (LLM_EVIDENCE_CITATION_ENABLED) item.withToolResultEvidenceBlock() else item
        }
}

/** 敏感/写入 Tool 的待确认请求；批准后 Runtime 仍会再次检查 allowed Tool 与 confirmed 标志。 */
data class LlmPendingManualTool(
    val callId: String,
    val descriptor: LlmToolDescriptor,
    val argumentsJson: String,
)

/** 单次 Provider 请求历史及其中真实可见的本地 ToolCall，二者必须来自同一次构建。 */
internal data class LlmRequestHistorySnapshot(
    val messages: List<LlmChatRequestMessage>,
    val toolCalls: List<LlmToolCallEntity>,
)

/**
 * 最近一条有效 USER 消息定义当前 Assistant/Tool 链。
 * 更早请求的 Tool Call 不能占用当前请求的自动调用轮次，也不能阻塞当前链恢复。
 */
internal fun currentToolChainCalls(
    messages: List<LlmMessageEntity>,
    toolCalls: List<LlmToolCallEntity>,
): List<LlmToolCallEntity> {
    val latestUserIndex =
        messages.indexOfLast { message ->
            message.historyActive &&
                message.role == LlmChatRole.USER &&
                message.status != LlmMessageStatus.ERROR
        }
    if (latestUserIndex < 0) return emptyList()
    val assistantIds =
        messages
            .drop(latestUserIndex + 1)
            .asSequence()
            .filter { it.historyActive && it.role == LlmChatRole.ASSISTANT }
            .map(LlmMessageEntity::id)
            .toSet()
    return toolCalls.filter { it.assistantMessageId in assistantIds }
}

/**
 * 从持久化消息与 ToolCall 构建一次 Provider 请求快照。
 * ERROR assistant、当前待生成 placeholder 与其他不可发送消息被过滤时，其 Tool Result 也必须同步排除。
 */
internal fun buildRequestHistorySnapshot(
    messages: List<LlmMessageEntity>,
    toolCalls: List<LlmToolCallEntity>,
    excludedAssistantId: String,
    citationFeatureEnabled: Boolean = LLM_EVIDENCE_CITATION_ENABLED,
): LlmRequestHistorySnapshot {
    val callsByAssistant = toolCalls.groupBy(LlmToolCallEntity::assistantMessageId)
    val visibleToolCalls = mutableListOf<LlmToolCallEntity>()
    val requestMessages = buildList {
        messages.forEach { message ->
            if (message.id == excludedAssistantId || message.role == LlmChatRole.SYSTEM) return@forEach
            if (!message.historyActive) return@forEach
            if (message.status == LlmMessageStatus.ERROR) return@forEach
            // TOOL 只属于传输拓扑；本地 UI/Room 不单独保存 Tool result 消息。
            if (message.role == LlmChatRole.TOOL) return@forEach
            val calls = callsByAssistant[message.id].orEmpty()
            if (message.content.isBlank() && calls.isEmpty()) return@forEach

            add(
                LlmChatRequestMessage(
                    role = message.role,
                    content =
                        if (citationFeatureEnabled && message.role == LlmChatRole.ASSISTANT) {
                            stripHistoricalCitationProtocolTokens(message.content)
                        } else {
                            message.content
                        },
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
                                call.resultContent ?: "Tool execution failed: ${call.errorMessage.orEmpty()}"
                            LlmToolCallStatus.PENDING_APPROVAL,
                            LlmToolCallStatus.RUNNING -> null
                        }
                    result?.let { content ->
                        visibleToolCalls += call
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
    return LlmRequestHistorySnapshot(
        messages = requestMessages,
        toolCalls = visibleToolCalls,
    )
}

internal fun applyToolResultCitationProtocol(
    history: List<LlmChatRequestMessage>,
    toolCalls: List<LlmToolCallEntity>,
    protocolEntries: List<LlmCitationProtocolEntry>,
): List<LlmChatRequestMessage> {
    val callsByLocalId = toolCalls.associateBy(LlmToolCallEntity::id)
    val providerPairs =
        protocolEntries.mapNotNull { entry ->
            val locator = entry.locatorSnapshot ?: return@mapNotNull null
            if (locator.sourceKind != LlmEvidenceSourceKind.TOOL_RESULT) return@mapNotNull null
            val localCallId = locator.toolCallId?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val call = callsByLocalId[localCallId] ?: return@mapNotNull null
            if (entry.contextId != "tool-result:${call.id}") return@mapNotNull null
            call.providerCallId to entry.protocolId
        }
    require(providerPairs.map(Pair<String, String>::first).distinct().size == providerPairs.size) {
        "Tool Result Citation protocol duplicated one provider_call_id"
    }
    if (providerPairs.isEmpty()) return history
    val protocolByProviderCallId = providerPairs.toMap()
    return history.map { message ->
        val protocolId =
            message
                .takeIf { it.role == LlmChatRole.TOOL }
                ?.toolCallId
                ?.let(protocolByProviderCallId::get)
        if (protocolId == null) {
            message
        } else {
            message.copy(content = wrapCitationEvidenceContent(message.content, protocolId))
        }
    }
}

/**
 * Regenerate 只替代最后一条 USER 之后仍属于当前历史分支的 Assistant 链。
 * 旧消息本身不删除，后续通过 historyActive=false 从 Provider Prompt 中退出。
 */
internal fun regenerationSupersededAssistantIds(
    messages: List<LlmMessageEntity>,
): Set<String> {
    val lastUserIndex = messages.indexOfLast { it.role == LlmChatRole.USER }
    if (lastUserIndex < 0) return emptySet()
    return messages
        .drop(lastUserIndex + 1)
        .asSequence()
        .filter { it.role == LlmChatRole.ASSISTANT && it.historyActive }
        .map(LlmMessageEntity::id)
        .toSet()
}

/** 当前会话的运行时选择，只保存 Provider/Model 标识，不持有 API Key。 */
private data class RuntimeSelection(
    val providerId: String? = null,
    val model: String? = null,
)

/**
 * UI 的单条瞬态 presentation 覆盖。
 *
 * Streaming 只有 message；terminal Citation finalize 同时携带完整 refs/annotations。null 表示“不覆盖
 * Citation graph”，emptyList 表示“明确覆盖为空”，两者语义不同。
 */
internal data class TransientChatPresentationOverride(
    val message: LlmMessageEntity,
    val citationRefs: List<LlmCitationRefEntity>? = null,
    val citationAnnotations: List<LlmCitationAnnotationWithRefs>? = null,
) {
    init {
        require((citationRefs == null) == (citationAnnotations == null)) {
            "Transient Citation refs and annotations must be supplied together"
        }
    }
}

/** Room 持久化消息与当前内存流式覆盖的组合快照。 */
private data class ChatMessagePresentationSnapshot(
    val conversationId: String?,
    val persistedPresentation: List<LlmMessageCitationPresentation>,
    val transientOverrides: Map<String, TransientChatPresentationOverride>,
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
    private val articleCandidateRepository: LlmArticleCandidateRepository,
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
    /**
     * 当前流式 Assistant 的内存消息覆盖。
     *
     * UI 不再依赖每次 Room persistence 才能看到增量；终态消息会继续保留到 Room 明确回读出
     * 完全相同的实体后才移除，避免较旧的数据库 emission 把新内容短暂覆盖回去。
     */
    private val transientMessageOverrides =
        MutableStateFlow<Map<String, TransientChatPresentationOverride>>(emptyMap())
    private var forceWebSearchNextRequest = false
    private var generationJob: Job? = null
    private var manualToolJob: Job? = null
    private var articleCandidateJob: Job? = null
    private var articleCandidateRequestRevision = 0L
    private var conversationSelectionInitialized = false
    /** 会话/文章边界修订号；异步候选加载必须命中同一修订号，禁止旧点击串入新会话。 */
    private var conversationSelectionRevision = 0L
    private val attachmentPersistenceMutex = Mutex()

    /** Citation 跳转失败兜底按历史 Assistant Message 直接读取冻结来源，不依赖当前会话选择。 */
    internal suspend fun loadContextRefsForAssistant(
        assistantMessageId: String,
    ): List<LlmContextRefEntity> = repository.getContextRefsForAssistant(assistantMessageId)

    /** 与 ContextRef 成对读取历史 CitationRef，跨文章后仍可恢复原回答的来源详情。 */
    internal suspend fun loadCitationRefsForAssistant(
        assistantMessageId: String,
    ): List<LlmCitationRefEntity> = repository.getCitationRefsForAssistant(assistantMessageId)

    /**
     * A Reader -> Chat target can disappear while navigation is in flight (conversation delete or
     * regenerate). Query Room directly so the UI can distinguish a real missing target from the
     * normal transient empty state before conversation/message Flows have delivered their first row.
     */
    internal suspend fun citationReturnTargetExists(
        ownerArticleId: String,
        conversationId: String,
        assistantMessageId: String,
        citationId: String? = null,
        annotationId: String? = null,
    ): Boolean {
        val conversation = repository.getConversation(conversationId) ?: return false
        if (conversation.articleId != ownerArticleId) return false
        if (citationId != null && repository.getCitationRefsForAssistant(assistantMessageId).none {
                it.id == citationId && it.conversationId == conversationId
            }) return false
        if (annotationId != null && repository.getCitationAnnotationsForAssistant(assistantMessageId).none {
                it.annotation.id == annotationId
            }) return false
        return repository.getMessages(conversationId).any { message ->
            message.id == assistantMessageId &&
                message.role == LlmChatRole.ASSISTANT &&
                message.historyActive &&
                message.status == LlmMessageStatus.COMPLETE
        }
    }

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
                if (!settings.assistantEnabled) {
                    stopGeneration()
                }
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
            clearOneShotWebSearchOverride()
            manualToolJob?.cancel(CancellationException("文章已切换"))
            articleCandidateJob?.cancel(CancellationException("文章已切换"))
            clearTransientStreamingMessages()
            nextConversationSelectionRevision()
            conversationSelectionInitialized = false
            selectedConversationId.value = null
            _uiState.update {
                it.copy(
                    articleTitle = context.title,
                    additionalArticleAttachments = emptyList(),
                    articleCandidates = emptyList(),
                    articleCandidatesLoading = false,
                    articleCandidatesLoadFailed = false,
                    currentConversationId = null,
                    conversations = emptyList(),
                    messages = emptyList(),
                    contextRefs = emptyList(),
                    citationRefs = emptyList(),
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
                            selectConversationInternal(
                                conversations.first().id,
                                nextConversationSelectionRevision(),
                            )
                        }
                    } else if (selectedId != null && conversations.none { it.id == selectedId }) {
                        clearConversationScopedTransientContext()
                        selectConversationInternal(
                            conversations.firstOrNull()?.id,
                            nextConversationSelectionRevision(),
                        )
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
                    val persistedPresentation =
                        if (conversationId == null) flowOf(emptyList())
                        else repository.observeMessageCitationPresentation(conversationId)
                    persistedPresentation.combine(transientMessageOverrides) { presentation, overrides ->
                        ChatMessagePresentationSnapshot(
                            conversationId = conversationId,
                            persistedPresentation = presentation,
                            transientOverrides = overrides,
                        )
                    }
                }
                .collect { snapshot ->
                    val persistedMessages = snapshot.persistedPresentation.map(LlmMessageCitationPresentation::message)
                    val messageOverrides = snapshot.transientOverrides.mapValues { it.value.message }
                    val citationOverrideIds =
                        snapshot.transientOverrides.values
                            .filter { it.citationRefs != null }
                            .mapTo(mutableSetOf()) { it.message.id }
                    val citationRefs =
                        (snapshot.persistedPresentation
                            .filterNot { it.message.id in citationOverrideIds }
                            .flatMap(LlmMessageCitationPresentation::citationRefs) +
                            snapshot.transientOverrides.values.flatMap { it.citationRefs.orEmpty() })
                            .sortedWith(
                                compareBy<LlmCitationRefEntity> { it.createdAt }
                                    .thenBy { it.assistantMessageId }
                                    .thenBy { it.displayOrder ?: Int.MAX_VALUE }
                                    .thenBy { it.id }
                            )
                    val citationAnnotations =
                        (snapshot.persistedPresentation
                            .filterNot { it.message.id in citationOverrideIds }
                            .flatMap(LlmMessageCitationPresentation::citationAnnotations) +
                            snapshot.transientOverrides.values.flatMap { it.citationAnnotations.orEmpty() })
                            .sortedWith(
                                compareBy<LlmCitationAnnotationWithRefs> { it.annotation.assistantMessageId }
                                    .thenBy { it.annotation.occurrenceOrdinal }
                                    .thenBy { it.annotation.id }
                            )
                    _uiState.update {
                        it.copy(
                            messages =
                                visibleChatPresentationMessages(
                                    mergeChatMessagesWithTransientOverrides(
                                        persistedMessages = persistedMessages,
                                        transientOverrides = messageOverrides,
                                        conversationId = snapshot.conversationId,
                                    )
                                ),
                            citationRefs = citationRefs,
                            citationAnnotations = citationAnnotations,
                        )
                    }

                    // 终态覆盖只有在 Room 已回读到完全相同实体后才释放，防止旧 emission 回闪。
                    val acknowledgedIds =
                        acknowledgedChatTransientPresentationIds(
                            persistedPresentation = snapshot.persistedPresentation,
                            transientOverrides = snapshot.transientOverrides,
                            conversationId = snapshot.conversationId,
                        )
                    if (acknowledgedIds.isNotEmpty()) {
                        transientMessageOverrides.update { current ->
                            current.filterKeys { it !in acknowledgedIds }
                        }
                    }
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
        nextConversationSelectionRevision()
        clearConversationScopedTransientContext()
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
                citationRefs = emptyList(),
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
        val selectionRevision = nextConversationSelectionRevision()
        clearConversationScopedTransientContext()
        viewModelScope.launch { selectConversationInternal(conversationId, selectionRevision) }
    }

    /**
     * 清空不具备跨会话持久身份的临时 Context。
     *
     * 多文章附件会在目标历史会话中从 Room 重新恢复；手动 Tool Context 只是当前会话内存附件，不能静默继承。
     */
    private fun clearConversationScopedTransientContext() {
        clearOneShotWebSearchOverride()
        manualToolJob?.cancel(CancellationException("会话已切换"))
        clearTransientStreamingMessages(selectedConversationId.value)
        _uiState.update {
            it.copy(
                additionalArticleAttachments = emptyList(),
                manualToolContexts = emptyList(),
                pendingManualTool = null,
                manualToolRunning = false,
            )
        }
    }

    /** FORCE 只属于当前会话的下一次发送；跨文章/会话边界必须恢复持久化 AUTO/OFF。 */
    private fun clearOneShotWebSearchOverride() {
        if (!forceWebSearchNextRequest) return
        forceWebSearchNextRequest = false
        val persistedMode = llmSettingsRepository.current().webSearchMode
        _uiState.update { it.copy(webSearchMode = persistedMode) }
    }

    /**
     * 将一篇相关文章附加到当前阅读会话，并在已创建会话中立即持久化。
     * 生成中或存在待确认 Tool Call 时不允许修改，保证同一请求及 Tool 续接轮次的 Context 集合稳定。
     */
    fun attachAdditionalArticle(attachment: LlmArticleAttachment) {
        val currentArticleId = articleContext.value?.articleId ?: return
        if (hasGenerationInFlight()) return
        if (_uiState.value.toolCalls.any { it.status == LlmToolCallStatus.PENDING_APPROVAL }) return
        val current = _uiState.value.additionalArticleAttachments
        if (
            current.size >= MAX_ADDITIONAL_ARTICLES &&
                current.none { it.articleId == attachment.articleId.trim() }
        ) {
            return
        }
        val next =
            upsertAdditionalArticleAttachment(
                currentArticleId = currentArticleId,
                existing = current,
                attachment = attachment,
            )
        if (next == current) return
        _uiState.update { it.copy(additionalArticleAttachments = next) }
        persistAdditionalArticlesAsync()
    }

    /** 从本地 Reader 候选附加文章；只有用户明确点击后才单独读取正文并进入 Chat Context。 */
    fun attachArticleCandidate(candidate: LlmArticleCandidate) {
        val currentArticleId = articleContext.value?.articleId ?: return
        val selectionRevision = conversationSelectionRevision
        if (hasGenerationInFlight()) return
        if (_uiState.value.toolCalls.any { it.status == LlmToolCallStatus.PENDING_APPROVAL }) return
        val current = _uiState.value.additionalArticleAttachments
        if (
            current.size >= MAX_ADDITIONAL_ARTICLES &&
                current.none { it.articleId == candidate.articleId }
        ) {
            return
        }
        viewModelScope.launch {
            val snapshot = articleCandidateRepository.loadArticleSnapshot(candidate.articleId) ?: return@launch
            if (
                articleContext.value?.articleId != currentArticleId ||
                    conversationSelectionRevision != selectionRevision ||
                    hasGenerationInFlight()
            ) {
                return@launch
            }
            if (_uiState.value.toolCalls.any { it.status == LlmToolCallStatus.PENDING_APPROVAL }) return@launch
            attachAdditionalArticle(
                LlmArticleAttachment(
                    articleId = snapshot.articleId,
                    title = snapshot.title,
                    link = snapshot.link,
                    originalContent = snapshot.originalContent,
                )
            )
        }
    }

    /** 移除指定附加文章；当前主文章不在该列表中，因此不会被此操作影响。 */
    fun removeAdditionalArticle(articleId: String) {
        if (hasGenerationInFlight()) return
        if (_uiState.value.toolCalls.any { it.status == LlmToolCallStatus.PENDING_APPROVAL }) return
        val normalizedId = articleId.trim()
        if (normalizedId.isBlank()) return
        val current = _uiState.value.additionalArticleAttachments
        val next = current.filterNot { it.articleId == normalizedId }
        if (next == current) return
        _uiState.update { it.copy(additionalArticleAttachments = next) }
        persistAdditionalArticlesAsync()
    }

    /** 清空当前会话的全部附加文章；历史 Assistant 已冻结的 ContextRef 不受影响。 */
    fun clearAdditionalArticles() {
        if (hasGenerationInFlight()) return
        if (_uiState.value.toolCalls.any { it.status == LlmToolCallStatus.PENDING_APPROVAL }) return
        if (_uiState.value.additionalArticleAttachments.isEmpty()) return
        _uiState.update { it.copy(additionalArticleAttachments = emptyList()) }
        persistAdditionalArticlesAsync()
    }

    /**
     * 加载多文章选择器候选。空查询返回“最近文章”（发布时间倒序）；非空查询只搜索标题。
     * 新搜索会取消旧查询，避免用户快速输入时较慢的旧结果覆盖较新的关键字结果。
     */
    fun loadArticleCandidates(query: String = "") {
        val currentArticleId = articleContext.value?.articleId ?: return
        val normalizedQuery = query.trim()
        val requestRevision = ++articleCandidateRequestRevision
        articleCandidateJob?.cancel()
        _uiState.update {
            it.copy(
                articleCandidatesLoading = true,
                articleCandidatesLoadFailed = false,
            )
        }
        articleCandidateJob =
            viewModelScope.launch {
                try {
                    val candidates =
                        if (normalizedQuery.isBlank()) {
                            articleCandidateRepository.recentArticles(currentArticleId)
                        } else {
                            articleCandidateRepository.searchArticles(currentArticleId, normalizedQuery)
                        }
                    if (
                        articleContext.value?.articleId != currentArticleId ||
                            articleCandidateRequestRevision != requestRevision
                    ) {
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            articleCandidates = candidates,
                            articleCandidatesLoading = false,
                            articleCandidatesLoadFailed = false,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (
                        articleContext.value?.articleId == currentArticleId &&
                            articleCandidateRequestRevision == requestRevision
                    ) {
                        _uiState.update {
                            it.copy(
                                articleCandidates = emptyList(),
                                articleCandidatesLoading = false,
                                articleCandidatesLoadFailed = true,
                            )
                        }
                    }
                }
            }
    }

    /**
     * 已创建会话中的附件变更立即持久化；每次真正写入前都读取最新 UI 集合，避免快速增删时旧协程覆盖新状态。
     */
    private fun persistAdditionalArticlesAsync() {
        val conversationId = selectedConversationId.value ?: return
        viewModelScope.launch {
            attachmentPersistenceMutex.withLock {
                if (selectedConversationId.value != conversationId) return@withLock
                persistAdditionalArticlesNow(
                    conversationId = conversationId,
                    attachments = _uiState.value.additionalArticleAttachments,
                )
            }
        }
    }

    /** 将当前活动附件完整替换到 Room；历史消息的 ContextRef 不参与此操作。 */
    private suspend fun persistAdditionalArticlesNow(
        conversationId: String,
        attachments: List<LlmArticleAttachment>,
    ) {
        val currentArticleId = articleContext.value?.articleId ?: return
        val normalized = normalizedAdditionalArticleAttachments(currentArticleId, attachments)
        val now = System.currentTimeMillis()
        repository.replaceConversationArticles(
            conversationId = conversationId,
            articles =
                normalized.mapIndexed { index, attachment ->
                    attachment.toConversationArticleEntity(
                        conversationId = conversationId,
                        position = index,
                        createdAt = now,
                    )
                },
        )
    }

    /** 推进会话边界修订号，用于废弃切换前启动的异步候选加载/会话恢复。 */
    private fun nextConversationSelectionRevision(): Long {
        conversationSelectionRevision += 1
        return conversationSelectionRevision
    }

    /** 从数据库加载会话运行时选择；已被删除/禁用的 Provider 回退到当前默认配置。 */
    private suspend fun selectConversationInternal(
        conversationId: String?,
        selectionRevision: Long,
    ) {
        val conversation = conversationId?.let { repository.getConversation(it) }
        val currentArticleId = articleContext.value?.articleId
        if (conversation != null && conversation.articleId != currentArticleId) return
        val restoredAttachments =
            conversation
                ?.let { repository.getConversationArticles(it.id) }
                .orEmpty()
                .map { it.toArticleAttachment() }
                .let { attachments ->
                    currentArticleId?.let { normalizedAdditionalArticleAttachments(it, attachments) }.orEmpty()
                }
        if (conversationSelectionRevision != selectionRevision) return
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
                additionalArticleAttachments = restoredAttachments,
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
        val quickMessageText = quickMessageRepository.resolveText(message)
        val resolution =
            resolveQuickMessageTemplate(
                template = quickMessageText.content,
                context =
                    LlmQuickMessageContext(
                        articleTitle = currentArticle?.title.orEmpty(),
                        articleUrl = currentArticle?.link,
                        selection = currentArticle?.selectedText,
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
            if (requestTask == LlmExecutionTask.CHAT) {
                val refreshedAttachments =
                    refreshAdditionalArticleAttachmentsForRequest(currentArticle.articleId)
                if (refreshedAttachments == null) {
                    _uiState.update {
                        it.copy(transientError = "相关文章正文读取失败，请移除该文章后重试")
                    }
                    return@startGenerationJob
                }
                if (refreshedAttachments != _uiState.value.additionalArticleAttachments) {
                    _uiState.update { it.copy(additionalArticleAttachments = refreshedAttachments) }
                }
            }
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
                            // A user send is an explicit conversation selection. Invalidate any
                            // initial Room restoration that may still be suspended in
                            // selectConversationInternal(), otherwise that older restore can resume
                            // after this create and switch the UI away from the conversation that is
                            // currently generating the first answer.
                            nextConversationSelectionRevision()
                            conversationSelectionInitialized = true
                            selectedConversationId.value = createdId
                            _uiState.update { it.copy(currentConversationId = createdId) }
                        }

            // 首条消息创建会话后立刻冻结当前活动附件集合；已有会话则同步兜底最新内存状态。
            attachmentPersistenceMutex.withLock {
                persistAdditionalArticlesNow(
                    conversationId = conversationId,
                    attachments = _uiState.value.additionalArticleAttachments,
                )
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
     * R07 multi-article citations require the frozen LLM attachment to be the exact document Reader
     * will render. Older conversations may still contain pre-fix rawDescription snapshots, so refresh
     * them lazily at the next CHAT request instead of doing network/cache work merely by opening Chat.
     */
    private suspend fun refreshAdditionalArticleAttachmentsForRequest(
        currentArticleId: String,
    ): List<LlmArticleAttachment>? {
        val current =
            normalizedAdditionalArticleAttachments(
                currentArticleId = currentArticleId,
                attachments = _uiState.value.additionalArticleAttachments,
            )
        if (current.isEmpty()) return emptyList()
        return buildList(current.size) {
            current.forEach { attachment ->
                val snapshot =
                    articleCandidateRepository.loadArticleSnapshot(attachment.articleId)
                        ?: return null
                add(
                    LlmArticleAttachment(
                        articleId = snapshot.articleId,
                        title = snapshot.title,
                        link = snapshot.link,
                        originalContent = snapshot.originalContent,
                    )
                )
            }
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
        clearTransientStreamingMessages(conversationId)
        startGenerationJob {
            val messages = repository.getMessages(conversationId)
            if (messages.none { it.role == LlmChatRole.USER }) return@startGenerationJob
            generateAssistant(
                conversationId = conversationId,
                supersededHistoryAssistantIds = regenerationSupersededAssistantIds(messages),
            )
        }
    }

    /** 重命名指定会话。 */
    fun renameConversation(conversationId: String, title: String) {
        viewModelScope.launch { repository.renameConversation(conversationId, title) }
    }

    /** 删除指定会话；若正在生成该会话，先终止网络请求。 */
    fun deleteConversation(conversationId: String) {
        if (conversationId == selectedConversationId.value) {
            stopGeneration()
            nextConversationSelectionRevision()
            clearConversationScopedTransientContext()
        }
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
        val selectionRevision = conversationSelectionRevision
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
                    // 切文章/会话期间即使远端已经完成，也不允许把旧请求的 Tool Result 附到新上下文。
                    if (
                        articleContext.value?.articleId != articleId ||
                            conversationSelectionRevision != selectionRevision
                    ) {
                        return@launch
                    }
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
        val calls =
            currentToolChainCalls(
                messages = repository.getMessages(conversationId),
                toolCalls = repository.getToolCalls(conversationId),
            )
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
    ): LlmRequestHistorySnapshot =
        buildRequestHistorySnapshot(
            messages = repository.getMessages(conversationId),
            toolCalls = repository.getToolCalls(conversationId),
            excludedAssistantId = excludedAssistantId,
        )

    /**
     * 将持久化历史转换为模型消息并消费 SSE 增量。
     * 中途取消保存 STOPPED，服务错误保存 ERROR，正常结束保存 COMPLETE。
     */
    private suspend fun generateAssistant(
        conversationId: String,
        allowWebSearch: Boolean = true,
        toolRound: Int = 0,
        supersededHistoryAssistantIds: Set<String> = emptySet(),
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
        val perfTrace = LlmChatPerfTracker.start(assistant.id, toolRound)
        var content = ""
        var reasoning = ""
        var lastUiPublishAt = 0L
        var lastPersistAt = 0L
        var requestStartedAtNanos: Long? = null
        var fallbackPromptTokens: Int? = null
        var providerPromptTokens: Int? = null
        var providerCompletionTokens: Int? = null
        var finishReason: LlmFinishReason? = null
        var requestCitationEntries: List<LlmCitationProtocolEntry> = emptyList()
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

        suspend fun finalizeCitationMessage(
            message: LlmMessageEntity,
            transportContent: String,
        ): TransientChatPresentationOverride {
            if (!LLM_EVIDENCE_CITATION_ENABLED) {
                return TransientChatPresentationOverride(
                    message = repository.updateMessage(message = message)
                )
            }
            val built =
                buildCitationPersistenceFromAssistantOutput(
                    assistantText = transportContent,
                    allowedEntries = requestCitationEntries,
                    conversationId = conversationId,
                    assistantMessageId = assistant.id,
                )
            val canonicalMessage = message.copy(content = built.canonicalText)
            repository.finalizeAssistantCitationState(
                message = canonicalMessage,
                citationRefs = built.refs,
                annotations = built.annotations,
                annotationRefs = built.annotationRefs,
            )
            if (built.invalidProtocolIds.isNotEmpty()) {
                LlmChatPerfTracker.mark(
                    assistant.id,
                    "citation_invalid_protocol_ids",
                    "ids" to built.invalidProtocolIds.joinToString(","),
                )
            }
            val refsById = built.refs.associateBy(LlmCitationRefEntity::id)
            val presentationAnnotations =
                built.annotations.map { annotation ->
                    val junctionRows = built.annotationRefs.filter { it.annotationId == annotation.id }
                    LlmCitationAnnotationWithRefs(
                        annotation,
                        junctionRows.mapNotNull { refsById[it.citationRefId] },
                        junctionRows,
                    )
                }
            return TransientChatPresentationOverride(
                message = canonicalMessage,
                citationRefs = built.refs,
                citationAnnotations = presentationAnnotations,
            )
        }

        try {
            if (supersededHistoryAssistantIds.isNotEmpty()) {
                repository.setMessagesHistoryActive(
                    messageIds = supersededHistoryAssistantIds,
                    active = false,
                )
            }
            val requestPrepareStartedAtNanos = System.nanoTime()
            val historySnapshot = buildRequestHistory(conversationId, assistant.id)
            val history = historySnapshot.messages
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
            val webSearchEnabled = allowWebSearch && advancedSettings.webSearchEnabled
            val preparedWebSearch =
                webSearchRouter.prepareSearch(
                    enabled = webSearchEnabled,
                    mode = effectiveWebSearchMode,
                    userInput = latestUserInput,
                    articleTitle = currentArticle.title,
                )
            LlmChatPerfTracker.mark(
                assistant.id,
                "request_prepare_complete",
                "durationMs" to
                    ((System.nanoTime() - requestPrepareStartedAtNanos) / 1_000_000L)
                        .coerceAtLeast(0L),
                "task" to requestTask.name,
                "historyMessages" to history.size,
                "historyToolCalls" to historySnapshot.toolCalls.size,
                "additionalArticles" to _uiState.value.additionalArticleAttachments.size,
                "searchMode" to effectiveWebSearchMode.name,
                "searchTriggered" to preparedWebSearch.triggered,
            )
            // 在真正执行网络请求前冻结 TRIGGERED/query/Provider；UI 展示值与随后真正执行的请求共用同一份计划。
            assistant =
                repository.updateMessage(
                    message = assistant,
                    webSearchStatus = preparedWebSearch.decision.status,
                    webSearchQuery = preparedWebSearch.query,
                    webSearchProviderName = preparedWebSearch.providerName,
                    webSearchErrorMessage = null,
                )
            val searchStartedAtNanos = System.nanoTime()
            val webSearchRoute =
                webSearchRouter.executePreparedSearch(preparedWebSearch)
            LlmChatPerfTracker.mark(
                assistant.id,
                "search_stage_complete",
                "durationMs" to
                    ((System.nanoTime() - searchStartedAtNanos) / 1_000_000L)
                        .coerceAtLeast(0L),
                "status" to webSearchRoute.status.name,
                "requiredFailure" to webSearchRoute.requiredFailure,
            )
            val webSearch = webSearchRoute.response
            val webSearchContextItems =
                webSearch
                    ?.toContextItems()
                    .orEmpty()
                    .map { item ->
                        if (LLM_EVIDENCE_CITATION_ENABLED) item.withWebSearchEvidenceBlock() else item
                    }
            val searchEvidenceCreatedAt = System.currentTimeMillis()
            val unconsumedSearchRefs =
                if (webSearchRoute.status == WebSearchRequestStatus.SUCCESS) {
                    buildUnconsumedContextRefEntities(
                        conversationId = conversationId,
                        assistantMessageId = assistant.id,
                        candidates = webSearchContextItems,
                        createdAt = searchEvidenceCreatedAt,
                    )
                } else {
                    emptyList()
                }
            // Search 一旦产生终态，就先把“终态 + 已取得结果快照”作为一个 Room 事务冻结。
            // 用户在 Provider 已返回后立即 Stop 时，也不能把真实 SUCCESS/FAILED 重新降成 TRIGGERED。
            withContext(NonCancellable) {
                assistant =
                    repository.finalizeWebSearch(
                        message = assistant,
                        status = webSearchRoute.status,
                        providerName = webSearchRoute.providerName,
                        errorMessage = webSearchRoute.errorMessage,
                        contextRefs = unconsumedSearchRefs,
                    )
            }
            if (webSearchRoute.requiredFailure) {
                throw WebSearchException(
                    webSearchRoute.errorMessage ?: "Web Search 强制联网失败"
                )
            }
            val additionalArticleAttachments = _uiState.value.additionalArticleAttachments
            val manualToolContexts = _uiState.value.manualToolContexts
            val contextItems =
                withContext(Dispatchers.Default) {
                    buildRequestArticleContextItems(
                        currentArticle = currentArticle,
                        attachments = additionalArticleAttachments,
                        // 一键 Article Analysis 语义仍是“分析当前文章”；多文章比较只进入普通 CHAT。
                        includeAdditionalArticles = requestTask == LlmExecutionTask.CHAT,
                    ) +
                        manualToolContexts.map(LlmManualToolContext::toContextItem) +
                        webSearchContextItems
                }
            val enabledToolIds =
                if (advancedSettings.mcpEnabled) {
                    toolRuntime.descriptors()
                        .filter { it.source == LlmToolSource.MCP && it.enabled }
                        .map { it.id }
                        .toSet()
                } else {
                    emptySet()
                }
            val runtimePrepareStartedAtNanos = System.nanoTime()
            val plan =
                withContext(Dispatchers.Default) {
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
                        perfTrace = perfTrace,
                    )
                }
            LlmChatPerfTracker.mark(
                assistant.id,
                "runtime_prepare_complete",
                "durationMs" to
                    ((System.nanoTime() - runtimePrepareStartedAtNanos) / 1_000_000L)
                        .coerceAtLeast(0L),
                "contextCandidates" to contextItems.size,
                "contextIncluded" to plan.context.includedIds.size,
                "contextTruncated" to plan.context.truncated,
                "toolCount" to plan.tools.size,
            )

            // P6 ContextRef 必须在真正发请求前冻结。即使后续网络失败，错误消息也能解释当次请求准备使用了哪些来源；
            // 同一 assistant placeholder 若重新 prepare，则 Repository 事务替换旧快照，不留下半套来源。
            val contextRefCreatedAt = System.currentTimeMillis()
            val requestToolCalls = historySnapshot.toolCalls
            val frozenSearchRefsByContextId = unconsumedSearchRefs.associateBy { it.contextId }
            val contextRefs =
                buildRequestContextRefEntities(
                    conversationId = conversationId,
                    assistantMessageId = assistant.id,
                    candidates = contextItems,
                    composed = plan.context,
                    toolCalls = requestToolCalls,
                    createdAt = contextRefCreatedAt,
                    // R07 Evidence Citation persists exact block references in llm_citation_refs.
                    // Keep the legacy ContextRef [R#] index unset even after the new gate opens.
                    citationFeatureEnabled = false,
                ).map { ref ->
                    frozenSearchRefsByContextId[ref.contextId]?.let { frozen ->
                        // SUCCESS 后立即打开详情时，最终 usage 刷新复用同一冻结结果身份。
                        ref.copy(id = frozen.id, createdAt = frozen.createdAt)
                    } ?: ref
                }
            val evidenceState =
                if (LLM_EVIDENCE_CITATION_ENABLED) {
                    withContext(Dispatchers.Default) {
                        buildEvidencePersistence(
                            contextItems = contextItems,
                            contextRefs = contextRefs,
                            toolCalls = requestToolCalls,
                            createdAt = contextRefCreatedAt,
                        )
                    }
                } else {
                    null
                }
            repository.replaceContextRefsAndEvidenceForAssistant(
                assistantMessageId = assistant.id,
                contextRefs = contextRefs,
                evidenceBlocks = evidenceState?.evidenceBlocks.orEmpty(),
            )
            // R07 Evidence Citation uses request-local [[E#]] transport; ContextRef keeps frozen source
            // snapshots, while the retired legacy [R#] protocol remains disabled.
            val citationReady =
                evidenceState?.let { state ->
                    prepareCitationProtocol(
                        composed = plan.context,
                        candidates = state.citationCandidates,
                        includedHistoryContextIds =
                            requestToolCalls.mapNotNull { call ->
                                if (
                                    call.status == LlmToolCallStatus.COMPLETE &&
                                        !call.resultContent.isNullOrBlank()
                                ) {
                                    "tool-result:${call.id}"
                                } else {
                                    null
                                }
                            },
                    )
                }
            requestCitationEntries = citationReady?.protocolEntries.orEmpty()
            val citationAccumulator =
                if (LLM_EVIDENCE_CITATION_ENABLED) {
                    CitationTransportAccumulator(
                        requestCitationEntries.mapTo(linkedSetOf()) { it.protocolId }
                    )
                } else {
                    null
                }
            val requestPlan =
                if (citationReady == null) {
                    plan
                } else {
                    plan.copy(
                        context = plan.context.copy(text = citationReady.text),
                        citationProtocolInstruction = citationReady.instruction.takeIf(String::isNotBlank),
                        citations = emptyList(),
                    )
                }
            val requestHistory =
                if (citationReady == null) {
                    history
                } else {
                    applyToolResultCitationProtocol(
                        history = history,
                        toolCalls = requestToolCalls,
                        protocolEntries = citationReady.protocolEntries,
                    )
                }

            fallbackPromptTokens = transport.estimateRequestTokens(requestPlan, requestHistory)
            LlmChatPerfTracker.mark(
                assistant.id,
                "request_ready_for_transport",
                "estimatedPromptTokens" to fallbackPromptTokens,
                "contextRefCount" to contextRefs.size,
            )
            requestStartedAtNanos = System.nanoTime()

            transport.stream(requestPlan, requestHistory, perfTrace = perfTrace).collect { delta ->
                content += delta.content
                citationAccumulator?.append(delta.content)
                reasoning += delta.reasoning
                mergeToolCallDeltas(toolCallParts, delta.toolCalls)
                LlmChatPerfTracker.recordTransportDelta(
                    assistantMessageId = assistant.id,
                    contentChars = delta.content.length,
                    reasoningChars = delta.reasoning.length,
                    toolCallCount = delta.toolCalls.size,
                )
                delta.promptTokens?.let { providerPromptTokens = it }
                delta.completionTokens?.let { providerCompletionTokens = it }
                delta.finishReason?.let { finishReason = it }
                val now = System.currentTimeMillis()
                val shouldPublishUi = now - lastUiPublishAt >= STREAM_UI_UPDATE_INTERVAL_MS
                val shouldPersist = now - lastPersistAt >= STREAM_PERSIST_INTERVAL_MS
                val streamingCanonicalContent =
                    if (shouldPublishUi || shouldPersist) {
                        citationAccumulator?.snapshot()?.canonicalText ?: content
                    } else {
                        ""
                    }
                val hasVisibleText =
                    if (shouldPublishUi || shouldPersist) {
                        streamingCanonicalContent.isNotEmpty() || reasoning.isNotEmpty()
                    } else {
                        content.isNotEmpty() || reasoning.isNotEmpty()
                    }
                if (hasVisibleText && shouldPublishUi) {
                    publishTransientStreamingMessage(
                        assistant.copy(
                            // raw provider transport 只存在内存瞬态层；UI projection 会经唯一 parser
                            // 转成 provisional [n]，Room 仍只写 streamingCanonicalContent。
                            content = if (LLM_EVIDENCE_CITATION_ENABLED) content else streamingCanonicalContent,
                            reasoning = reasoning.ifBlank { null },
                            status = LlmMessageStatus.STREAMING,
                            errorMessage = null,
                            updatedAt = now,
                        )
                    )
                    LlmChatPerfTracker.recordStreamingUiPublish(
                        assistantMessageId = assistant.id,
                        contentChars = content.length,
                        reasoningChars = reasoning.length,
                    )
                    lastUiPublishAt = now
                }
                if (hasVisibleText && shouldPersist) {
                    assistant =
                        repository.updateMessage(
                            message = assistant,
                            content = streamingCanonicalContent,
                            reasoning = reasoning.ifBlank { null },
                            status = LlmMessageStatus.STREAMING,
                            errorMessage = null,
                            // 流式正文自身已经会触发 messages Flow；不要每 90ms 再更新一次会话元数据，
                            // 否则同一次 token 刷新会额外触发 conversations Flow 与一次无意义的 Room 写入。
                            // 最终 COMPLETE / STOPPED / ERROR 仍使用默认 true，保证最近活动时间最终正确落盘。
                            touchConversation = false,
                        )
                    LlmChatPerfTracker.recordRoomPersist(
                        assistantMessageId = assistant.id,
                        contentChars = content.length,
                        reasoningChars = reasoning.length,
                    )
                    lastPersistAt = now
                }
            }

            val terminalDecision =
                resolveLlmGenerationTerminalDecision(
                    hasContent = content.isNotBlank(),
                    hasReasoning = reasoning.isNotBlank(),
                    hasToolCalls = toolCallParts.isNotEmpty(),
                    finishReason = finishReason,
                )
            when (terminalDecision) {
                LlmGenerationTerminalDecision.Complete,
                LlmGenerationTerminalDecision.ContinueWithTools -> Unit
                is LlmGenerationTerminalDecision.Error -> error(terminalDecision.userMessage)
            }
            // 把不足一个 UI interval 的尾部增量先补到内存层，再执行最终 Room 落盘。
            if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                publishTransientStreamingMessage(
                    assistant.copy(
                        content = content,
                        reasoning = reasoning.ifBlank { null },
                        status = LlmMessageStatus.STREAMING,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                LlmChatPerfTracker.recordStreamingUiPublish(
                    assistantMessageId = assistant.id,
                    contentChars = content.length,
                    reasoningChars = reasoning.length,
                )
            }
            val completedPresentation = finalizeCitationMessage(
                assistant.copy(
                content = content,
                reasoning = reasoning.ifBlank { null },
                status = LlmMessageStatus.COMPLETE,
                errorMessage = null,
                promptTokens = promptTokens(),
                completionTokens = completionTokens(),
                durationMs = durationMs(),
                tokenUsageEstimated = tokenUsageEstimated(),
            ), content)
            assistant = completedPresentation.message
            publishTransientPresentation(completedPresentation)
            LlmChatPerfTracker.finish(
                assistantMessageId = assistant.id,
                status = LlmMessageStatus.COMPLETE.name,
                contentChars = content.length,
                reasoningChars = reasoning.length,
                finishReason = finishReason,
            )

            if (terminalDecision == LlmGenerationTerminalDecision.ContinueWithTools) {
                if (toolRound >= MAX_AUTOMATIC_TOOL_ROUNDS) {
                    error("Tool Calling 已达到安全轮次上限")
                }
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
                            toolName = descriptor?.name ?: apiName,
                            toolSourceId = descriptor?.sourceId,
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

                // 只有本地策略明确免确认的 Tool 才自动执行；远端 MCP 默认都保持 Pending。
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
                val stoppedPresentation = finalizeCitationMessage(
                    assistant.copy(
                        content = content,
                        reasoning = reasoning.ifBlank { null },
                        status = LlmMessageStatus.STOPPED,
                        errorMessage = null,
                        webSearchStatus =
                            webSearchStatusAfterGenerationStopped(assistant.webSearchStatus),
                        promptTokens = promptTokens(),
                        completionTokens = completionTokens(),
                        durationMs = durationMs(),
                        tokenUsageEstimated = tokenUsageEstimated(),
                    ), content)
                assistant = stoppedPresentation.message
                publishTransientPresentation(stoppedPresentation)
                LlmChatPerfTracker.finish(
                    assistantMessageId = assistant.id,
                    status = LlmMessageStatus.STOPPED.name,
                    contentChars = content.length,
                    reasoningChars = reasoning.length,
                    finishReason = finishReason ?: LlmFinishReason.Cancelled,
                )
            }
            throw error
        } catch (error: Throwable) {
            // 错误信息既持久化到消息，又作为一次性 Snackbar 暴露，便于历史恢复后仍能看见失败原因。
            val message = error.message?.takeIf(String::isNotBlank) ?: "AI 请求失败"
            val errorPresentation = finalizeCitationMessage(
                assistant.copy(
                    content = content,
                    reasoning = reasoning.ifBlank { null },
                    status = LlmMessageStatus.ERROR,
                    errorMessage = message,
                    promptTokens = promptTokens(),
                    completionTokens = completionTokens(),
                    durationMs = durationMs(),
                    tokenUsageEstimated = tokenUsageEstimated(),
                ), content)
            assistant = errorPresentation.message
            publishTransientPresentation(errorPresentation)
            LlmChatPerfTracker.finish(
                assistantMessageId = assistant.id,
                status = LlmMessageStatus.ERROR.name,
                contentChars = content.length,
                reasoningChars = reasoning.length,
                finishReason = finishReason ?: LlmFinishReason.Error,
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

    /** 更新当前流式消息的内存覆盖；历史真值仍由 Room 终态负责。 */
    private fun publishTransientStreamingMessage(message: LlmMessageEntity) {
        publishTransientPresentation(TransientChatPresentationOverride(message = message))
    }

    private fun publishTransientPresentation(presentation: TransientChatPresentationOverride) {
        transientMessageOverrides.update { current ->
            current + (presentation.message.id to presentation)
        }
    }

    /** 切文章、切会话或重新生成时移除旧 UI 覆盖，禁止已删除消息继续残留在列表。 */
    private fun clearTransientStreamingMessages(conversationId: String? = null) {
        transientMessageOverrides.update { current ->
            if (conversationId == null) {
                emptyMap()
            } else {
                current.filterValues { it.message.conversationId != conversationId }
            }
        }
    }
}

/**
 * 用内存流式快照覆盖 Room 中较旧的同 id 消息；首个 delta 早于 Room placeholder emission 时也允许补入列表。
 */
internal fun mergeChatMessagesWithTransientOverrides(
    persistedMessages: List<LlmMessageEntity>,
    transientOverrides: Map<String, LlmMessageEntity>,
    conversationId: String?,
): List<LlmMessageEntity> {
    if (conversationId == null || transientOverrides.isEmpty()) return persistedMessages
    val scopedOverrides =
        transientOverrides.values
            .filter { it.conversationId == conversationId }
            .associateBy(LlmMessageEntity::id)
    if (scopedOverrides.isEmpty()) return persistedMessages

    val persistedIds = persistedMessages.mapTo(mutableSetOf(), LlmMessageEntity::id)
    return buildList {
            persistedMessages.forEach { message -> add(scopedOverrides[message.id] ?: message) }
            scopedOverrides.values
                .filterNot { it.id in persistedIds }
                .forEach(::add)
        }
        .sortedWith(compareBy<LlmMessageEntity> { it.createdAt }.thenBy { it.id })
}

internal fun visibleChatPresentationMessages(
    messages: List<LlmMessageEntity>,
): List<LlmMessageEntity> = messages.filter(LlmMessageEntity::historyActive)

/** 只有 Room 已经回读到完全相同的终态实体时，内存覆盖才算被持久化层确认。 */
internal fun acknowledgedChatTransientMessageIds(
    persistedMessages: List<LlmMessageEntity>,
    transientOverrides: Map<String, LlmMessageEntity>,
    conversationId: String?,
): Set<String> {
    if (conversationId == null || transientOverrides.isEmpty()) return emptySet()
    val persistedById = persistedMessages.associateBy(LlmMessageEntity::id)
    return transientOverrides.values
        .asSequence()
        .filter { it.conversationId == conversationId }
        .filter { it.status != LlmMessageStatus.STREAMING }
        .filter { persistedById[it.id] == it }
        .map(LlmMessageEntity::id)
        .toSet()
}

/**
 * Terminal transient presentation 只有在 Room 回读到 message 和 Citation graph 全部一致后才能释放。
 * 这样旧的 streaming Room emission 永远不能和新的 terminal message 拼成半状态。
 */
internal fun acknowledgedChatTransientPresentationIds(
    persistedPresentation: List<LlmMessageCitationPresentation>,
    transientOverrides: Map<String, TransientChatPresentationOverride>,
    conversationId: String?,
): Set<String> {
    if (conversationId == null || transientOverrides.isEmpty()) return emptySet()
    val persistedById = persistedPresentation.associateBy { it.message.id }
    return transientOverrides.values
        .asSequence()
        .filter { it.message.conversationId == conversationId }
        .filter { it.message.status != LlmMessageStatus.STREAMING }
        .filter { transient ->
            val persisted = persistedById[transient.message.id] ?: return@filter false
            if (persisted.message != transient.message) return@filter false
            val expectedRefs = transient.citationRefs ?: return@filter true
            val expectedAnnotations = transient.citationAnnotations.orEmpty()
            persisted.citationRefs.sortedBy(LlmCitationRefEntity::id) ==
                expectedRefs.sortedBy(LlmCitationRefEntity::id) &&
                persisted.citationAnnotations.citationAnnotationAckKeys() ==
                expectedAnnotations.citationAnnotationAckKeys()
        }
        .map { it.message.id }
        .toSet()
}

private data class CitationAnnotationAckKey(
    val annotation: me.ash.reader.llm.chat.data.LlmCitationAnnotationEntity,
    val citationRefIds: List<String>,
)

private fun List<LlmCitationAnnotationWithRefs>.citationAnnotationAckKeys(): List<CitationAnnotationAckKey> =
    map { occurrence ->
        CitationAnnotationAckKey(
            annotation = occurrence.annotation,
            citationRefIds = occurrence.refs.map(LlmCitationRefEntity::id),
        )
    }.sortedWith(compareBy({ it.annotation.occurrenceOrdinal }, { it.annotation.id }))

/** 保持原有约 90ms 的屏幕流式刷新节奏；首个可见 delta 仍会立即发布。 */
private const val STREAM_UI_UPDATE_INTERVAL_MS = 90L

/** Room 与 UI 解耦后降低流式持久化频率；STOPPED / ERROR / COMPLETE 仍立即最终落盘。 */
private const val STREAM_PERSIST_INTERVAL_MS = 300L

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
 * Chat 的事实上下文只来自当前文章原文与原文选区。
 *
 * AI 摘要和翻译是独立阅读产物，绝不能作为新的事实来源回灌 Conversation；这样后续追问、
 * Citation 和分析始终能够回到原始正文，而不是在模型生成内容之上继续二次推理。
 */
internal fun buildArticleContextItems(context: ArticleAssistantContext): List<LlmContextItem> =
    buildList {
        val originalContent = context.originalContent.trim().takeIf(String::isNotBlank)
        val articleEvidenceBlocks =
            if (LLM_EVIDENCE_CITATION_ENABLED && originalContent != null) {
                buildArticleEvidenceBlocks(
                    html = originalContent,
                    source =
                        LlmArticleEvidenceSource(
                            articleId = context.articleId,
                            sourceUrl = context.link,
                        ),
                )
            } else {
                emptyList()
            }
        context.selectedText?.trim()?.takeIf(String::isNotBlank)?.let { selection ->
            add(
                LlmContextItem(
                    id = "article:${context.articleId}:selection",
                    type = LlmContextType.SELECTED_TEXT,
                    title = context.title,
                    sourceId = context.link,
                    internalArticleId = context.articleId,
                    content = selection,
                    // 用户刚刚显式选中的正文与当前问题相关度最高，必须优先于摘要/译文/整篇正文进入预算。
                    priority = 160,
                ).let { item ->
                    if (
                        LLM_EVIDENCE_CITATION_ENABLED &&
                            !context.selectedTextFromTranslation
                    ) {
                        item.withSelectionEvidenceBlock(articleEvidenceBlocks = articleEvidenceBlocks)
                    } else {
                        item
                    }
                }
            )
        }
        originalContent?.let { original ->
            add(
                LlmContextItem(
                    id = "article:${context.articleId}:original",
                    type = LlmContextType.ARTICLE,
                    title = context.title,
                    sourceId = context.link,
                    internalArticleId = context.articleId,
                    content = original,
                    reserveEvidenceBudget = true,
                    priority = 100,
                ).let { item ->
                    if (LLM_EVIDENCE_CITATION_ENABLED) {
                        item.withBuiltEvidenceBlocks(articleEvidenceBlocks)
                    } else {
                        item
                    }
                }
            )
        }
    }
