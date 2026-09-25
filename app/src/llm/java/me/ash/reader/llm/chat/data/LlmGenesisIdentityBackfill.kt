package me.ash.reader.llm.chat.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.GenesisIdentityBackfillReport
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityBackfillSupport
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import me.ash.reader.infrastructure.sync.identity.SyncIdentitySeed
import me.ash.reader.infrastructure.sync.identity.SyncIdentityTypeBackfillResult

/** Android 独立 Chat DB 的 Genesis identity scan，并通过主 Reader DB 解析文章引用。 */
@Singleton
class LlmGenesisIdentityBackfill @Inject constructor(
    private val readerDatabase: AndroidDatabase,
    private val chatDatabase: LlmChatDatabase,
) {
    suspend fun backfill(
        syncSpaceId: String,
        now: Long = System.currentTimeMillis(),
    ): GenesisIdentityBackfillReport {
        require(syncSpaceId.isNotBlank()) { "syncSpaceId must not be blank" }
        val source = chatDatabase.syncIdentitySourceDao()
        val conversations = source.conversations()
        val conversationArticles = source.conversationArticles()
        val contextRefs = source.contextRefs()
        val messageIds = source.messageIds()
        val toolCallIds = source.toolCallIds()
        val evidenceBlockIds = source.evidenceBlockIds()
        val citationRefIds = source.citationRefIds()
        val citationAnnotationIds = source.citationAnnotationIds()
        val annotationRefs = source.citationAnnotationRefs()

        val referencedArticleIds =
            buildSet {
                conversations.mapNotNullTo(this) { it.articleId }
                conversationArticles.mapTo(this) { it.articleId }
                contextRefs.mapNotNullTo(this) { it.articleId }
            }

        // Chat DB 不拥有 SyncSpace 真源，也不复制 Article Mapping。历史引用即使对应文章已经被本地清理，
        // 仍在 Reader 主库保留一条 canonicalKey=null 的 Article identity，保证冻结历史不会断引用。
        val referencedArticleResult =
            readerDatabase.withTransaction {
                SyncIdentityBackfillSupport.ensureSpace(readerDatabase.syncSpaceDao(), syncSpaceId, now)
                SyncIdentityBackfillSupport.backfillType(
                    dao = readerDatabase.syncIdentityMappingDao(),
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.ARTICLE,
                    seeds = referencedArticleIds.map { SyncIdentitySeed(localId = it) },
                    now = now,
                )
            }

        val articleMappings =
            readerDatabase.syncIdentityMappingDao()
                .findByType(syncSpaceId, SyncEntityType.ARTICLE.wireName)
                .associateBy(SyncIdentityMappingEntity::localId)

        val chatResults =
            chatDatabase.withTransaction {
                val mappingDao = chatDatabase.syncIdentityMappingDao()
                val results = mutableListOf<SyncIdentityTypeBackfillResult>()

                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.CONVERSATION,
                        conversations.map { uuidSeed(it.id) },
                        now,
                    )
                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.MESSAGE,
                        messageIds.map(::uuidSeed),
                        now,
                    )
                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.TOOL_CALL,
                        toolCallIds.map(::uuidSeed),
                        now,
                    )
                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.CONTEXT_REF,
                        contextRefs.map { uuidSeed(it.id) },
                        now,
                    )
                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.EVIDENCE_BLOCK,
                        evidenceBlockIds.map(::uuidSeed),
                        now,
                    )
                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.CITATION_REF,
                        citationRefIds.map(::uuidSeed),
                        now,
                    )
                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.CITATION_ANNOTATION,
                        citationAnnotationIds.map(::uuidSeed),
                        now,
                    )

                val conversationMappings =
                    mappingDao.findByType(syncSpaceId, SyncEntityType.CONVERSATION.wireName)
                        .associateBy(SyncIdentityMappingEntity::localId)
                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.CONVERSATION_ARTICLE,
                        conversationArticles.map { row ->
                            val conversationSyncId =
                                requireMapping(conversationMappings, row.conversationId, SyncEntityType.CONVERSATION)
                            val articleSyncId =
                                requireMapping(articleMappings, row.articleId, SyncEntityType.ARTICLE)
                            SyncIdentitySeed(
                                localId =
                                    SyncCanonicalIdentity.relationLocalId(
                                        SyncEntityType.CONVERSATION_ARTICLE,
                                        row.conversationId,
                                        row.articleId,
                                    ),
                                preferredSyncId =
                                    SyncCanonicalIdentity.relationSyncId(
                                        SyncEntityType.CONVERSATION_ARTICLE,
                                        conversationSyncId,
                                        articleSyncId,
                                    ),
                            )
                        },
                        now,
                    )

                val annotationMappings =
                    mappingDao.findByType(syncSpaceId, SyncEntityType.CITATION_ANNOTATION.wireName)
                        .associateBy(SyncIdentityMappingEntity::localId)
                val citationMappings =
                    mappingDao.findByType(syncSpaceId, SyncEntityType.CITATION_REF.wireName)
                        .associateBy(SyncIdentityMappingEntity::localId)
                results +=
                    SyncIdentityBackfillSupport.backfillType(
                        mappingDao,
                        syncSpaceId,
                        SyncEntityType.CITATION_ANNOTATION_REF,
                        annotationRefs.map { row ->
                            SyncIdentitySeed(
                                localId =
                                    SyncCanonicalIdentity.relationLocalId(
                                        SyncEntityType.CITATION_ANNOTATION_REF,
                                        row.annotationId,
                                        row.citationRefId,
                                    ),
                                preferredSyncId =
                                    SyncCanonicalIdentity.relationSyncId(
                                        SyncEntityType.CITATION_ANNOTATION_REF,
                                        requireMapping(
                                            annotationMappings,
                                            row.annotationId,
                                            SyncEntityType.CITATION_ANNOTATION,
                                        ),
                                        requireMapping(citationMappings, row.citationRefId, SyncEntityType.CITATION_REF),
                                    ),
                            )
                        },
                        now,
                    )
                results
            }

        return GenesisIdentityBackfillReport(
            syncSpaceId = syncSpaceId,
            results = listOf(referencedArticleResult) + chatResults,
        )
    }

    private fun uuidSeed(localId: String): SyncIdentitySeed =
        SyncIdentitySeed(
            localId = localId,
            preferredSyncId = SyncCanonicalIdentity.adoptUuidOrNull(localId),
        )

    private fun requireMapping(
        mappings: Map<String, SyncIdentityMappingEntity>,
        localId: String,
        entityType: SyncEntityType,
    ): String =
        requireNotNull(mappings[localId]?.syncId) {
            "Missing ${entityType.wireName} Sync ID for localId=$localId"
        }
}
