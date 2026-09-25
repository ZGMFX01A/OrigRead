package me.ash.reader.llm.chat.data

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.core.SyncBlobAvailabilityState
import me.ash.reader.infrastructure.sync.core.SyncLocalBlobStore
import me.ash.reader.infrastructure.sync.core.SyncMutationType
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.llm.runtime.LlmExecutionTask
import me.ash.reader.llm.search.WebSearchRequestStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

data class LlmSyncAttachmentKey(
    val entityType: String,
    val localId: String,
    val referenceKind: String,
)

data class LlmSyncAttachmentState(
    val availability: SyncBlobAvailabilityState,
    val failureReason: String? = null,
)

@Singleton
/** LLM Chat 数据仓储，统一封装会话/消息 Room 写入与活动时间维护。 */
class LlmChatRepository @Inject constructor(
    private val dao: LlmChatDao,
    private val syncMutations: LlmSyncMutationCapture,
    private val syncBlobStore: SyncLocalBlobStore,
    private val accountService: AccountService,
    private val readerDatabase: AndroidDatabase,
    private val chatDatabase: LlmChatDatabase,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSyncAttachmentAvailability(): Flow<Map<LlmSyncAttachmentKey, LlmSyncAttachmentState>> =
        accountService.currentAccountIdFlow
            .filterNotNull()
            .flatMapLatest { accountId ->
                readerDatabase.syncRuntimeDao().observeBinding(accountId)
            }
            .flatMapLatest { binding ->
                if (binding == null) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        readerDatabase.syncBlobDao().observeOwnerAvailability(
                            syncSpaceId = binding.syncSpaceId,
                            lane = "AI_HISTORY",
                        ),
                        chatDatabase.syncIdentityMappingDao().observeByTypes(
                            syncSpaceId = binding.syncSpaceId,
                            entityTypes =
                                listOf(
                                    SyncEntityType.CONTEXT_REF.wireName,
                                    SyncEntityType.EVIDENCE_BLOCK.wireName,
                                    SyncEntityType.CITATION_REF.wireName,
                                ),
                        ),
                    ) { ownerStates, mappings ->
                        val localIds =
                            mappings.associateBy(
                                keySelector = {
                                    Triple(it.entityType, it.syncId, it.generation)
                                },
                                valueTransform = { it.localId },
                            )
                        buildMap {
                            ownerStates.forEach { owner ->
                                val localId =
                                    localIds[
                                        Triple(
                                            owner.ownerEntityType,
                                            owner.ownerEntitySyncId,
                                            owner.ownerEntityGeneration,
                                        )
                                    ] ?: return@forEach
                                val availability =
                                    runCatching {
                                        SyncBlobAvailabilityState.valueOf(owner.availabilityState)
                                    }.getOrDefault(SyncBlobAvailabilityState.METADATA_READY)
                                put(
                                    LlmSyncAttachmentKey(
                                        entityType = owner.ownerEntityType,
                                        localId = localId,
                                        referenceKind = owner.referenceKind,
                                    ),
                                    LlmSyncAttachmentState(
                                        availability = availability,
                                        failureReason = owner.failureReason,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

    /** 观察当前文章最近活动的会话列表。 */
    fun observeConversations(articleId: String): Flow<List<LlmConversationEntity>> =
        dao.observeConversations(articleId)

    /** 观察指定会话消息。 */
    fun observeMessages(conversationId: String): Flow<List<LlmMessageEntity>> =
        dao.observeMessages(conversationId)

    /** UI 原子观察 message + CitationRef + Annotation，避免多个 Flow 拼接出半状态。 */
    fun observeMessageCitationPresentation(
        conversationId: String,
    ): Flow<List<LlmMessageCitationPresentation>> = dao.observeMessageCitationPresentation(conversationId)

    /** 观察指定会话 Tool Call 状态。 */
    fun observeToolCalls(conversationId: String): Flow<List<LlmToolCallEntity>> =
        dao.observeToolCalls(conversationId)

    /** 观察指定会话所有请求级 ContextRef，供 P6 来源/Context 管理 UI 恢复历史依据。 */
    fun observeContextRefs(conversationId: String): Flow<List<LlmContextRefEntity>> =
        dao.observeContextRefs(conversationId)

    /** 观察指定会话全部 CitationRef；显示编号仍严格属于各自 Assistant Message。 */
    fun observeCitationRefs(conversationId: String): Flow<List<LlmCitationRefEntity>> =
        dao.observeCitationRefs(conversationId)

    fun observeCitationAnnotations(
        conversationId: String,
    ): Flow<List<LlmCitationAnnotationWithRefs>> = dao.observeCitationAnnotations(conversationId)

    fun observeLatestRestorableCitationAssistant(articleId: String): Flow<LlmMessageEntity?> =
        dao.observeLatestRestorableCitationAssistant(articleId)

    /** 查询指定会话。 */
    suspend fun getConversation(conversationId: String): LlmConversationEntity? =
        dao.getConversation(conversationId)

    /** 一次性读取指定会话历史，用于构造下一轮模型请求。 */
    suspend fun getMessages(conversationId: String): List<LlmMessageEntity> =
        dao.getMessages(conversationId)

    suspend fun getToolCalls(conversationId: String): List<LlmToolCallEntity> =
        dao.getToolCalls(conversationId)

    suspend fun getContextRefsForAssistant(assistantMessageId: String): List<LlmContextRefEntity> =
        dao.getContextRefsForAssistant(assistantMessageId)

    suspend fun getEvidenceBlocksForContextRef(contextRefId: String): List<LlmEvidenceBlockEntity> =
        dao.getEvidenceBlocksForContextRef(contextRefId)

    suspend fun getCitationRefsForAssistant(assistantMessageId: String): List<LlmCitationRefEntity> =
        dao.getCitationRefsForAssistant(assistantMessageId)

    /** 恢复会话级活动文章附件；历史请求自己的 ContextRef 仍由消息级快照独立恢复。 */
    suspend fun getConversationArticles(conversationId: String): List<LlmConversationArticleEntity> =
        dao.getConversationArticles(conversationId)

    /** 原子保存会话当前的活动文章附件集合。 */
    suspend fun replaceConversationArticles(
        conversationId: String,
        articles: List<LlmConversationArticleEntity>,
    ) {
        val existing = dao.getConversationArticles(conversationId)
        val incomingIds =
            articles.mapTo(mutableSetOf()) {
                SyncCanonicalIdentity.relationLocalId(
                    SyncEntityType.CONVERSATION_ARTICLE,
                    it.conversationId,
                    it.articleId,
                )
            }
        val drafts =
            buildList {
                existing.forEach { row ->
                    val localId =
                        SyncCanonicalIdentity.relationLocalId(
                            SyncEntityType.CONVERSATION_ARTICLE,
                            row.conversationId,
                            row.articleId,
                        )
                    if (localId !in incomingIds) {
                        add(
                            LlmSyncMutationDraft(
                                entityType = SyncEntityType.CONVERSATION_ARTICLE,
                                localId = localId,
                                mutationType = SyncMutationType.GLOBAL_DELETE,
                                payloadJson = syncDeletePayloadJson(localId),
                            )
                        )
                    }
                }
                articles.forEach { row ->
                    add(
                        LlmSyncMutationDraft(
                            entityType = SyncEntityType.CONVERSATION_ARTICLE,
                            localId =
                                SyncCanonicalIdentity.relationLocalId(
                                    SyncEntityType.CONVERSATION_ARTICLE,
                                    row.conversationId,
                                    row.articleId,
                                ),
                            mutationType = SyncMutationType.RELATION_SET,
                            payloadJson = row.toSyncPayloadJson(syncBlobStore),
                        )
                    )
                }
            }
        syncMutations.capture(drafts) {
            dao.replaceConversationArticles(conversationId, articles)
        }
    }

    /** 新建会话，并以首条用户文本生成本地标题。 */
    suspend fun createConversation(
        providerId: String?,
        model: String?,
        skillId: String?,
        articleId: String,
        articleTitle: String,
        articleLink: String?,
        titleSeed: String? = null,
    ): LlmConversationEntity {
        val now = System.currentTimeMillis()
        val conversation =
            LlmConversationEntity(
                id = UUID.randomUUID().toString(),
                title = deriveConversationTitle(titleSeed.orEmpty()),
                providerId = providerId,
                model = model,
                skillId = skillId,
                articleId = articleId,
                articleTitle = articleTitle.trim().takeIf(String::isNotBlank),
                articleLink = articleLink?.trim()?.takeIf(String::isNotBlank),
                createdAt = now,
                updatedAt = now,
            )
        syncMutations.capture(
            listOf(
                LlmSyncMutationDraft(
                    entityType = SyncEntityType.CONVERSATION,
                    localId = conversation.id,
                    mutationType = SyncMutationType.UPSERT,
                    payloadJson = conversation.toSyncPayloadJson(),
                )
            )
        ) { dao.insertConversation(conversation) }
        return conversation
    }

    /** 重命名会话；空标题不会覆盖原值。 */
    suspend fun renameConversation(conversationId: String, title: String) {
        val current = dao.getConversation(conversationId) ?: return
        val normalized = title.trim().take(MAX_CONVERSATION_TITLE_LENGTH)
        if (normalized.isBlank()) return
        val updated = current.copy(title = normalized, updatedAt = System.currentTimeMillis())
        syncMutations.capture(
            listOf(
                LlmSyncMutationDraft(
                    entityType = SyncEntityType.CONVERSATION,
                    localId = updated.id,
                    mutationType = SyncMutationType.FIELD_SET,
                    payloadJson = syncFieldPayloadJson("title", normalized),
                )
            )
        ) { dao.updateConversation(updated) }
    }

    /** 保存会话绑定的 Provider/Model/Skill。 */
    suspend fun updateConversationRuntime(
        conversationId: String,
        providerId: String?,
        model: String?,
        skillId: String?,
    ) {
        val current = dao.getConversation(conversationId) ?: return
        val updated =
            current.copy(
                providerId = providerId,
                model = model,
                skillId = skillId,
                updatedAt = System.currentTimeMillis(),
            )
        syncMutations.capture(
            listOf(
                LlmSyncMutationDraft(
                    entityType = SyncEntityType.CONVERSATION,
                    localId = updated.id,
                    mutationType = SyncMutationType.FIELD_SET,
                    payloadJson = syncFieldPayloadJson("providerId", providerId),
                ),
                LlmSyncMutationDraft(
                    entityType = SyncEntityType.CONVERSATION,
                    localId = updated.id,
                    mutationType = SyncMutationType.FIELD_SET,
                    payloadJson = syncFieldPayloadJson("model", model),
                ),
                LlmSyncMutationDraft(
                    entityType = SyncEntityType.CONVERSATION,
                    localId = updated.id,
                    mutationType = SyncMutationType.FIELD_SET,
                    payloadJson = syncFieldPayloadJson("skillId", skillId),
                ),
            )
        ) { dao.updateConversation(updated) }
    }

    /** 更新会话最近活动时间。 */
    suspend fun touchConversation(conversationId: String) {
        val current = dao.getConversation(conversationId) ?: return
        dao.updateConversation(current.copy(updatedAt = System.currentTimeMillis()))
    }

    /** 删除会话，关联消息由 Room 外键级联删除。 */
    suspend fun deleteConversation(conversationId: String) {
        dao.getConversation(conversationId)?.let { conversation ->
            syncMutations.capture(
                listOf(
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.CONVERSATION,
                        localId = conversation.id,
                        mutationType = SyncMutationType.GLOBAL_DELETE,
                        payloadJson = syncDeletePayloadJson(conversation.id),
                    )
                )
            ) { dao.deleteConversation(conversation) }
        }
    }

    /** 追加消息并更新会话活动时间。 */
    suspend fun appendMessage(
        conversationId: String,
        role: LlmChatRole,
        content: String,
        requestTask: LlmExecutionTask? = null,
        status: LlmMessageStatus = LlmMessageStatus.COMPLETE,
    ): LlmMessageEntity {
        val now = System.currentTimeMillis()
        val message =
            LlmMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = role,
                content = content,
                requestTask = requestTask,
                status = status,
                createdAt = now,
                updatedAt = now,
            )
        if (message.status == LlmMessageStatus.STREAMING) {
            dao.insertMessage(message)
        } else {
            syncMutations.capture(
                listOf(
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.MESSAGE,
                        localId = message.id,
                        mutationType = SyncMutationType.UPSERT,
                        payloadJson = message.toSyncPayloadJson(),
                    )
                )
            ) { dao.insertMessage(message) }
        }
        touchConversation(conversationId)
        return message
    }

    /** 持久化流式内容、reasoning、最终状态或错误信息。 */
    suspend fun updateMessage(
        message: LlmMessageEntity,
        content: String = message.content,
        reasoning: String? = message.reasoning,
        status: LlmMessageStatus = message.status,
        errorMessage: String? = message.errorMessage,
        webSearchStatus: WebSearchRequestStatus? = message.webSearchStatus,
        webSearchQuery: String? = message.webSearchQuery,
        webSearchProviderName: String? = message.webSearchProviderName,
        webSearchErrorMessage: String? = message.webSearchErrorMessage,
        promptTokens: Int? = message.promptTokens,
        completionTokens: Int? = message.completionTokens,
        durationMs: Long? = message.durationMs,
        tokenUsageEstimated: Boolean = message.tokenUsageEstimated,
        touchConversation: Boolean = true,
    ): LlmMessageEntity {
        val updated =
            message.copy(
                content = content,
                reasoning = reasoning,
                status = status,
                errorMessage = errorMessage,
                webSearchStatus = webSearchStatus,
                webSearchQuery = webSearchQuery,
                webSearchProviderName = webSearchProviderName,
                webSearchErrorMessage = webSearchErrorMessage,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                durationMs = durationMs,
                tokenUsageEstimated = tokenUsageEstimated,
                updatedAt = System.currentTimeMillis(),
            )
        if (updated.status == LlmMessageStatus.STREAMING) {
            dao.updateMessage(updated)
        } else {
            syncMutations.capture(
                listOf(
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.MESSAGE,
                        localId = updated.id,
                        mutationType = SyncMutationType.UPSERT,
                        payloadJson = updated.toSyncPayloadJson(),
                    )
                )
            ) { dao.updateMessage(updated) }
        }
        if (touchConversation) {
            touchConversation(message.conversationId)
        }
        return updated
    }

    /**
     * 原子冻结 Dedicated Search 的终态与已取得的结果证据。
     *
     * SUCCESS 会先保存一份“尚未进入模型 Prompt”的 Search ContextRef；Runtime prepare 完成后再由
     * replaceContextRefsForAssistant 原子替换成最终 usage 状态。这样 Stop/进程退出落在两阶段之间时，
     * 仍能恢复真实 Search 结果，而不会制造 SUCCESS + 0 evidence 的竞态。
     */
    suspend fun finalizeWebSearch(
        message: LlmMessageEntity,
        status: WebSearchRequestStatus,
        providerName: String?,
        errorMessage: String?,
        contextRefs: List<LlmContextRefEntity>,
    ): LlmMessageEntity {
        val updated =
            message.copy(
                webSearchStatus = status,
                webSearchProviderName = providerName,
                webSearchErrorMessage = errorMessage,
                updatedAt = System.currentTimeMillis(),
            )
        if (updated.status == LlmMessageStatus.STREAMING) {
            // Search evidence belongs to the still-running request. Do not emit a half-built
            // AI_HISTORY graph; terminal finalize will freeze message + Context/Evidence/Citation.
            dao.updateMessageAndReplaceContextRefs(updated, contextRefs)
        } else {
            syncMutations.capture(
                listOf(
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.MESSAGE,
                        localId = updated.id,
                        mutationType = SyncMutationType.UPSERT,
                        payloadJson = updated.toSyncPayloadJson(),
                    )
                )
            ) { dao.updateMessageAndReplaceContextRefs(updated, contextRefs) }
        }
        touchConversation(message.conversationId)
        return updated
    }

    /**
     * 切换消息是否进入后续 Provider 历史；只改分支选择，不删除消息及其请求级证据。
     */
    suspend fun setMessagesHistoryActive(
        messageIds: Collection<String>,
        active: Boolean,
    ): Int {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) return 0
        return syncMutations.capture(
            drafts =
                ids.map { id ->
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.MESSAGE,
                        localId = id,
                        mutationType = SyncMutationType.FIELD_SET,
                        payloadJson = syncFieldPayloadJson("historyActive", active),
                    )
                },
            mutate = {
                dao.setMessagesHistoryActive(
                    messageIds = ids,
                    active = active,
                    updatedAt = System.currentTimeMillis(),
                )
            },
        )
    }

    /** 将同一 assistant response 中的 Tool Calls 一次落库。 */
    suspend fun appendToolCalls(toolCalls: List<LlmToolCallEntity>) {
        if (toolCalls.isEmpty()) return
        val terminal =
            toolCalls.filter {
                it.status in
                    setOf(
                        LlmToolCallStatus.COMPLETE,
                        LlmToolCallStatus.DENIED,
                        LlmToolCallStatus.ERROR,
                    )
            }
        if (terminal.isEmpty()) {
            dao.insertToolCalls(toolCalls)
        } else {
            syncMutations.capture(
                drafts =
                    terminal.map { toolCall ->
                        LlmSyncMutationDraft(
                            entityType = SyncEntityType.TOOL_CALL,
                            localId = toolCall.id,
                            mutationType = SyncMutationType.UPSERT,
                            payloadJson = toolCall.toSyncPayloadJson(syncBlobStore),
                        )
                    },
                mutate = { dao.insertToolCalls(toolCalls) },
            )
        }
        touchConversation(toolCalls.first().conversationId)
    }

    /** 保存某一次模型请求的来源快照；不会修改会话活动时间，避免仅重建来源 UI 扰动历史排序。 */
    suspend fun replaceContextRefsForAssistant(
        assistantMessageId: String,
        contextRefs: List<LlmContextRefEntity>,
    ) {
        dao.replaceContextRefsForAssistant(assistantMessageId, contextRefs)
    }

    suspend fun replaceContextRefsAndEvidenceForAssistant(
        assistantMessageId: String,
        contextRefs: List<LlmContextRefEntity>,
        evidenceBlocks: List<LlmEvidenceBlockEntity>,
    ) {
        dao.replaceContextRefsAndEvidenceForAssistant(
            assistantMessageId = assistantMessageId,
            contextRefs = contextRefs,
            evidenceBlocks = evidenceBlocks,
        )
    }

    suspend fun replaceCitationRefsForAssistant(
        assistantMessageId: String,
        citationRefs: List<LlmCitationRefEntity>,
    ) {
        dao.replaceCitationRefsForAssistant(assistantMessageId, citationRefs)
    }

    suspend fun getCitationAnnotationsForAssistant(
        assistantMessageId: String,
    ): List<LlmCitationAnnotationWithRefs> = dao.getCitationAnnotationsForAssistant(assistantMessageId)

    /** 原子持久化 canonical Assistant 正文与完整 Citation occurrence 图。 */
    suspend fun finalizeAssistantCitationState(
        message: LlmMessageEntity,
        citationRefs: List<LlmCitationRefEntity>,
        annotations: List<LlmCitationAnnotationEntity>,
        annotationRefs: List<LlmCitationAnnotationRefEntity>,
    ): LlmMessageEntity {
        val contextRefs = dao.getContextRefsForAssistant(message.id)
        val evidenceBlocks =
            contextRefs.flatMap { contextRef ->
                dao.getEvidenceBlocksForContextRef(contextRef.id)
            }
        val previousRefs = dao.getCitationRefsForAssistant(message.id)
        val previousAnnotations = dao.getCitationAnnotationsForAssistant(message.id)
        val incomingCitationIds = citationRefs.mapTo(mutableSetOf()) { it.id }
        val incomingAnnotationIds = annotations.mapTo(mutableSetOf()) { it.id }
        val incomingRelationIds =
            annotationRefs.mapTo(mutableSetOf()) { ref ->
                SyncCanonicalIdentity.relationLocalId(
                    SyncEntityType.CITATION_ANNOTATION_REF,
                    ref.annotationId,
                    ref.citationRefId,
                )
            }
        val drafts =
            buildList {
                previousAnnotations.forEach { occurrence ->
                    occurrence.refs.forEach { ref ->
                        val localId =
                            SyncCanonicalIdentity.relationLocalId(
                                SyncEntityType.CITATION_ANNOTATION_REF,
                                occurrence.annotation.id,
                                ref.id,
                            )
                        if (localId !in incomingRelationIds) {
                            add(
                                LlmSyncMutationDraft(
                                    SyncEntityType.CITATION_ANNOTATION_REF,
                                    localId,
                                    SyncMutationType.GLOBAL_DELETE,
                                    syncDeletePayloadJson(localId),
                                )
                            )
                        }
                    }
                }
                previousAnnotations.map { it.annotation }.forEach { old ->
                    if (old.id !in incomingAnnotationIds) {
                        add(
                            LlmSyncMutationDraft(
                                SyncEntityType.CITATION_ANNOTATION,
                                old.id,
                                SyncMutationType.GLOBAL_DELETE,
                                syncDeletePayloadJson(old.id),
                            )
                        )
                    }
                }
                previousRefs.forEach { old ->
                    if (old.id !in incomingCitationIds) {
                        add(
                            LlmSyncMutationDraft(
                                SyncEntityType.CITATION_REF,
                                old.id,
                                SyncMutationType.GLOBAL_DELETE,
                                syncDeletePayloadJson(old.id),
                            )
                        )
                    }
                }
                add(
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.MESSAGE,
                        localId = message.id,
                        mutationType = SyncMutationType.UPSERT,
                        payloadJson = message.toSyncPayloadJson(),
                    )
                )
                contextRefs.forEach { contextRef ->
                    add(
                        LlmSyncMutationDraft(
                            SyncEntityType.CONTEXT_REF,
                            contextRef.id,
                            SyncMutationType.UPSERT,
                            contextRef.toSyncPayloadJson(syncBlobStore),
                        )
                    )
                }
                evidenceBlocks.forEach { evidence ->
                    add(
                        LlmSyncMutationDraft(
                            SyncEntityType.EVIDENCE_BLOCK,
                            evidence.id,
                            SyncMutationType.UPSERT,
                            evidence.toSyncPayloadJson(syncBlobStore),
                        )
                    )
                }
                citationRefs.forEach { citation ->
                    add(
                        LlmSyncMutationDraft(
                            SyncEntityType.CITATION_REF,
                            citation.id,
                            SyncMutationType.UPSERT,
                            citation.toSyncPayloadJson(syncBlobStore),
                        )
                    )
                }
                annotations.forEach { annotation ->
                    add(
                        LlmSyncMutationDraft(
                            SyncEntityType.CITATION_ANNOTATION,
                            annotation.id,
                            SyncMutationType.UPSERT,
                            annotation.toSyncPayloadJson(),
                        )
                    )
                }
                annotationRefs.forEach { ref ->
                    add(
                        LlmSyncMutationDraft(
                            SyncEntityType.CITATION_ANNOTATION_REF,
                            SyncCanonicalIdentity.relationLocalId(
                                SyncEntityType.CITATION_ANNOTATION_REF,
                                ref.annotationId,
                                ref.citationRefId,
                            ),
                            SyncMutationType.RELATION_SET,
                            ref.toSyncPayloadJson(),
                        )
                    )
                }
            }
        syncMutations.capture(
            drafts
        ) { dao.finalizeAssistantCitationState(message, citationRefs, annotations, annotationRefs) }
        touchConversation(message.conversationId)
        return message
    }

    /** 更新单个 Tool Call 的审批或执行结果。 */
    suspend fun updateToolCall(
        toolCall: LlmToolCallEntity,
        status: LlmToolCallStatus = toolCall.status,
        resultContent: String? = toolCall.resultContent,
        errorMessage: String? = toolCall.errorMessage,
    ): LlmToolCallEntity {
        val updated =
            toolCall.copy(
                status = status,
                resultContent = resultContent,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis(),
            )
        if (updated.status in setOf(LlmToolCallStatus.COMPLETE, LlmToolCallStatus.DENIED, LlmToolCallStatus.ERROR)) {
            syncMutations.capture(
                listOf(
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.TOOL_CALL,
                        localId = updated.id,
                        mutationType = SyncMutationType.UPSERT,
                        payloadJson = updated.toSyncPayloadJson(syncBlobStore),
                    )
                )
            ) { dao.updateToolCall(updated) }
        } else {
            dao.updateToolCall(updated)
        }
        touchConversation(toolCall.conversationId)
        return updated
    }

    /** 删除指定消息，主要用于重新生成前移除旧 assistant 回复。 */
    suspend fun deleteMessage(messageId: String) {
        syncMutations.capture(
            listOf(
                LlmSyncMutationDraft(
                    entityType = SyncEntityType.MESSAGE,
                    localId = messageId,
                    mutationType = SyncMutationType.GLOBAL_DELETE,
                    payloadJson = syncDeletePayloadJson(messageId),
                )
            )
        ) { dao.deleteMessage(messageId) }
    }

    /** 将上次进程退出时遗留的流式消息恢复为已停止状态。 */
    suspend fun recoverInterruptedGenerations(): Int {
        val now = System.currentTimeMillis()
        val recoveredMessages =
            dao.getMessagesByStatus(LlmMessageStatus.STREAMING).map { message ->
                message.copy(
                    status = LlmMessageStatus.STOPPED,
                    webSearchStatus =
                        if (message.webSearchStatus == WebSearchRequestStatus.TRIGGERED) {
                            WebSearchRequestStatus.CANCELLED
                        } else {
                            message.webSearchStatus
                        },
                    updatedAt = now,
                )
            }
        val interruptedToolMessage =
            "Tool execution was interrupted before its result could be confirmed."
        val recoveredToolCalls =
            dao.getToolCallsByStatus(LlmToolCallStatus.RUNNING).map { toolCall ->
                toolCall.copy(
                    status = LlmToolCallStatus.ERROR,
                    errorMessage = interruptedToolMessage,
                    updatedAt = now,
                )
            }
        val drafts = mutableListOf<LlmSyncMutationDraft>()
        recoveredMessages.forEach { message ->
            drafts +=
                LlmSyncMutationDraft(
                    entityType = SyncEntityType.MESSAGE,
                    localId = message.id,
                    mutationType = SyncMutationType.UPSERT,
                    payloadJson = message.toSyncPayloadJson(),
                )
            val contextRefs = dao.getContextRefsForAssistant(message.id)
            contextRefs.forEach { contextRef ->
                drafts +=
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.CONTEXT_REF,
                        localId = contextRef.id,
                        mutationType = SyncMutationType.UPSERT,
                        payloadJson = contextRef.toSyncPayloadJson(syncBlobStore),
                    )
                dao.getEvidenceBlocksForContextRef(contextRef.id).forEach { evidence ->
                    drafts +=
                        LlmSyncMutationDraft(
                            entityType = SyncEntityType.EVIDENCE_BLOCK,
                            localId = evidence.id,
                            mutationType = SyncMutationType.UPSERT,
                            payloadJson = evidence.toSyncPayloadJson(syncBlobStore),
                        )
                }
            }
            dao.getCitationRefsForAssistant(message.id).forEach { citation ->
                drafts +=
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.CITATION_REF,
                        localId = citation.id,
                        mutationType = SyncMutationType.UPSERT,
                        payloadJson = citation.toSyncPayloadJson(syncBlobStore),
                    )
            }
            val annotations = dao.getCitationAnnotationsForAssistant(message.id)
            annotations.forEach { occurrence ->
                drafts +=
                    LlmSyncMutationDraft(
                        entityType = SyncEntityType.CITATION_ANNOTATION,
                        localId = occurrence.annotation.id,
                        mutationType = SyncMutationType.UPSERT,
                        payloadJson = occurrence.annotation.toSyncPayloadJson(),
                    )
            }
            val annotationIds = annotations.mapTo(mutableSetOf()) { it.annotation.id }
            if (annotationIds.isNotEmpty()) {
                dao.getAllCitationAnnotationRefs()
                    .filter { it.annotationId in annotationIds }
                    .forEach { ref ->
                        drafts +=
                            LlmSyncMutationDraft(
                                entityType = SyncEntityType.CITATION_ANNOTATION_REF,
                                localId =
                                    SyncCanonicalIdentity.relationLocalId(
                                        SyncEntityType.CITATION_ANNOTATION_REF,
                                        ref.annotationId,
                                        ref.citationRefId,
                                    ),
                                mutationType = SyncMutationType.RELATION_SET,
                                payloadJson = ref.toSyncPayloadJson(),
                            )
                    }
            }
        }
        recoveredToolCalls.forEach { toolCall ->
            drafts +=
                LlmSyncMutationDraft(
                    entityType = SyncEntityType.TOOL_CALL,
                    localId = toolCall.id,
                    mutationType = SyncMutationType.UPSERT,
                    payloadJson = toolCall.toSyncPayloadJson(syncBlobStore),
                )
        }
        syncMutations.capture(drafts) {
            recoveredMessages.forEach { dao.updateMessage(it) }
            recoveredToolCalls.forEach { dao.updateToolCall(it) }
        }
        return recoveredMessages.size
    }
}

/** 本地会话标题最大字符数，避免抽屉中出现超长标题。 */
private const val MAX_CONVERSATION_TITLE_LENGTH = 48

/** 第一条用户消息只用于生成本地标题，不发起额外 AI 请求。 */
internal fun deriveConversationTitle(seed: String): String {
    val normalized = seed.trim().replace(Regex("\\s+"), " ")
    return normalized.take(MAX_CONVERSATION_TITLE_LENGTH).ifBlank { "New chat" }
}
