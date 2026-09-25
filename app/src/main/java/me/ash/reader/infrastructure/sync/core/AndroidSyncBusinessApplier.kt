package me.ash.reader.infrastructure.sync.core

import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rsshub.RssHubInstance
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceUserSyncState
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

/**
 * 当操作依赖的实体、空间映射或数据库前置行尚未就绪时抛出此异常。
 *
 * 协调器捕获后会将该操作置为 DEFERRED 状态，保留在 Ingestion 待处理列表中，
 * 绝不会误将未完全应用的操作推进至 AppliedCoverage。
 */
class SyncApplyDeferredException(message: String) : IllegalStateException(message)

/**
 * Reader 业务数据库的 Received -> Applied 业务投影执行器。
 *
 * 协议层操作仅携带跨端稳定的 Sync ID，本地主键严格通过 sync_identity_mapping 表解析；
 * 写入本地业务表前必须完成确定性版本（VersionToken）裁决并记录字段版本胜者，
 * 确保即使网络乱序或重试也不会用旧值覆盖新值或绑定到错误的本地记录。
 */
@Singleton
class AndroidSyncBusinessApplier @Inject constructor(
    private val database: AndroidDatabase,
    private val localBlobStore: SyncLocalBlobStore,
    private val blobState: SyncBlobStateService,
    private val localEviction: SyncLocalEvictionService,
    private val readerCacheHelper: ReaderCacheHelper,
    private val articleFilterRepository: ArticleFilterRepository,
    private val websiteRuleRepository: WebsiteRuleRepository,
    private val jsonRuleRepository: JsonRuleRepository,
    private val rssHubSettingsRepository: RssHubSettingsRepository,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    private val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
    private val projectionExtensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension> = emptySet(),
) : SyncRemoteApplyHandler {
    private val json = Json { ignoreUnknownKeys = true }
    private val aliasResolver =
        AndroidSyncAliasResolver(database).withFeedDeleteCleanup { localFeedId ->
            articleFilterRepository.deleteByFeed(localFeedId)
            websiteParsePreferenceRepository.delete(localFeedId)
            rssHubSubscriptionRepository.replaceSyncSource(localFeedId, null)
        }

    private suspend fun resolveFeedScopedConfigParent(
        syncSpaceId: String,
        feedSyncId: String,
        feedGeneration: Long?,
        label: String,
    ): String? {
        val feedMapping =
            database.syncIdentityMappingDao()
                .findBySyncId(
                    syncSpaceId,
                    SyncEntityType.FEED.wireName,
                    feedSyncId,
                ) ?: throw SyncApplyDeferredException("$label is waiting for feed $feedSyncId")

        if (feedGeneration != null) {
            if (feedGeneration < 0L) {
                throw SyncApplyDeferredException("$label has invalid feedGeneration")
            }
            if (feedMapping.generation < feedGeneration) {
                throw SyncApplyDeferredException(
                    "$label is waiting for feed $feedSyncId generation $feedGeneration"
                )
            }
            if (feedMapping.generation > feedGeneration) return null
        } else if (feedMapping.generation > 0L) {
            // Legacy feed-scoped CONFIG without an explicit parent generation is safe only for
            // generation zero. Once the Feed has revived, attaching it would be ambiguous.
            return null
        }

        if (database.feedDao().queryById(feedMapping.localId) != null) {
            return feedMapping.localId
        }

        val tombstone =
            database.syncInboxDao().findTombstone(
                syncSpaceId,
                SyncEntityType.FEED.wireName,
                feedSyncId,
            )
        val expectedGeneration = feedGeneration ?: feedMapping.generation
        if (tombstone != null && tombstone.entityGeneration >= expectedGeneration) return null

        throw SyncApplyDeferredException("$label is waiting for feed $feedSyncId")
    }

    private suspend fun resolveGroupParent(
        syncSpaceId: String,
        groupSyncId: String,
        groupGeneration: Long?,
        label: String,
    ): String? {
        val groupMapping =
            database.syncIdentityMappingDao()
                .findBySyncId(
                    syncSpaceId,
                    SyncEntityType.GROUP.wireName,
                    groupSyncId,
                ) ?: throw SyncApplyDeferredException("$label is waiting for group $groupSyncId")

        if (groupGeneration != null) {
            if (groupGeneration < 0L) {
                throw SyncApplyDeferredException("$label has invalid groupGeneration")
            }
            if (groupMapping.generation < groupGeneration) {
                throw SyncApplyDeferredException(
                    "$label is waiting for group $groupSyncId generation $groupGeneration"
                )
            }
            if (groupMapping.generation > groupGeneration) return null
        } else if (groupMapping.generation > 0L) {
            return null
        }

        if (database.groupDao().queryById(groupMapping.localId) != null) {
            return groupMapping.localId
        }

        val tombstone =
            database.syncInboxDao().findTombstone(
                syncSpaceId,
                SyncEntityType.GROUP.wireName,
                groupSyncId,
            )
        val expectedGeneration = groupGeneration ?: groupMapping.generation
        if (tombstone != null && tombstone.entityGeneration >= expectedGeneration) return null

        throw SyncApplyDeferredException("$label is waiting for group $groupSyncId")
    }

    private suspend fun pairedGenerationForWinner(
        operation: SyncOperationEntity,
        generationField: String,
        winnerToken: String,
    ): Long? {
        val paired =
            database.syncInboxDao().listFieldCandidates(operation.syncSpaceId)
                .firstOrNull {
                    it.entityType == operation.entityType &&
                        it.entitySyncId == operation.entitySyncId &&
                        it.entityGeneration == operation.entityGeneration &&
                        it.fieldId == generationField &&
                        sameRelationVersionOrigin(it.versionToken, winnerToken)
                }
        val value = paired?.valueJson ?: return null
        val parsed =
            runCatching { json.parseToJsonElement(value).jsonPrimitive.longOrNull }.getOrNull()
                ?: throw SyncApplyDeferredException(
                    "Invalid $generationField for ${operation.entityType}/${operation.entitySyncId}"
                )
        if (parsed < 0L) {
            throw SyncApplyDeferredException(
                "Invalid $generationField for ${operation.entityType}/${operation.entitySyncId}"
            )
        }
        return parsed
    }

    private fun sameRelationVersionOrigin(leftToken: String, rightToken: String): Boolean {
        if (leftToken == rightToken) return true
        if (!leftToken.startsWith("GENESIS_V1|") || !rightToken.startsWith("GENESIS_V1|")) {
            return false
        }
        val left = leftToken.split('|')
        val right = rightToken.split('|')
        return left.size == 6 &&
            right.size == 6 &&
            left[0] == right[0] &&
            left[1] == right[1] &&
            left[2] == right[2] &&
            left[3] == right[3]
    }

    private suspend fun pairedGenerationForProjectedField(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        idField: String,
        generationField: String,
    ): Long? {
        val idWinner =
            database.syncInboxDao().findFieldVersion(
                syncSpaceId,
                entityType,
                entitySyncId,
                idField,
            )?.takeIf { it.entityGeneration == entityGeneration }
        if (idWinner == null) {
            return rollbackBaselineGeneration(
                syncSpaceId,
                entityType,
                entitySyncId,
                entityGeneration,
                generationField,
            )
        }

        val paired =
            database.syncInboxDao().listFieldCandidates(syncSpaceId)
                .firstOrNull {
                    it.entityType == entityType &&
                        it.entitySyncId == entitySyncId &&
                        it.entityGeneration == entityGeneration &&
                        it.fieldId == generationField &&
                        sameRelationVersionOrigin(it.versionToken, idWinner.versionToken)
                }
        if (paired == null) {
            val revoked =
                idWinner.sourceOperationId
                    ?.let { database.syncInboxDao().find(it)?.state == "REJECTED" }
                    ?: false
            if (revoked) {
                return rollbackBaselineGeneration(
                    syncSpaceId,
                    entityType,
                    entitySyncId,
                    entityGeneration,
                    generationField,
                )
            }
            return null
        }

        val parsed =
            runCatching { json.parseToJsonElement(paired.valueJson).jsonPrimitive.longOrNull }
                .getOrNull()
                ?: throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: invalid $generationField for $entityType/$entitySyncId"
                )
        if (parsed < 0L) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: invalid $generationField for $entityType/$entitySyncId"
            )
        }
        return parsed
    }

    private suspend fun pairedGenerationForProjectedWinner(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        generationField: String,
        winnerToken: String,
    ): Long? {
        val paired =
            database.syncInboxDao().listFieldCandidates(syncSpaceId)
                .firstOrNull {
                    it.entityType == entityType &&
                        it.entitySyncId == entitySyncId &&
                        it.entityGeneration == entityGeneration &&
                        it.fieldId == generationField &&
                        sameRelationVersionOrigin(it.versionToken, winnerToken)
                } ?: return null
        val parsed =
            runCatching { json.parseToJsonElement(paired.valueJson).jsonPrimitive.longOrNull }
                .getOrNull()
                ?: throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: invalid $generationField for $entityType/$entitySyncId"
                )
        if (parsed < 0L) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: invalid $generationField for $entityType/$entitySyncId"
            )
        }
        return parsed
    }

    private suspend fun rollbackBaselineGeneration(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        generationField: String,
    ): Long? {
        val baseline =
            database.syncInboxDao().findRollbackBaseline(
                syncSpaceId,
                entityType,
                entitySyncId,
                entityGeneration,
                generationField,
            ) ?: return null
        val restoredValueJson = decodeRollbackBaseline(baseline.valueJson).first
        val parsed =
            runCatching {
                json.parseToJsonElement(restoredValueJson).jsonPrimitive.longOrNull
            }.getOrNull()
                ?: throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: invalid rollback baseline $generationField for $entityType/$entitySyncId"
                )
        if (parsed < 0L) {
            throw SyncRebaseUnsafeException(
                "REBASE_UNSAFE: invalid rollback baseline $generationField for $entityType/$entitySyncId"
            )
        }
        return parsed
    }

    private suspend fun resolveGenerationMetadataField(
        operation: SyncOperationEntity,
        field: String,
        incomingGeneration: Long,
        baselineGeneration: Long,
    ) {
        val current =
            database.syncInboxDao().findFieldVersion(
                operation.syncSpaceId,
                operation.entityType,
                operation.entitySyncId,
                field,
            )
        if (current == null || shouldRefreshProvisionalBaseline(operation, current)) {
            captureRollbackBaseline(
                operation,
                field,
                JsonPrimitive(baselineGeneration).toString(),
                replace = current != null,
            )
        }
        resolveRetainedField(
            operation,
            field,
            JsonPrimitive(incomingGeneration).toString(),
            current,
            SyncGenesisMergePolicy.DETERMINISTIC,
        )
    }

    internal fun readLocalBlob(entityType: String, hash: String): ByteArray? =
        if (entityType == SyncEntityType.ARTICLE.wireName) {
            localBlobStore.readVerified(hash)
        } else {
            projectionExtensions.firstOrNull { it.owns(entityType) }?.readLocalBlob(hash)
        }

    internal fun hasProjectionForEntity(entityType: String): Boolean =
        entityType == SyncEntityType.ARTICLE.wireName || projectionExtensions.any { it.owns(entityType) }

    internal fun canApplyWithoutBlob(entityType: String, referenceKind: String): Boolean =
        projectionExtensions.firstOrNull { it.owns(entityType) }
            ?.canApplyWithoutBlob(entityType, referenceKind)
            ?: false

    internal suspend fun shouldRefillReferencedBlob(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        referenceKind: String,
    ): Boolean {
        if (
            entityType == SyncEntityType.ARTICLE.wireName &&
            referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND
        ) {
            if (
                localEviction.isEvicted(
                    syncSpaceId,
                    entityType,
                    entitySyncId,
                    entityGeneration,
                    referenceKind,
                )
            ) {
                return false
            }
            return aliasResolver.resolveMapping(
                syncSpaceId,
                entityType,
                entitySyncId,
                entityGeneration,
            ) != null
        }
        return canApplyWithoutBlob(entityType, referenceKind)
    }

    internal suspend fun persistFetchedBlob(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        referenceKind: String,
        manifest: SyncBlobManifestWire,
        bytes: ByteArray,
    ) {
        if (entityType == SyncEntityType.ARTICLE.wireName) {
            localBlobStore.putVerified(manifest.hash, bytes)
            return
        }
        projectionExtensions.firstOrNull { it.owns(entityType) }
            ?.persistFetchedBlob(
                syncSpaceId = syncSpaceId,
                entityType = entityType,
                entitySyncId = entitySyncId,
                entityGeneration = entityGeneration,
                referenceKind = referenceKind,
                manifest = manifest,
                bytes = bytes,
            )
            ?: throw SyncApplyDeferredException("No Blob materializer for entity type " + entityType)
    }

    internal suspend fun persistAndMaterializeFetchedBlob(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        referenceKind: String,
        manifest: SyncBlobManifestWire,
        bytes: ByteArray,
    ) {
        if (database.syncBlobDao().listReferencesForBlob(syncSpaceId, manifest.hash).none {
            it.ownerEntityType == entityType && it.ownerEntitySyncId == entitySyncId &&
                it.ownerEntityGeneration == entityGeneration && it.referenceKind == referenceKind
        }) {
            localBlobStore.putVerified(manifest.hash, bytes)
            return
        }
        persistFetchedBlob(
            syncSpaceId = syncSpaceId,
            entityType = entityType,
            entitySyncId = entitySyncId,
            entityGeneration = entityGeneration,
            referenceKind = referenceKind,
            manifest = manifest,
            bytes = bytes,
        )
        if (
            entityType != SyncEntityType.ARTICLE.wireName ||
            referenceKind != SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND
        ) {
            return
        }
        val binding =
            database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
                ?: throw SyncApplyDeferredException("Missing local Space binding for Blob refill")
        val mapping =
            aliasResolver.resolveMapping(
                syncSpaceId,
                entityType,
                entitySyncId,
                entityGeneration,
            ) ?: throw SyncApplyDeferredException("Missing Article mapping for Blob refill")
        materializeSnapshotArticleFullContent(
            syncSpaceId = syncSpaceId,
            entitySyncId = entitySyncId,
            generation = entityGeneration,
            localAccountId = binding.localAccountId,
            localArticleId = mapping.localId,
            hash = manifest.hash,
        )
    }

    internal suspend fun shouldFetchBlob(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        reference: SyncPayloadBlobRefWire,
    ): Boolean {
        if (
            entityType != SyncEntityType.ARTICLE.wireName ||
            reference.referenceKind != SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND
        ) {
            return true
        }
        return !localEviction.isEvicted(
            syncSpaceId,
            entityType,
            entitySyncId,
            entityGeneration,
            SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND,
        )
    }

    internal suspend fun buildExtensionPendingOperations(
        syncSpaceId: String,
        limit: Int,
        now: Long,
    ): Int =
        projectionExtensions.sumOf { extension ->
            extension.buildPendingOperations(syncSpaceId, limit, now)
        }

    internal suspend fun hasExtensionPendingOutbox(syncSpaceId: String): Boolean =
        projectionExtensions.any { it.hasPendingOutbox(syncSpaceId) }

    override fun canApplyProvisionally(operation: SyncOperationEntity): Boolean {
        if (operation.operationType != SyncMutationType.FIELD_SET.name) return false
        val field =
            runCatching {
                json.parseToJsonElement(operation.payloadJson).jsonObject["field"]
                    ?.jsonPrimitive?.content
            }.getOrNull()
        return when (operation.entityType) {
            SyncEntityType.GROUP.wireName -> field == "name"
            SyncEntityType.FEED.wireName -> field == "name"
            SyncEntityType.ARTICLE.wireName -> field in setOf("isStarred", "isUnread", "isReadLater")
            else -> false
        }
    }

    /**
     * 将同步操作应用到本地业务数据库。
     *
     * @param operation 待应用的同步操作记录
     * @throws SyncApplyDeferredException 当依赖的本地数据或映射不存在时抛出，以便稍后重试
     */
    override suspend fun apply(operation: SyncOperationEntity) {
        if (operation.schemaVersion != 1 || operation.payloadSchemaVersion != 1) {
            throw SyncApplyDeferredException("Unsupported operation schema; retained for a compatible client")
        }
        when (operation.entityType) {
            SyncEntityType.ALIAS_EDGE.wireName -> {
                require(operation.operationType == SyncMutationType.UPSERT.name) {
                    "alias_edge only supports UPSERT"
                }
                aliasResolver.applyEdge(
                    operation.syncSpaceId,
                    json.decodeFromString<SyncAliasEdgePayloadV1>(operation.payloadJson),
                    operation.operationId,
                )
            }
            SyncEntityType.GROUP.wireName -> applyGroup(operation)
            SyncEntityType.FEED.wireName -> applyFeed(operation)
            SyncEntityType.ARTICLE.wireName -> applyArticle(operation)
            SyncEntityType.FILTER_RULE.wireName -> applyFilterRule(operation)
            SyncEntityType.WEBSITE_RULE.wireName -> applyWebsiteRule(operation)
            SyncEntityType.JSON_RULE.wireName -> applyJsonRule(operation)
            SyncEntityType.RSSHUB_SETTINGS.wireName -> applyRssHubSettings(operation)
            SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName ->
                applyWebsiteParsePreference(operation)
            SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName ->
                applyRssHubSubscriptionSource(operation)
            else -> {
                val extension = projectionExtensions.firstOrNull { it.owns(operation.entityType) }
                if (extension != null) {
                    extension.apply(operation)
                } else if (operation.operationType == SyncMutationType.RELATION_SET.name) {
                    applyRelation(operation)
                } else {
                    throw SyncApplyDeferredException("Entity projection unavailable: ${operation.entityType}")
                }
            }
        }
    }

    /**
     * 处理 Group 分组的业务投影（创建、改名、删除）。
     */
    private suspend fun applyFilterRule(operation: SyncOperationEntity) {
        val mappingDao = database.syncIdentityMappingDao()
        var mapping = mappingDao.findBySyncId(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
        if (mapping != null && mapping.generation > operation.entityGeneration) return
        val tombstone = database.syncInboxDao().findTombstone(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
        if (tombstone != null && tombstone.entityGeneration >= operation.entityGeneration) {
            if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name && mapping != null) {
                val localId = mapping.localId
                articleFilterRepository.getAll()
                    .find { it.id == localId }
                    ?.let(articleFilterRepository::delete)
            }
            return
        }
        val now = System.currentTimeMillis()
        if (mapping == null) {
            mapping = SyncIdentityMappingEntity(operation.syncSpaceId, operation.entityType,
                UUID.nameUUIDFromBytes(("filter:" + operation.syncSpaceId + ":" + operation.entitySyncId).toByteArray(Charsets.UTF_8)).toString(),
                operation.entitySyncId, generation = operation.entityGeneration, createdAt = now, updatedAt = now)
            mappingDao.insert(mapping)
        } else if (mapping.generation < operation.entityGeneration) {
            mapping = mapping.copy(generation = operation.entityGeneration, updatedAt = now)
            mappingDao.update(mapping)
        }
        val activeMapping = checkNotNull(mapping)
        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            articleFilterRepository.getAll().find { it.id == activeMapping.localId }?.let(articleFilterRepository::delete)
            database.syncInboxDao().upsertTombstone(SyncTombstoneEntity(operation.syncSpaceId, operation.entityType,
                operation.entitySyncId, operation.entityGeneration, SyncVersionToken.operation(operation.actorIncarnationId,
                    operation.replicationLaneId, operation.sequence), operation.operationId, now))
            return
        }
        val payload = parsePayload(operation.payloadJson)
        val fields = when (operation.operationType) {
            SyncMutationType.UPSERT.name -> (payload["fields"] as? JsonObject) ?: payload
            SyncMutationType.FIELD_SET.name -> JsonObject(mapOf(payload.getValue("field").jsonPrimitive.content to payload.getValue("value")))
            else -> throw SyncApplyDeferredException("Unsupported filter rule mutation")
        }
        val existing = articleFilterRepository.getAll().find { it.id == activeMapping.localId }
        val resolvedWinners = fields.mapValues { (field, value) ->
            val current = database.syncInboxDao().findFieldVersion(operation.syncSpaceId, operation.entityType, operation.entitySyncId, field)
            if (
                existing != null &&
                (current == null || shouldRefreshProvisionalBaseline(operation, current))
            ) {
                val baselineJson =
                    when (field) {
                        "keyword" -> JsonPrimitive(existing.keyword).toString()
                        "feedSyncId" ->
                            if (existing.feedId == null) {
                                JsonNull.toString()
                            } else {
                                mappingDao.findByLocalId(
                                    operation.syncSpaceId,
                                    SyncEntityType.FEED.wireName,
                                    existing.feedId,
                                )?.let { JsonPrimitive(it.syncId).toString() }
                            }
                        "feedGeneration" ->
                            if (existing.feedId == null) {
                                JsonNull.toString()
                            } else {
                                mappingDao.findByLocalId(
                                    operation.syncSpaceId,
                                    SyncEntityType.FEED.wireName,
                                    existing.feedId,
                                )?.let { JsonPrimitive(it.generation).toString() }
                            }
                        "feedName" ->
                            existing.feedName?.let { JsonPrimitive(it).toString() }
                                ?: JsonNull.toString()
                        "type" -> JsonPrimitive(existing.type.name).toString()
                        "enabled" -> JsonPrimitive(existing.enabled).toString()
                        else -> null
                    }
                if (baselineJson != null) {
                    captureRollbackBaseline(
                        operation = operation,
                        field = field,
                        valueJson = baselineJson,
                        replace = current != null,
                    )
                }
            }
            resolveRetainedField(
                operation,
                field,
                value.toString(),
                current,
                SyncGenesisMergePolicy.DETERMINISTIC,
            )
        }
        val resolved =
            resolvedWinners.mapValues { (_, winner) ->
                json.parseToJsonElement(winner.valueJson)
            }
        suspend fun retained(field: String): JsonElement? =
            resolved[field]
                ?: database.syncInboxDao()
                    .findFieldVersion(
                        operation.syncSpaceId,
                        operation.entityType,
                        operation.entitySyncId,
                        field,
                    )
                    ?.takeIf { it.entityGeneration == activeMapping.generation }
                    ?.let { json.parseToJsonElement(it.valueJson) }
        suspend fun text(field: String, fallback: String?): String? =
            retained(field)?.let { value ->
                if (value == JsonNull) null else (value as? JsonPrimitive)?.content
            } ?: fallback
        val feedSyncId = text("feedSyncId", null)
        val feedGeneration =
            if (feedSyncId == null) {
                null
            } else {
                database.syncInboxDao()
                    .findFieldVersion(
                        operation.syncSpaceId,
                        operation.entityType,
                        operation.entitySyncId,
                        "feedSyncId",
                    )
                    ?.takeIf { it.entityGeneration == activeMapping.generation }
                    ?.let { winner ->
                        pairedGenerationForWinner(
                            operation = operation,
                            generationField = "feedGeneration",
                            winnerToken = winner.versionToken,
                        )
                    }
            }
        val feedId =
            if (feedSyncId == null) {
                existing?.feedId
            } else {
                resolveFeedScopedConfigParent(
                    syncSpaceId = operation.syncSpaceId,
                    feedSyncId = feedSyncId,
                    feedGeneration = feedGeneration,
                    label = "Filter rule " + operation.entitySyncId,
                ) ?: run {
                    articleFilterRepository.getAll()
                        .find { it.id == activeMapping.localId }
                        ?.let(articleFilterRepository::delete)
                    return
                }
            }
        articleFilterRepository.upsert(ArticleFilterRule(id = activeMapping.localId,
            keyword = text("keyword", existing?.keyword) ?: throw SyncApplyDeferredException("Missing filter rule keyword"),
            feedId = feedId, feedName = text("feedName", existing?.feedName),
            type = text("type", existing?.type?.name)?.let(ArticleFilterRuleType::valueOf) ?: ArticleFilterRuleType.KEYWORD,
            enabled = (resolved["enabled"] as? JsonPrimitive)?.booleanOrNull ?: existing?.enabled ?: true))
    }

    private suspend fun applyWebsiteRule(operation: SyncOperationEntity) {
        applyAtomicConfigRule(
            operation = operation,
            entityType = SyncEntityType.WEBSITE_RULE,
            decode = { value -> json.decodeFromString<WebsiteRule>(value) },
            encode = { value -> json.encodeToString(value) },
            idOf = WebsiteRule::id,
            read = websiteRuleRepository::listSyncRules,
            replace = websiteRuleRepository::replaceSyncRules,
        )
    }

    private suspend fun applyJsonRule(operation: SyncOperationEntity) {
        applyAtomicConfigRule(
            operation = operation,
            entityType = SyncEntityType.JSON_RULE,
            decode = { value -> json.decodeFromString<JsonRule>(value) },
            encode = { value -> json.encodeToString(value) },
            idOf = JsonRule::id,
            read = jsonRuleRepository::listSyncRules,
            replace = jsonRuleRepository::replaceSyncRules,
        )
    }

    private suspend fun <T> applyAtomicConfigRule(
        operation: SyncOperationEntity,
        entityType: SyncEntityType,
        decode: (String) -> T,
        encode: (T) -> String,
        idOf: (T) -> String,
        read: () -> List<T>,
        replace: (List<T>) -> Any,
    ) {
        val mappingDao = database.syncIdentityMappingDao()
        var mapping =
            mappingDao.findBySyncId(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
            )
        if (mapping != null && mapping.generation > operation.entityGeneration) return
        val tombstone =
            database.syncInboxDao().findTombstone(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
            )
        if (tombstone != null && tombstone.entityGeneration >= operation.entityGeneration) {
            if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name && mapping != null) {
                val localId = mapping.localId
                replace(read().filterNot { idOf(it) == localId })
            }
            return
        }
        val now = System.currentTimeMillis()

        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            mapping?.let { current ->
                replace(read().filterNot { idOf(it) == current.localId })
            }
            database.syncInboxDao().upsertTombstone(
                SyncTombstoneEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = entityType.wireName,
                    entitySyncId = operation.entitySyncId,
                    entityGeneration = operation.entityGeneration,
                    versionToken =
                        SyncVersionToken.operation(
                            operation.actorIncarnationId,
                            operation.replicationLaneId,
                            operation.sequence,
                        ),
                    sourceOperationId = operation.operationId,
                    updatedAt = now,
                )
            )
            return
        }

        val payload = parsePayload(operation.payloadJson)
        val fields =
            when (operation.operationType) {
                SyncMutationType.UPSERT.name -> (payload["fields"] as? JsonObject) ?: payload
                SyncMutationType.FIELD_SET.name ->
                    JsonObject(
                        mapOf(
                            payload.getValue("field").jsonPrimitive.content to
                                payload.getValue("value")
                        )
                    )
                else -> throw SyncApplyDeferredException("Unsupported CONFIG rule mutation")
            }
        val incomingValue =
            fields["rule"]
                ?: throw SyncApplyDeferredException("CONFIG rule operation has no rule field")
        val current =
            database.syncInboxDao().findFieldVersion(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
                "rule",
            )
        val existingRule =
            mapping
                ?.takeIf { it.generation == operation.entityGeneration }
                ?.let { active -> read().firstOrNull { idOf(it) == active.localId } }
        if (
            existingRule != null &&
            (current == null || shouldRefreshProvisionalBaseline(operation, current))
        ) {
            captureRollbackBaseline(
                operation = operation,
                field = "rule",
                valueJson = SyncOperationCanonicalizer.canonicalJson(encode(existingRule)),
                replace = current != null,
            )
        }
        val winner =
            resolveRetainedField(
                operation,
                "rule",
                incomingValue.toString(),
                current,
                SyncGenesisMergePolicy.DETERMINISTIC,
            )
        val decoded =
            runCatching { decode(winner.valueJson) }
                .getOrElse {
                    throw SyncApplyDeferredException(
                        "Invalid " + entityType.wireName + " payload: " + it.message.orEmpty()
                    )
                }
        val localId = idOf(decoded)
        val expectedSyncId = SyncCanonicalIdentity.configRuleSyncId(entityType, localId)
        check(expectedSyncId == operation.entitySyncId) {
            "CONFIG identity mismatch for " + entityType.wireName + "/" + localId
        }
        if (mapping == null) {
            val created =
                SyncIdentityMappingEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = entityType.wireName,
                    localId = localId,
                    syncId = operation.entitySyncId,
                    canonicalKey = null,
                    generation = operation.entityGeneration,
                    createdAt = now,
                    updatedAt = now,
                )
            mappingDao.insert(created)
            mapping = created
        } else {
            check(mapping.localId == localId) {
                "CONFIG mapping localId mismatch for " + entityType.wireName
            }
            if (mapping.generation < operation.entityGeneration) {
                val updated =
                    mapping.copy(
                        generation = operation.entityGeneration,
                        updatedAt = now,
                    )
                mappingDao.update(updated)
                mapping = updated
            }
        }
        replace(read().filterNot { idOf(it) == localId } + decoded)
    }

    private suspend fun applyRssHubSettings(operation: SyncOperationEntity) {
        val entityType = SyncEntityType.RSSHUB_SETTINGS
        val expectedSyncId =
            SyncCanonicalIdentity.configRuleSyncId(entityType, "rsshub-settings")
        check(operation.entitySyncId == expectedSyncId) {
            "RSSHub settings Sync identity mismatch"
        }
        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            throw SyncApplyDeferredException("RSSHub settings cannot be globally deleted")
        }
        val mappingDao = database.syncIdentityMappingDao()
        var mapping =
            mappingDao.findBySyncId(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
            )
        if (mapping != null && mapping.generation > operation.entityGeneration) return
        val baselineEligible = mapping?.generation == operation.entityGeneration
        val now = System.currentTimeMillis()
        if (mapping == null) {
            val created =
                SyncIdentityMappingEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = entityType.wireName,
                    localId = "rsshub-settings",
                    syncId = operation.entitySyncId,
                    canonicalKey = null,
                    generation = operation.entityGeneration,
                    createdAt = now,
                    updatedAt = now,
                )
            mappingDao.insert(created)
            mapping = created
        } else {
            check(mapping.localId == "rsshub-settings") {
                "RSSHub settings mapping localId mismatch"
            }
            if (mapping.generation < operation.entityGeneration) {
                val updated =
                    mapping.copy(
                        generation = operation.entityGeneration,
                        updatedAt = now,
                    )
                mappingDao.update(updated)
                mapping = updated
            }
        }

        val payload = parsePayload(operation.payloadJson)
        val fields =
            when (operation.operationType) {
                SyncMutationType.UPSERT.name -> (payload["fields"] as? JsonObject) ?: payload
                SyncMutationType.FIELD_SET.name ->
                    JsonObject(
                        mapOf(
                            payload.getValue("field").jsonPrimitive.content to
                                payload.getValue("value")
                        )
                    )
                else -> throw SyncApplyDeferredException("Unsupported RSSHub settings mutation")
            }
        val incomingValue =
            fields["settings"]
                ?: throw SyncApplyDeferredException("RSSHub settings operation has no settings field")
        val current =
            database.syncInboxDao().findFieldVersion(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
                "settings",
            )
        if (
            baselineEligible &&
            (current == null || shouldRefreshProvisionalBaseline(operation, current))
        ) {
            captureRollbackBaseline(
                operation = operation,
                field = "settings",
                valueJson = encodeRssHubSettings(rssHubSettingsRepository.current()),
                replace = current != null,
            )
        }
        val winner =
            resolveRetainedField(
                operation,
                "settings",
                incomingValue.toString(),
                current,
                SyncGenesisMergePolicy.DETERMINISTIC,
            )
        rssHubSettingsRepository.replaceSyncSettings(decodeRssHubSettings(winner.valueJson))
    }

    private suspend fun applyWebsiteParsePreference(operation: SyncOperationEntity) {
        val entityType = SyncEntityType.WEBSITE_PARSE_PREFERENCE
        val mappingDao = database.syncIdentityMappingDao()
        var mapping =
            mappingDao.findBySyncId(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
            )
        if (mapping != null && mapping.generation > operation.entityGeneration) return
        val tombstone =
            database.syncInboxDao().findTombstone(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
            )
        if (tombstone != null && tombstone.entityGeneration >= operation.entityGeneration) {
            if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
                mapping?.localId?.let { feedSyncId ->
                    mappingDao.findBySyncId(
                        operation.syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        feedSyncId,
                    )?.localId?.let { localFeedId ->
                        websiteParsePreferenceRepository.applyUserSyncState(localFeedId, null)
                    }
                }
            }
            return
        }

        val now = System.currentTimeMillis()

        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            mapping?.localId?.let { feedSyncId ->
                mappingDao.findBySyncId(
                    operation.syncSpaceId,
                    SyncEntityType.FEED.wireName,
                    feedSyncId,
                )?.localId?.let { localFeedId ->
                    websiteParsePreferenceRepository.applyUserSyncState(localFeedId, null)
                }
            }
            database.syncInboxDao().upsertTombstone(
                SyncTombstoneEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = entityType.wireName,
                    entitySyncId = operation.entitySyncId,
                    entityGeneration = operation.entityGeneration,
                    versionToken =
                        SyncVersionToken.operation(
                            operation.actorIncarnationId,
                            operation.replicationLaneId,
                            operation.sequence,
                        ),
                    sourceOperationId = operation.operationId,
                    updatedAt = now,
                )
            )
            return
        }

        val payload = parsePayload(operation.payloadJson)
        val fields =
            when (operation.operationType) {
                SyncMutationType.UPSERT.name -> (payload["fields"] as? JsonObject) ?: payload
                SyncMutationType.FIELD_SET.name ->
                    JsonObject(
                        mapOf(
                            payload.getValue("field").jsonPrimitive.content to
                                payload.getValue("value")
                        )
                    )
                else ->
                    throw SyncApplyDeferredException(
                        "Unsupported website parse preference mutation"
                    )
            }
        val incomingValue =
            fields["preference"]
                ?: throw SyncApplyDeferredException(
                    "Website parse preference operation has no preference field"
                )
        val current =
            database.syncInboxDao().findFieldVersion(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
                "preference",
            )
        val baselineMapping =
            mapping?.takeIf { it.generation == operation.entityGeneration }
        if (
            baselineMapping != null &&
            (current == null || shouldRefreshProvisionalBaseline(operation, current))
        ) {
            val feedMapping =
                database.syncIdentityMappingDao().findBySyncId(
                    operation.syncSpaceId,
                    SyncEntityType.FEED.wireName,
                    baselineMapping.localId,
                )
            if (feedMapping != null) {
                val state = websiteParsePreferenceRepository.getUserSyncState(feedMapping.localId)
                val baselineJson =
                    state?.let {
                        buildJsonObject {
                            put("feedSyncId", feedMapping.syncId)
                            put("feedGeneration", feedMapping.generation)
                            put("dynamicRenderingEnabled", it.dynamicRenderingEnabled)
                            it.preferredRuleId?.let { value -> put("preferredRuleId", value) }
                                ?: put("preferredRuleId", JsonNull)
                            it.preferredRuleName?.let { value -> put("preferredRuleName", value) }
                                ?: put("preferredRuleName", JsonNull)
                        }.toString()
                    } ?: buildJsonObject {
                        put("feedSyncId", feedMapping.syncId)
                        put("feedGeneration", feedMapping.generation)
                        put("__syncAbsent", true)
                    }.toString()
                captureRollbackBaseline(
                    operation = operation,
                    field = "preference",
                    valueJson = baselineJson,
                    replace = current != null,
                )
            }
        }
        val winner =
            resolveRetainedField(
                operation,
                "preference",
                incomingValue.toString(),
                current,
                SyncGenesisMergePolicy.DETERMINISTIC,
            )
        val preference =
            runCatching { json.parseToJsonElement(winner.valueJson).jsonObject }
                .getOrElse {
                    throw SyncApplyDeferredException(
                        "Invalid website parse preference payload: " + it.message.orEmpty()
                    )
                }
        val feedSyncId =
            preference["feedSyncId"]?.jsonPrimitive?.content
                ?: throw SyncApplyDeferredException(
                    "Website parse preference payload has no feedSyncId"
                )
        val feedGeneration =
            preference["feedGeneration"]
                ?.takeIf { it != JsonNull }
                ?.jsonPrimitive
                ?.longOrNull
        val expectedSyncId =
            SyncCanonicalIdentity.configRuleSyncId(entityType, feedSyncId)
        check(operation.entitySyncId == expectedSyncId) {
            "Website parse preference Sync identity mismatch"
        }
        val localFeedId =
            resolveFeedScopedConfigParent(
                syncSpaceId = operation.syncSpaceId,
                feedSyncId = feedSyncId,
                feedGeneration = feedGeneration,
                label = "Website parse preference ${operation.entitySyncId}",
            ) ?: return

        if (mapping == null) {
            val created =
                SyncIdentityMappingEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = entityType.wireName,
                    localId = feedSyncId,
                    syncId = operation.entitySyncId,
                    canonicalKey = null,
                    generation = operation.entityGeneration,
                    createdAt = now,
                    updatedAt = now,
                )
            mappingDao.insert(created)
            mapping = created
        } else {
            check(mapping.localId == feedSyncId) {
                "Website parse preference mapping localId mismatch"
            }
            if (mapping.generation < operation.entityGeneration) {
                val updated =
                    mapping.copy(
                        generation = operation.entityGeneration,
                        updatedAt = now,
                    )
                mappingDao.update(updated)
                mapping = updated
            }
        }

        if (preference["__syncAbsent"]?.jsonPrimitive?.booleanOrNull == true) {
            websiteParsePreferenceRepository.applyUserSyncState(localFeedId, null)
        } else {
            fun nullableText(field: String): String? =
                preference[field]?.let { value ->
                    if (value == JsonNull) null else value.jsonPrimitive.content
                }
            websiteParsePreferenceRepository.applyUserSyncState(
                localFeedId,
                WebsiteParsePreferenceUserSyncState(
                    dynamicRenderingEnabled =
                        preference["dynamicRenderingEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    preferredRuleId = nullableText("preferredRuleId"),
                    preferredRuleName = nullableText("preferredRuleName"),
                ),
            )
        }
    }

    private suspend fun applyRssHubSubscriptionSource(operation: SyncOperationEntity) {
        val entityType = SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE
        val mappingDao = database.syncIdentityMappingDao()
        var mapping =
            mappingDao.findBySyncId(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
            )
        if (mapping != null && mapping.generation > operation.entityGeneration) return
        val tombstone =
            database.syncInboxDao().findTombstone(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
            )
        if (tombstone != null && tombstone.entityGeneration >= operation.entityGeneration) {
            if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
                mapping?.localId?.let { feedSyncId ->
                    mappingDao.findBySyncId(
                        operation.syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        feedSyncId,
                    )?.localId?.let { localFeedId ->
                        rssHubSubscriptionRepository.replaceSyncSource(localFeedId, null)
                    }
                }
            }
            return
        }

        val now = System.currentTimeMillis()

        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            mapping?.localId?.let { feedSyncId ->
                mappingDao.findBySyncId(
                    operation.syncSpaceId,
                    SyncEntityType.FEED.wireName,
                    feedSyncId,
                )?.localId?.let { localFeedId ->
                    rssHubSubscriptionRepository.replaceSyncSource(localFeedId, null)
                }
            }
            database.syncInboxDao().upsertTombstone(
                SyncTombstoneEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = entityType.wireName,
                    entitySyncId = operation.entitySyncId,
                    entityGeneration = operation.entityGeneration,
                    versionToken =
                        SyncVersionToken.operation(
                            operation.actorIncarnationId,
                            operation.replicationLaneId,
                            operation.sequence,
                        ),
                    sourceOperationId = operation.operationId,
                    updatedAt = now,
                )
            )
            return
        }

        val payload = parsePayload(operation.payloadJson)
        val fields =
            when (operation.operationType) {
                SyncMutationType.UPSERT.name -> (payload["fields"] as? JsonObject) ?: payload
                SyncMutationType.FIELD_SET.name ->
                    JsonObject(
                        mapOf(
                            payload.getValue("field").jsonPrimitive.content to
                                payload.getValue("value")
                        )
                    )
                else ->
                    throw SyncApplyDeferredException(
                        "Unsupported RSSHub subscription source mutation"
                    )
            }
        val incomingValue =
            fields["source"]
                ?: throw SyncApplyDeferredException(
                    "RSSHub subscription source operation has no source field"
                )
        val current =
            database.syncInboxDao().findFieldVersion(
                operation.syncSpaceId,
                entityType.wireName,
                operation.entitySyncId,
                "source",
            )
        val baselineMapping =
            mapping?.takeIf { it.generation == operation.entityGeneration }
        if (
            baselineMapping != null &&
            (current == null || shouldRefreshProvisionalBaseline(operation, current))
        ) {
            val feedMapping =
                database.syncIdentityMappingDao().findBySyncId(
                    operation.syncSpaceId,
                    SyncEntityType.FEED.wireName,
                    baselineMapping.localId,
                )
            if (feedMapping != null) {
                val sourceUrl = rssHubSubscriptionRepository.sourceUrl(feedMapping.localId)
                val baselineJson =
                    sourceUrl?.let {
                        buildJsonObject {
                            put("feedSyncId", feedMapping.syncId)
                            put("feedGeneration", feedMapping.generation)
                            put("sourceUrl", it)
                        }.toString()
                    } ?: buildJsonObject {
                        put("feedSyncId", feedMapping.syncId)
                        put("feedGeneration", feedMapping.generation)
                        put("__syncAbsent", true)
                    }.toString()
                captureRollbackBaseline(
                    operation = operation,
                    field = "source",
                    valueJson = baselineJson,
                    replace = current != null,
                )
            }
        }
        val winner =
            resolveRetainedField(
                operation,
                "source",
                incomingValue.toString(),
                current,
                SyncGenesisMergePolicy.DETERMINISTIC,
            )
        val source =
            runCatching { json.parseToJsonElement(winner.valueJson).jsonObject }
                .getOrElse {
                    throw SyncApplyDeferredException(
                        "Invalid RSSHub subscription source payload: " + it.message.orEmpty()
                    )
                }
        val feedSyncId =
            source["feedSyncId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                ?: throw SyncApplyDeferredException(
                    "RSSHub subscription source payload has no feedSyncId"
                )
        val feedGeneration =
            source["feedGeneration"]
                ?.takeIf { it != JsonNull }
                ?.jsonPrimitive
                ?.longOrNull
        val isAbsent = source["__syncAbsent"]?.jsonPrimitive?.booleanOrNull == true
        val sourceUrl =
            if (isAbsent) {
                null
            } else {
                source["sourceUrl"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
                    ?: throw SyncApplyDeferredException(
                        "RSSHub subscription source payload has no sourceUrl"
                    )
            }
        val expectedSyncId =
            SyncCanonicalIdentity.configRuleSyncId(entityType, feedSyncId)
        check(operation.entitySyncId == expectedSyncId) {
            "RSSHub subscription source Sync identity mismatch"
        }
        val localFeedId =
            resolveFeedScopedConfigParent(
                syncSpaceId = operation.syncSpaceId,
                feedSyncId = feedSyncId,
                feedGeneration = feedGeneration,
                label = "RSSHub subscription source ${operation.entitySyncId}",
            ) ?: return

        if (mapping == null) {
            val created =
                SyncIdentityMappingEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = entityType.wireName,
                    localId = feedSyncId,
                    syncId = operation.entitySyncId,
                    canonicalKey = null,
                    generation = operation.entityGeneration,
                    createdAt = now,
                    updatedAt = now,
                )
            mappingDao.insert(created)
            mapping = created
        } else {
            check(mapping.localId == feedSyncId) {
                "RSSHub subscription source mapping localId mismatch"
            }
            if (mapping.generation < operation.entityGeneration) {
                val updated =
                    mapping.copy(
                        generation = operation.entityGeneration,
                        updatedAt = now,
                    )
                mappingDao.update(updated)
                mapping = updated
            }
        }
        rssHubSubscriptionRepository.replaceSyncSource(localFeedId, sourceUrl)
    }

    private fun decodeRssHubSettings(valueJson: String): RssHubSettings {
        val root = json.parseToJsonElement(valueJson).jsonObject
        val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val instances =
            root["instances"]?.jsonArray?.map { value ->
                val item = value.jsonObject
                RssHubInstance(
                    id = item.getValue("id").jsonPrimitive.content,
                    url = item.getValue("url").jsonPrimitive.content,
                    location = item["location"]?.jsonPrimitive?.content.orEmpty(),
                    maintainer = item["maintainer"]?.jsonPrimitive?.content.orEmpty(),
                    enabled = item["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    builtIn = item["builtIn"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }.orEmpty()
        return RssHubSettings(enabled = enabled, instances = instances)
    }

    private fun encodeRssHubSettings(settings: RssHubSettings): String =
        SyncOperationCanonicalizer.canonicalJson(
            buildJsonObject {
                put("enabled", settings.enabled)
                put(
                    "instances",
                    buildJsonArray {
                        settings.instances.forEach { instance ->
                            add(
                                buildJsonObject {
                                    put("id", instance.id)
                                    put("url", instance.url)
                                    put("location", instance.location)
                                    put("maintainer", instance.maintainer)
                                    put("enabled", instance.enabled)
                                    put("builtIn", instance.builtIn)
                                }
                            )
                        }
                    },
                )
            }.toString()
        )

    private suspend fun applyGroup(operation: SyncOperationEntity) {
        val binding = database.syncRuntimeDao().findBindingBySpace(operation.syncSpaceId)
            ?: throw SyncApplyDeferredException("Missing local Space binding")

        val tombstone = database.syncInboxDao().findTombstone(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
        if (tombstone != null && operation.entityGeneration <= tombstone.entityGeneration) {
            if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
                aliasResolver.applyGlobalDelete(operation)
            }
            return
        }

        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            val mapping =
                aliasResolver.resolveMapping(
                    operation.syncSpaceId,
                    operation.entityType,
                    operation.entitySyncId,
                    operation.entityGeneration,
                )
            val group = mapping?.let { database.groupDao().queryById(it.localId) }
            if (
                group != null &&
                database.feedDao()
                    .queryByGroupId(binding.localAccountId, group.id)
                    .isNotEmpty()
            ) {
                throw SyncApplyDeferredException(
                    "Group delete is waiting for dependent Feed deletes"
                )
            }
            aliasResolver.applyGlobalDelete(operation)
            return
        }

        val payload = parsePayload(operation.payloadJson)
        val fields = when (operation.operationType) {
            SyncMutationType.FIELD_SET.name -> {
                require(payload["field"]?.jsonPrimitive?.content == "name") { "Unsupported group field" }
                mapOf("name" to (payload["value"] ?: JsonNull))
            }
            SyncMutationType.UPSERT.name -> ((payload["fields"] as? JsonObject) ?: payload).toMap()
            else -> throw SyncApplyDeferredException("Group operation projection unavailable: ${operation.operationType}")
        }
        require(fields.keys == setOf("name")) { "Group payload requires only the name field" }
        val nameValue = fields.getValue("name") as? JsonPrimitive
        require(nameValue != null && nameValue.isString) { "Group name must be a string" }

        val mapping = aliasResolver.resolveMapping(
            operation.syncSpaceId,
            operation.entityType,
            operation.entitySyncId,
            operation.entityGeneration,
        )
        val group = mapping?.let { database.groupDao().queryById(it.localId) }
        if (group != null) check(group.accountId == binding.localAccountId) { "Group belongs to another account" }
        if (group == null && operation.operationType != SyncMutationType.UPSERT.name) {
            throw SyncApplyDeferredException("Missing group row")
        }

        val token = SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence)
        val valueJson = nameValue.toString()
        val current = database.syncInboxDao().findFieldVersion(operation.syncSpaceId, operation.entityType, operation.entitySyncId, "name")
        if (
            group != null &&
            (current == null || shouldRefreshProvisionalBaseline(operation, current))
        ) {
            captureRollbackBaseline(
                operation,
                "name",
                JsonPrimitive(group.name).toString(),
                replace = current != null,
            )
        }
        val winner = resolveRetainedField(operation, "name", valueJson, current, SyncGenesisMergePolicy.DETERMINISTIC)
        val winningName = json.parseToJsonElement(winner.valueJson).jsonPrimitive.content
        val now = System.currentTimeMillis()

        if (group == null) {
            val localId = mapping?.localId ?: UUID.randomUUID().toString()
            database.groupDao().insertAll(listOf(Group(localId, winningName, binding.localAccountId)))
            if (mapping == null) {
                database.syncIdentityMappingDao().insert(
                    SyncIdentityMappingEntity(
                        syncSpaceId = operation.syncSpaceId,
                        entityType = operation.entityType,
                        localId = localId,
                        syncId = operation.entitySyncId,
                        generation = operation.entityGeneration,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
        } else {
            database.groupDao().update(group.copy(name = winningName))
        }

        if (winner.token == token) {
            database.syncInboxDao().upsertFieldVersion(
                SyncFieldVersionEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = operation.entityType,
                    entitySyncId = operation.entitySyncId,
                    fieldId = "name",
                    entityGeneration = operation.entityGeneration,
                    versionToken = token,
                    sourceOperationId = operation.operationId,
                    valueJson = valueJson,
                    causalContextJson = operation.causalContextJson,
                    logicalClock = operation.logicalClock,
                    updatedAt = now,
                )
            )
        }
    }

    /**
     * 处理 Feed 订阅源的业务投影（创建、更新字段、删除）。
     *
     * 关键约束：若 Feed 依赖的分组在本地尚无映射，必须抛出 SyncApplyDeferredException 延迟应用。
     */
    private suspend fun applyFeed(operation: SyncOperationEntity) {
        val binding = database.syncRuntimeDao().findBindingBySpace(operation.syncSpaceId)
            ?: throw SyncApplyDeferredException("Missing local Space binding")

        val tombstone = database.syncInboxDao().findTombstone(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
        if (tombstone != null && operation.entityGeneration <= tombstone.entityGeneration) {
            if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
                aliasResolver.applyGlobalDelete(operation)
            }
            return
        }

        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            val mapping =
                aliasResolver.resolveMapping(
                    operation.syncSpaceId,
                    operation.entityType,
                    operation.entitySyncId,
                    operation.entityGeneration,
                )
            val feed = mapping?.let { database.feedDao().queryById(it.localId) }
            if (
                feed != null &&
                database.articleDao().countByFeedId(binding.localAccountId, feed.id) > 0
            ) {
                throw SyncApplyDeferredException(
                    "Feed delete is waiting for dependent Article deletes"
                )
            }
            aliasResolver.applyGlobalDelete(operation)
            mapping?.localId?.let { localFeedId ->
                articleFilterRepository.deleteByFeed(localFeedId)
                websiteParsePreferenceRepository.delete(localFeedId)
                rssHubSubscriptionRepository.replaceSyncSource(localFeedId, null)
            }
            return
        }

        if (operation.operationType == SyncMutationType.RELATION_SET.name) {
            applyRelation(operation)
            return
        }

        val payload = parsePayload(operation.payloadJson)
        val fieldMap = when (operation.operationType) {
            SyncMutationType.FIELD_SET.name -> {
                val field = payload["field"]?.jsonPrimitive?.content.orEmpty()
                require(field.isNotBlank()) { "FIELD_SET requires a field" }
                mapOf(field to (payload["value"] ?: JsonNull))
            }
            SyncMutationType.UPSERT.name -> {
                ((payload["fields"] as? JsonObject) ?: payload).toMap()
            }
            else -> throw SyncApplyDeferredException("Feed mutation unavailable: ${operation.operationType}")
        }

        val mapping = aliasResolver.resolveMapping(
            operation.syncSpaceId,
            operation.entityType,
            operation.entitySyncId,
            operation.entityGeneration,
        )
        val existingFeed = mapping?.let { database.feedDao().queryById(it.localId) }
        if (existingFeed != null) check(existingFeed.accountId == binding.localAccountId) { "Feed belongs to another account" }
        if (existingFeed == null && operation.operationType != SyncMutationType.UPSERT.name) {
            throw SyncApplyDeferredException("Missing feed row for update")
        }

        // 解析依赖的分组
        val groupSyncId = fieldMap["groupSyncId"]?.jsonPrimitive?.content
            ?: fieldMap["groupId"]?.jsonPrimitive?.content
        val groupGeneration =
            fieldMap["groupGeneration"]
                ?.takeIf { it != JsonNull }
                ?.jsonPrimitive
                ?.longOrNull
        if (groupGeneration != null && groupSyncId.isNullOrBlank()) {
            throw SyncApplyDeferredException("Feed groupGeneration requires groupSyncId")
        }
        var targetLocalGroupId =
            existingFeed?.groupId
                ?: database.groupDao().queryAll(binding.localAccountId).firstOrNull()?.id
                ?: throw SyncApplyDeferredException("No local group available to attach feed")

        val token = SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence)
        val now = System.currentTimeMillis()

        if (!groupSyncId.isNullOrBlank()) {
            if (groupGeneration != null) {
                val baselineGeneration =
                    existingFeed?.let { feed ->
                        database.syncIdentityMappingDao()
                            .findByLocalId(
                                operation.syncSpaceId,
                                SyncEntityType.GROUP.wireName,
                                feed.groupId,
                            )
                            ?.generation
                    } ?: groupGeneration
                resolveGenerationMetadataField(
                    operation = operation,
                    field = "groupGeneration",
                    incomingGeneration = groupGeneration,
                    baselineGeneration = baselineGeneration,
                )
            }
            val current = database.syncInboxDao().findFieldVersion(operation.syncSpaceId, operation.entityType,
                operation.entitySyncId, "groupSyncId")
            val winner = resolveRetainedField(operation, "groupSyncId", JsonPrimitive(groupSyncId).toString(),
                current, SyncGenesisMergePolicy.DETERMINISTIC)
            val winningGroupSyncId =
                json.parseToJsonElement(winner.valueJson).jsonPrimitive.content
            val winningGroupGeneration =
                pairedGenerationForWinner(
                    operation = operation,
                    generationField = "groupGeneration",
                    winnerToken = winner.token,
                )
            val winningLocalGroupId =
                resolveGroupParent(
                    syncSpaceId = operation.syncSpaceId,
                    groupSyncId = winningGroupSyncId,
                    groupGeneration = winningGroupGeneration,
                    label = "Feed ${operation.entitySyncId}",
                )
            if (winningLocalGroupId == null) {
                if (existingFeed == null) return
            } else {
                val winningGroup = database.groupDao().queryById(winningLocalGroupId)
                    ?: throw SyncApplyDeferredException("Missing winning group")
                check(winningGroup.accountId == binding.localAccountId) { "Winning group belongs to another account" }
                targetLocalGroupId = winningGroup.id
            }
        }

        // 裁决各字段胜出者
        val feedName = resolveStringField(operation, "name", fieldMap["name"]?.jsonPrimitive?.content ?: fieldMap["title"]?.jsonPrimitive?.content, existingFeed?.name, token)
        val feedUrl = resolveStringField(operation, "url", fieldMap["url"]?.jsonPrimitive?.content, existingFeed?.url, token)
        val icon = resolveStringField(operation, "icon", fieldMap["icon"]?.jsonPrimitive?.content, existingFeed?.icon, token)
        val incomingSourceType =
            fieldMap["sourceType"]?.jsonPrimitive?.content?.trim()?.lowercase()
        val sourceTypeWire =
            resolveStringField(
                operation,
                "sourceType",
                incomingSourceType,
                existingFeed?.sourceType?.name?.lowercase(),
                token,
            )
        val isNotification = resolveBooleanField(operation, "isNotification", fieldMap["isNotification"]?.jsonPrimitive?.booleanOrNull, existingFeed?.isNotification, token)
        val isFullContent = resolveBooleanField(operation, "isFullContent", fieldMap["isFullContent"]?.jsonPrimitive?.booleanOrNull, existingFeed?.isFullContent, token)
        val isBrowser = resolveBooleanField(operation, "isBrowser", fieldMap["isBrowser"]?.jsonPrimitive?.booleanOrNull, existingFeed?.isBrowser, token)

        val resolvedSourceType =
            runCatching { SourceType.valueOf(sourceTypeWire.uppercase()) }
                .getOrElse {
                    throw SyncApplyDeferredException(
                        "Unsupported Feed sourceType: $sourceTypeWire"
                    )
                }

        if (existingFeed == null) {
            val localId = mapping?.localId ?: "${binding.localAccountId}$${UUID.randomUUID()}"
            val newFeed = Feed(
                id = localId,
                name = feedName.ifBlank { "Feed" },
                icon = icon.takeIf { it.isNotBlank() },
                url = feedUrl.ifBlank { "about:blank" },
                groupId = targetLocalGroupId,
                accountId = binding.localAccountId,
                isNotification = isNotification,
                isFullContent = isFullContent,
                isBrowser = isBrowser,
                sourceType = resolvedSourceType,
            )
            database.feedDao().insertAll(listOf(newFeed))
            if (mapping == null) {
                database.syncIdentityMappingDao().insert(
                    SyncIdentityMappingEntity(
                        syncSpaceId = operation.syncSpaceId,
                        entityType = operation.entityType,
                        localId = localId,
                        syncId = operation.entitySyncId,
                        canonicalKey = SyncCanonicalIdentity.feedKey(resolvedSourceType, newFeed.url),
                        generation = operation.entityGeneration,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
        } else {
            val updatedFeed = existingFeed.copy(
                name = feedName.ifBlank { existingFeed.name },
                icon = if (icon.isNotBlank()) icon else existingFeed.icon,
                url = feedUrl.ifBlank { existingFeed.url },
                groupId = targetLocalGroupId,
                isNotification = isNotification,
                isFullContent = isFullContent,
                isBrowser = isBrowser,
                sourceType = resolvedSourceType,
            )
            database.feedDao().updateAll(listOf(updatedFeed))
            if (mapping != null) {
                val canonicalKey =
                    SyncCanonicalIdentity.feedKey(updatedFeed.sourceType, updatedFeed.url)
                if (mapping.canonicalKey != canonicalKey) {
                    database.syncIdentityMappingDao().update(
                        mapping.copy(
                            canonicalKey = canonicalKey,
                            updatedAt = now,
                        )
                    )
                }
            }
        }
    }

    /** Materialize Article metadata/state. New Article rows may arrive entirely through Operations. */
    private suspend fun applyArticle(operation: SyncOperationEntity) {
        val binding =
            database.syncRuntimeDao().findBindingBySpace(operation.syncSpaceId)
                ?: throw SyncApplyDeferredException("Missing local Space binding")
        val tombstone =
            database.syncInboxDao().findTombstone(
                operation.syncSpaceId,
                operation.entityType,
                operation.entitySyncId,
            )
        if (tombstone != null && operation.entityGeneration <= tombstone.entityGeneration) {
            if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
                aliasResolver.applyGlobalDelete(operation)
            }
            return
        }

        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            applyDelete(operation)
            return
        }
        if (operation.operationType == SyncMutationType.RELATION_SET.name) {
            applyRelation(operation)
            return
        }

        val payload = parsePayload(operation.payloadJson)
        val fields =
            when (operation.operationType) {
                SyncMutationType.FIELD_SET.name -> {
                    val field = payload["field"]?.jsonPrimitive?.content.orEmpty()
                    require(field.isNotBlank()) { "FIELD_SET requires a field" }
                    if (field == SYNC_ARTICLE_FULL_CONTENT_FIELD) {
                        applyArticleFullContent(operation, payload)
                        return
                    }
                    JsonObject(mapOf(field to (payload["value"] ?: JsonNull)))
                }
                SyncMutationType.UPSERT.name -> (payload["fields"] as? JsonObject) ?: payload
                else -> throw SyncApplyDeferredException(
                    "Unsupported Article mutation type: ${operation.operationType}"
                )
            }

        val mapping =
            aliasResolver.resolveMapping(
                operation.syncSpaceId,
                SyncEntityType.ARTICLE.wireName,
                operation.entitySyncId,
                operation.entityGeneration,
            )
        var articleWithFeed = mapping?.let { database.articleDao().queryById(it.localId) }
        var createdArticle = false
        if (articleWithFeed != null) {
            check(articleWithFeed.article.accountId == binding.localAccountId) {
                "Article belongs to another account"
            }
        }
        val incomingFeedGeneration =
            fields["feedGeneration"]
                ?.takeIf { it != JsonNull }
                ?.jsonPrimitive
                ?.longOrNull
        if (incomingFeedGeneration != null) {
            if (fields["feedSyncId"]?.jsonPrimitive?.content.isNullOrBlank()) {
                throw SyncApplyDeferredException("Article feedGeneration requires feedSyncId")
            }
            val baselineGeneration =
                articleWithFeed?.let { current ->
                    database.syncIdentityMappingDao()
                        .findByLocalId(
                            operation.syncSpaceId,
                            SyncEntityType.FEED.wireName,
                            current.article.feedId,
                        )
                        ?.generation
                } ?: incomingFeedGeneration
            resolveGenerationMetadataField(
                operation = operation,
                field = "feedGeneration",
                incomingGeneration = incomingFeedGeneration,
                baselineGeneration = baselineGeneration,
            )
        }

        if (articleWithFeed == null) {
            if (operation.operationType != SyncMutationType.UPSERT.name) {
                throw SyncApplyDeferredException("Missing article row for update")
            }
            val feedSyncId =
                fields["feedSyncId"]?.jsonPrimitive?.content
                    ?: throw SyncApplyDeferredException("New Article UPSERT requires feedSyncId")
            val localFeedId =
                resolveFeedScopedConfigParent(
                    syncSpaceId = operation.syncSpaceId,
                    feedSyncId = feedSyncId,
                    feedGeneration = incomingFeedGeneration,
                    label = "Article ${operation.entitySyncId}",
                ) ?: return
            val feedMapping =
                database.syncIdentityMappingDao()
                    .findBySyncId(
                        operation.syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        feedSyncId,
                    )
                    ?: throw SyncApplyDeferredException(
                        "Missing dependent feed mapping for Article ${operation.entitySyncId}"
                    )
            val feed =
                database.feedDao().queryById(localFeedId)
                    ?: throw SyncApplyDeferredException(
                        "Missing dependent feed row $localFeedId"
                    )
            check(feed.accountId == binding.localAccountId) {
                "Dependent feed belongs to another account"
            }
            fun nullableText(field: String): String? =
                fields[field]?.let { value ->
                    if (value is JsonNull) null else value.jsonPrimitive.content
                }
            fun text(field: String, fallback: String = ""): String =
                nullableText(field) ?: fallback
            val now = System.currentTimeMillis()
            val localId = mapping?.localId ?: UUID.randomUUID().toString()
            val article =
                Article(
                    id = localId,
                    date =
                        Date(
                            fields["publishedAt"]?.jsonPrimitive?.content?.toLongOrNull()
                                ?: now
                        ),
                    title = text("title", "Article"),
                    author = nullableText("author"),
                    rawDescription = text("contentHtml"),
                    shortDescription = text("description"),
                    img = nullableText("imageUrl"),
                    link = text("url"),
                    feedId = feed.id,
                    accountId = binding.localAccountId,
                    isUnread = fields["isUnread"]?.jsonPrimitive?.booleanOrNull ?: true,
                    isStarred = fields["isStarred"]?.jsonPrimitive?.booleanOrNull ?: false,
                    isReadLater = fields["isReadLater"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            database.articleDao().insertList(listOf(article))
            if (mapping == null) {
                database.syncIdentityMappingDao().insert(
                    SyncIdentityMappingEntity(
                        syncSpaceId = operation.syncSpaceId,
                        entityType = SyncEntityType.ARTICLE.wireName,
                        localId = localId,
                        syncId = operation.entitySyncId,
                        canonicalKey =
                            SyncCanonicalIdentity.articleKey(
                                feedCanonicalKey = feedMapping.canonicalKey,
                                articleLink = article.link,
                            ),
                        generation = operation.entityGeneration,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
            articleWithFeed =
                database.articleDao().queryById(localId)
                    ?: throw SyncApplyDeferredException("Unable to materialize Article row")
            createdArticle = true
        }

        fields.forEach { (field, value) ->
            if (field != "field" && field != "value" && field != "feedGeneration") {
                applyArticleField(
                    operation,
                    field,
                    value,
                    captureBaseline = !createdArticle,
                )
            }
        }
        if (mapping != null) {
            database.articleDao().queryById(mapping.localId)?.let { current ->
                val feedMapping =
                    database.syncIdentityMappingDao().findByLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        current.feed.id,
                    )
                val canonicalKey =
                    SyncCanonicalIdentity.articleKey(
                        feedCanonicalKey = feedMapping?.canonicalKey,
                        articleLink = current.article.link,
                    )
                if (mapping.canonicalKey != canonicalKey) {
                    database.syncIdentityMappingDao().update(
                        mapping.copy(
                            canonicalKey = canonicalKey,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
    }

    private suspend fun applyArticleFullContent(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        val hash =
            payload["value"]?.jsonPrimitive?.content
                ?: throw SyncApplyDeferredException("Article full-content operation has no Blob hash")
        val reference =
            SyncBlobPayloadCodec.references(operation.payloadJson)
                .singleOrNull {
                    it.field == SYNC_ARTICLE_FULL_CONTENT_FIELD &&
                        it.referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND
                }
                ?: throw SyncApplyDeferredException("Article full-content operation has no Blob reference")
        require(reference.manifest.hash == hash) {
            "Article full-content field hash does not match Blob manifest"
        }

        val binding =
            database.syncRuntimeDao().findBindingBySpace(operation.syncSpaceId)
                ?: throw SyncApplyDeferredException("Missing local Space binding")
        val mapping =
            aliasResolver.resolveMapping(
                operation.syncSpaceId,
                SyncEntityType.ARTICLE.wireName,
                operation.entitySyncId,
                operation.entityGeneration,
            ) ?: throw SyncApplyDeferredException("Missing article identity mapping")
        val article =
            database.articleDao().queryById(mapping.localId)?.article
                ?: throw SyncApplyDeferredException("Missing article row")
        check(article.accountId == binding.localAccountId) { "Article belongs to another account" }

        val bytes = localBlobStore.readVerified(hash)
        blobState.registerManifest(
            reference.manifest,
            if (bytes == null) SyncBlobAvailabilityState.BLOB_MISSING else SyncBlobAvailabilityState.READY,
        )
        blobState.replaceOwnerReference(
            syncSpaceId = operation.syncSpaceId,
            lane = operation.replicationLaneId,
            ownerEntityType = operation.entityType,
            ownerEntitySyncId = operation.entitySyncId,
            ownerEntityGeneration = operation.entityGeneration,
            referenceKind = reference.referenceKind,
            hash = hash,
        )

        val token =
            SyncVersionToken.operation(
                operation.actorIncarnationId,
                operation.replicationLaneId,
                operation.sequence,
            )
        val current =
            database.syncInboxDao().findFieldVersion(
                operation.syncSpaceId,
                operation.entityType,
                operation.entitySyncId,
                SYNC_ARTICLE_FULL_CONTENT_FIELD,
            )
        val winner =
            resolveRetainedField(
                operation,
                SYNC_ARTICLE_FULL_CONTENT_FIELD,
                JsonPrimitive(hash).toString(),
                current,
                SyncGenesisMergePolicy.DETERMINISTIC,
            )
        if (winner.token != token) return

        if (
            localEviction.isEvicted(
                operation.syncSpaceId,
                operation.entityType,
                operation.entitySyncId,
                operation.entityGeneration,
                SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND,
            )
        ) {
            return
        }
        if (bytes == null || bytes.size.toLong() != reference.manifest.totalBytes) {
            blobState.markMissing(hash)
            throw SyncApplyDeferredException("BLOB_MISSING:$hash")
        }
        blobState.markReadyVerified(hash, bytes.size.toLong())
        if (
            !readerCacheHelper.writeContentToCacheFromSync(
                binding.localAccountId,
                bytes.toString(Charsets.UTF_8),
                mapping.localId,
            )
        ) {
            throw SyncApplyDeferredException("Unable to materialize synced article full content")
        }
    }

    internal suspend fun materializeSnapshotArticleFullContent(
        syncSpaceId: String,
        entitySyncId: String,
        generation: Long,
        localAccountId: Int,
        localArticleId: String,
        hash: String,
    ) {
        if (
            localEviction.isEvicted(
                syncSpaceId,
                SyncEntityType.ARTICLE.wireName,
                entitySyncId,
                generation,
                SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND,
            )
        ) {
            return
        }
        val manifest = database.syncBlobDao().findManifest(hash) ?: return
        val bytes = localBlobStore.readVerified(hash)
        if (bytes == null || bytes.size.toLong() != manifest.totalBytes) {
            blobState.markMissing(hash)
            return
        }
        blobState.markReadyVerified(hash, bytes.size.toLong())
        readerCacheHelper.writeContentToCacheFromSync(
            localAccountId,
            bytes.toString(Charsets.UTF_8),
            localArticleId,
        )
    }

    /**
     * 处理文章业务字段变更。
     */
    private suspend fun applyArticleField(
        operation: SyncOperationEntity,
        field: String,
        value: JsonElement,
        captureBaseline: Boolean = true,
    ) {
        if (
            field !in
                setOf(
                    "feedSyncId",
                    "title",
                    "url",
                    "author",
                    "publishedAt",
                    "description",
                    "contentHtml",
                    "imageUrl",
                    "isUnread",
                    "isStarred",
                    "isReadLater",
                )
        ) {
            throw SyncApplyDeferredException("Article field projection unavailable: $field")
        }
        val binding = database.syncRuntimeDao().findBindingBySpace(operation.syncSpaceId)
            ?: throw SyncApplyDeferredException("Missing local Space binding")
        val mapping = aliasResolver.resolveMapping(
            operation.syncSpaceId,
            SyncEntityType.ARTICLE.wireName,
            operation.entitySyncId,
            operation.entityGeneration,
        ) ?: throw SyncApplyDeferredException("Missing article identity mapping")
        val articleWithFeed = database.articleDao().queryById(mapping.localId)
            ?: throw SyncApplyDeferredException("Missing article row")
        check(articleWithFeed.article.accountId == binding.localAccountId) { "Article belongs to another account" }

        val valueJson = SyncOperationCanonicalizer.canonicalJson(value.toString())
        val token = SyncVersionToken.operation(
            operation.actorIncarnationId,
            operation.replicationLaneId,
            operation.sequence,
        )
        val current = database.syncInboxDao().findFieldVersion(
            operation.syncSpaceId,
            operation.entityType,
            operation.entitySyncId,
            field,
        )
        if (
            captureBaseline &&
            (current == null || shouldRefreshProvisionalBaseline(operation, current))
        ) {
            val baselineJson = when (field) {
                "feedSyncId" -> {
                    val feedMapping =
                        database.syncIdentityMappingDao().findByLocalId(
                            operation.syncSpaceId,
                            SyncEntityType.FEED.wireName,
                            articleWithFeed.article.feedId,
                        ) ?: throw SyncApplyDeferredException(
                            "Article feed has no Sync mapping for rollback baseline"
                        )
                    JsonPrimitive(feedMapping.syncId).toString()
                }
                "title" -> JsonPrimitive(articleWithFeed.article.title).toString()
                "url" -> JsonPrimitive(articleWithFeed.article.link).toString()
                "author" ->
                    articleWithFeed.article.author?.let { JsonPrimitive(it).toString() }
                        ?: JsonNull.toString()
                "publishedAt" -> JsonPrimitive(articleWithFeed.article.date.time).toString()
                "description" -> JsonPrimitive(articleWithFeed.article.shortDescription).toString()
                "contentHtml" -> JsonPrimitive(articleWithFeed.article.rawDescription).toString()
                "imageUrl" ->
                    articleWithFeed.article.img?.let { JsonPrimitive(it).toString() }
                        ?: JsonNull.toString()
                "isUnread" -> articleWithFeed.article.isUnread.toString()
                "isStarred" -> articleWithFeed.article.isStarred.toString()
                "isReadLater" -> articleWithFeed.article.isReadLater.toString()
                else -> null
            }
            if (baselineJson != null) {
                captureRollbackBaseline(
                    operation,
                    field,
                    baselineJson,
                    replace = current != null,
                )
            }
        }
        val winner = resolveRetainedField(operation, field, valueJson, current,
            when (field) {
                "isUnread" -> SyncGenesisMergePolicy.READ_WINS
                "isStarred" -> SyncGenesisMergePolicy.STARRED_WINS
                else -> SyncGenesisMergePolicy.DETERMINISTIC
            },
        )
        if (winner.token != token) {
            updateProjectedTable(operation.syncSpaceId, operation.entityType, operation.entitySyncId, field, winner.valueJson)
            return
        }

        database.syncInboxDao().upsertFieldVersion(
            SyncFieldVersionEntity(
                syncSpaceId = operation.syncSpaceId,
                entityType = operation.entityType,
                entitySyncId = operation.entitySyncId,
                fieldId = field,
                entityGeneration = operation.entityGeneration,
                versionToken = token,
                sourceOperationId = operation.operationId,
                valueJson = valueJson,
                causalContextJson = operation.causalContextJson,
                logicalClock = operation.logicalClock,
                updatedAt = System.currentTimeMillis(),
            )
        )
        updateProjectedTable(
            operation.syncSpaceId,
            operation.entityType,
            operation.entitySyncId,
            field,
            winner.valueJson,
        )
    }

    /**
     * 处理关系变更（如 Feed 隶属于指定 Group）。
     */
    private suspend fun applyRelation(operation: SyncOperationEntity) {
        val binding = database.syncRuntimeDao().findBindingBySpace(operation.syncSpaceId)
            ?: throw SyncApplyDeferredException("Missing local Space binding")

        val payload = parsePayload(operation.payloadJson)
        val feedSyncId = payload["feedSyncId"]?.jsonPrimitive?.content
            ?: (if (operation.entityType == SyncEntityType.FEED.wireName) operation.entitySyncId else null)
            ?: payload["fromSyncId"]?.jsonPrimitive?.content
            ?: throw SyncApplyDeferredException("Relation requires feedSyncId")

        val groupSyncId = payload["groupSyncId"]?.jsonPrimitive?.content
            ?: payload["targetGroupId"]?.jsonPrimitive?.content
            ?: payload["toSyncId"]?.jsonPrimitive?.content
            ?: throw SyncApplyDeferredException("Relation requires groupSyncId")
        val feedGeneration =
            payload["feedGeneration"]
                ?.takeIf { it != JsonNull }
                ?.jsonPrimitive
                ?.longOrNull
                ?: if (operation.entityType == SyncEntityType.FEED.wireName) {
                    operation.entityGeneration
                } else {
                    null
                }
        val groupGeneration =
            (payload["groupGeneration"] ?: payload["targetGroupGeneration"])
                ?.takeIf { it != JsonNull }
                ?.jsonPrimitive
                ?.longOrNull

        val localFeedId =
            resolveFeedScopedConfigParent(
                syncSpaceId = operation.syncSpaceId,
                feedSyncId = feedSyncId,
                feedGeneration = feedGeneration,
                label = "Relation",
            ) ?: return
        val feed = database.feedDao().queryById(localFeedId)
            ?: throw SyncApplyDeferredException("Missing local feed row for relation")
        check(feed.accountId == binding.localAccountId) { "Relation Feed belongs to another account" }

        val fieldOperation = operation.copy(entityType = SyncEntityType.FEED.wireName, entitySyncId = feedSyncId)
        val currentGroupMapping =
            database.syncIdentityMappingDao().findByLocalId(
                operation.syncSpaceId,
                SyncEntityType.GROUP.wireName,
                feed.groupId,
            )
        if (groupGeneration != null) {
            resolveGenerationMetadataField(
                operation = fieldOperation,
                field = "groupGeneration",
                incomingGeneration = groupGeneration,
                baselineGeneration = currentGroupMapping?.generation ?: groupGeneration,
            )
        }
        val current = database.syncInboxDao().findFieldVersion(operation.syncSpaceId, SyncEntityType.FEED.wireName, feedSyncId, "groupSyncId")
        if (current == null || shouldRefreshProvisionalBaseline(fieldOperation, current)) {
            if (currentGroupMapping != null) {
                captureRollbackBaseline(
                    fieldOperation,
                    "groupSyncId",
                    JsonPrimitive(currentGroupMapping.syncId).toString(),
                    replace = current != null,
                )
            }
        }
        val winner = resolveRetainedField(fieldOperation, "groupSyncId", JsonPrimitive(groupSyncId).toString(), current,
            SyncGenesisMergePolicy.DETERMINISTIC)
        val winningGroupSyncId = json.parseToJsonElement(winner.valueJson).jsonPrimitive.content
        val winningGroupGeneration =
            pairedGenerationForWinner(
                operation = fieldOperation,
                generationField = "groupGeneration",
                winnerToken = winner.token,
            )
        val localGroupId =
            resolveGroupParent(
                syncSpaceId = operation.syncSpaceId,
                groupSyncId = winningGroupSyncId,
                groupGeneration = winningGroupGeneration,
                label = "Relation",
            ) ?: return
        val winningGroup = database.groupDao().queryById(localGroupId)
            ?: throw SyncApplyDeferredException("Missing winning relation group")
        check(winningGroup.accountId == binding.localAccountId) { "Winning relation group belongs to another account" }
        if (feed.groupId != winningGroup.id) {
            database.feedDao().updateAll(listOf(feed.copy(groupId = winningGroup.id)))
        }
    }

    /**
     * 处理删除操作（写入 Tombstone 并级联清理本地行）。
     */
    private suspend fun applyDelete(operation: SyncOperationEntity) {
        aliasResolver.applyGlobalDelete(operation)
    }

    private suspend fun resolveStringField(
        operation: SyncOperationEntity,
        field: String,
        incomingValue: String?,
        currentValue: String?,
        token: String,
    ): String {
        if (incomingValue == null) return currentValue.orEmpty()
        val current = database.syncInboxDao().findFieldVersion(operation.syncSpaceId, operation.entityType, operation.entitySyncId, field)
        val valueJson = SyncOperationCanonicalizer.canonicalJson(JsonPrimitive(incomingValue).toString())
        if (
            currentValue != null &&
            (current == null || shouldRefreshProvisionalBaseline(operation, current))
        ) {
            captureRollbackBaseline(
                operation,
                field,
                JsonPrimitive(currentValue).toString(),
                replace = current != null,
            )
        }
        val winner = resolveRetainedField(operation, field, valueJson, current, SyncGenesisMergePolicy.DETERMINISTIC)
        if (winner.token == token) {
            database.syncInboxDao().upsertFieldVersion(
                SyncFieldVersionEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = operation.entityType,
                    entitySyncId = operation.entitySyncId,
                    fieldId = field,
                    entityGeneration = operation.entityGeneration,
                    versionToken = token,
                    sourceOperationId = operation.operationId,
                    valueJson = valueJson,
                    causalContextJson = operation.causalContextJson,
                    logicalClock = operation.logicalClock,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return incomingValue
        }
        return json.parseToJsonElement(winner.valueJson).jsonPrimitive.content
    }

    private suspend fun resolveBooleanField(
        operation: SyncOperationEntity,
        field: String,
        incomingValue: Boolean?,
        currentValue: Boolean?,
        token: String,
        policy: SyncGenesisMergePolicy = SyncGenesisMergePolicy.DETERMINISTIC,
    ): Boolean {
        if (incomingValue == null) return currentValue ?: false
        val current = database.syncInboxDao().findFieldVersion(operation.syncSpaceId, operation.entityType, operation.entitySyncId, field)
        val valueJson = incomingValue.toString()
        if (
            currentValue != null &&
            (current == null || shouldRefreshProvisionalBaseline(operation, current))
        ) {
            captureRollbackBaseline(
                operation,
                field,
                currentValue.toString(),
                replace = current != null,
            )
        }
        val winner = resolveRetainedField(operation, field, valueJson, current, policy)
        if (winner.token == token) {
            database.syncInboxDao().upsertFieldVersion(
                SyncFieldVersionEntity(
                    syncSpaceId = operation.syncSpaceId,
                    entityType = operation.entityType,
                    entitySyncId = operation.entitySyncId,
                    fieldId = field,
                    entityGeneration = operation.entityGeneration,
                    versionToken = token,
                    sourceOperationId = operation.operationId,
                    valueJson = valueJson,
                    causalContextJson = operation.causalContextJson,
                    logicalClock = operation.logicalClock,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return incomingValue
        }
        return json.parseToJsonElement(winner.valueJson).jsonPrimitive.booleanOrNull ?: false
    }

    private fun parsePayload(value: String): JsonObject =
        try {
            json.parseToJsonElement(value).jsonObject
        } catch (error: Throwable) {
            throw IllegalArgumentException("Malformed Sync payload JSON", error)
        }

    private suspend fun captureRollbackBaseline(
        operation: SyncOperationEntity,
        field: String,
        valueJson: String,
        replace: Boolean = false,
    ) {
        val predecessor =
            if (replace) {
                database.syncInboxDao().findFieldVersion(
                    operation.syncSpaceId,
                    operation.entityType,
                    operation.entitySyncId,
                    field,
                )
            } else {
                null
            }
        val storedValueJson =
            if (predecessor != null && predecessor.sourceOperationId == null) {
                JsonObject(
                    mapOf(
                        "__syncRollbackBaselineV2" to JsonPrimitive(true),
                        "valueJson" to JsonPrimitive(valueJson),
                        "versionToken" to JsonPrimitive(predecessor.versionToken),
                    )
                ).toString()
            } else {
                valueJson
            }
        val baseline =
            SyncFieldRollbackBaselineEntity(
                syncSpaceId = operation.syncSpaceId,
                entityType = operation.entityType,
                entitySyncId = operation.entitySyncId,
                entityGeneration = operation.entityGeneration,
                fieldId = field,
                valueJson = storedValueJson,
            )
        if (replace) {
            database.syncInboxDao().upsertRollbackBaseline(baseline)
        } else {
            database.syncInboxDao().insertRollbackBaselineIgnore(baseline)
        }
    }

    private suspend fun shouldRefreshProvisionalBaseline(
        operation: SyncOperationEntity,
        current: SyncFieldVersionEntity,
    ): Boolean =
        current.sourceOperationId == null &&
            database.syncInboxDao().find(operation.operationId)?.authorizationState ==
                "PROVISIONAL_AUTHORIZED"

    /**
     * 当 sourceOperation 因授权撤销而被置为 REJECTED 时，回滚受污染的业务字段并重新决胜。
     *
     * @param syncSpaceId 同步空间 ID
     * @param entityType 实体类型 ('article', 'feed', 'group')
     * @param entitySyncId 实体跨端 Sync ID
     * @param field 字段名 (如 'isStarred', 'isUnread', 'name')
     * @param revokedOperationId 被撤销的操作 ID
     */
    suspend fun rollbackField(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        field: String,
        revokedOperationId: String,
    ) {
        val current = database.syncInboxDao().findFieldVersion(syncSpaceId, entityType, entitySyncId, field)
        if (current == null || current.sourceOperationId != revokedOperationId) return

        val appliedInbox = database.syncInboxDao().listAppliedInbox(syncSpaceId)
            .filter { it.operationId != revokedOperationId }

        val candidates = mutableListOf<SyncFieldCandidate>()

        for (inbox in appliedInbox) {
            val op = database.syncOperationDao().findById(inbox.operationId) ?: continue
            if (op.entityType != entityType || op.entitySyncId != entitySyncId ||
                op.entityGeneration != current.entityGeneration) continue

            val payload = runCatching { json.parseToJsonElement(op.payloadJson).jsonObject }.getOrNull() ?: continue
            var valueJson: String? = null
            if (op.operationType == SyncMutationType.FIELD_SET.name && payload["field"]?.jsonPrimitive?.content == field) {
                valueJson = payload["value"]?.toString()
            } else if (op.operationType == SyncMutationType.RELATION_SET.name) {
                valueJson =
                    when (field) {
                        "groupSyncId" ->
                            (payload["groupSyncId"] ?: payload["targetGroupId"] ?: payload["toSyncId"])
                                ?.toString()
                        "groupGeneration" ->
                            (payload["groupGeneration"] ?: payload["targetGroupGeneration"])
                                ?.toString()
                        else -> null
                    }
            } else if (op.operationType == SyncMutationType.UPSERT.name) {
                val fields = (payload["fields"] as? JsonObject) ?: payload
                valueJson = fields[field]?.toString()
            }

            if (valueJson != null) {
                val token = SyncVersionToken.operation(op.actorIncarnationId, op.replicationLaneId, op.sequence)
                candidates.add(
                    SyncFieldCandidate(
                        fieldId = field,
                        valueJson = valueJson,
                        token = token,
                        source = SyncVersionSource.OPERATION,
                        causalContextJson = op.causalContextJson,
                        logicalClock = op.logicalClock,
                    )
                )
            }
        }

        val policy = when (field) {
            "isStarred" -> SyncGenesisMergePolicy.STARRED_WINS
            "isUnread" -> SyncGenesisMergePolicy.READ_WINS
            else -> SyncGenesisMergePolicy.DETERMINISTIC
        }

        if (candidates.isNotEmpty()) {
            val winner = SyncVersionResolver.resolve(candidates, policy = policy)
            val matchedOp = appliedInbox.firstNotNullOfOrNull { inbox ->
                database.syncOperationDao().findById(inbox.operationId)?.takeIf {
                    SyncVersionToken.operation(it.actorIncarnationId, it.replicationLaneId, it.sequence) == winner.token
                }
            }
            updateProjectedTable(
                syncSpaceId,
                entityType,
                entitySyncId,
                field,
                winner.valueJson,
                winner.token,
            )
            database.syncInboxDao().upsertFieldVersion(
                SyncFieldVersionEntity(
                    syncSpaceId = syncSpaceId,
                    entityType = entityType,
                    entitySyncId = entitySyncId,
                    fieldId = field,
                    entityGeneration = current.entityGeneration,
                    versionToken = winner.token,
                    sourceOperationId = matchedOp?.operationId,
                    valueJson = winner.valueJson,
                    causalContextJson = winner.causalContextJson,
                    logicalClock = winner.logicalClock,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        } else {
            val baseline = database.syncInboxDao().findRollbackBaseline(
                syncSpaceId,
                entityType,
                entitySyncId,
                current.entityGeneration,
                field,
            ) ?: throw SyncRebaseUnsafeException("REBASE_UNSAFE: historical field baseline is unavailable")
            val restored = decodeRollbackBaseline(baseline.valueJson)
            val restoredVersionToken = restored.second
            updateProjectedTable(syncSpaceId, entityType, entitySyncId, field, restored.first)
            if (restoredVersionToken != null) {
                database.syncInboxDao().upsertFieldVersion(
                    SyncFieldVersionEntity(
                        syncSpaceId = syncSpaceId,
                        entityType = entityType,
                        entitySyncId = entitySyncId,
                        fieldId = field,
                        entityGeneration = current.entityGeneration,
                        versionToken = restoredVersionToken,
                        sourceOperationId = null,
                        valueJson = restored.first,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } else {
                database.syncInboxDao().deleteFieldVersion(syncSpaceId, entityType, entitySyncId, field)
            }
        }
    }

    private fun decodeRollbackBaseline(raw: String): Pair<String, String?> {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (obj?.get("__syncRollbackBaselineV2")?.jsonPrimitive?.booleanOrNull != true) {
            return raw to null
        }
        val valueJson =
            obj["valueJson"]?.jsonPrimitive?.content
                ?: throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: rollback baseline value is unavailable"
                )
        val versionToken =
            obj["versionToken"]?.jsonPrimitive?.content
                ?: throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: rollback baseline VersionToken is unavailable"
                )
        SyncVersionToken.source(versionToken)
        return valueJson to versionToken
    }

    /** Keep losing concurrent candidates available: a later causal successor can change the winner. */
    private suspend fun resolveRetainedField(
        operation: SyncOperationEntity,
        field: String,
        valueJson: String,
        current: SyncFieldVersionEntity?,
        policy: SyncGenesisMergePolicy,
    ): SyncFieldCandidate {
        val candidates = linkedMapOf<String, SyncFieldCandidate>()
        database.syncInboxDao().listFieldCandidates(operation.syncSpaceId).filter {
            it.entityType == operation.entityType && it.entitySyncId == operation.entitySyncId &&
                it.entityGeneration == operation.entityGeneration && it.fieldId == field
        }.forEach { candidates[it.versionToken] = fieldCandidate(it) }
        current?.takeIf { it.entityGeneration == operation.entityGeneration }?.let {
            candidates[it.versionToken] = fieldCandidate(it)
        }
        fun addPayload(type: String, payloadJson: String, token: String, context: String, clock: Long) {
            val payload = parsePayload(payloadJson)
            val value = when (type) {
                SyncMutationType.FIELD_SET.name -> if (payload["field"]?.jsonPrimitive?.content == field) payload["value"] else null
                SyncMutationType.UPSERT.name -> ((payload["fields"] as? JsonObject) ?: payload)[field]
                SyncMutationType.RELATION_SET.name ->
                    when (field) {
                        "groupSyncId" ->
                            payload["groupSyncId"] ?: payload["targetGroupId"] ?: payload["toSyncId"]
                        "groupGeneration" ->
                            payload["groupGeneration"] ?: payload["targetGroupGeneration"]
                        else -> null
                    }
                else -> null
            } ?: return
            candidates[token] = SyncFieldCandidate(field, SyncOperationCanonicalizer.canonicalJson(value.toString()),
                token, SyncVersionSource.OPERATION, causalContextJson = context, logicalClock = clock)
        }
        database.syncOperationDao().listAppliedEntityOperations(operation.syncSpaceId, operation.entityType,
            operation.entitySyncId, operation.entityGeneration).forEach { prior ->
            addPayload(prior.operationType, prior.payloadJson,
                SyncVersionToken.operation(prior.actorIncarnationId, prior.replicationLaneId, prior.sequence),
                prior.causalContextJson, prior.logicalClock)
        }
        database.syncOperationDao().listPendingEntityOutbox(operation.syncSpaceId, operation.entityType,
            operation.entitySyncId, operation.entityGeneration).forEach { pending ->
            addPayload(pending.mutationType, pending.payloadJson,
                SyncVersionToken.operation(pending.actorIncarnationId, pending.replicationLaneId, pending.sequence),
                pending.causalContextJson, pending.sequence)
        }
        val incoming = SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence)
        candidates[incoming] = SyncFieldCandidate(field, valueJson, incoming, SyncVersionSource.OPERATION,
            causalContextJson = operation.causalContextJson, logicalClock = operation.logicalClock)
        candidates.values.forEach { candidate ->
            val dot = SyncVersionToken.parseOperationDot(candidate.token)
            database.syncInboxDao().upsertFieldCandidate(SyncFieldCandidateEntity(SyncFieldVersionEntity(
                operation.syncSpaceId, operation.entityType, operation.entitySyncId, field,
                operation.entityGeneration, candidate.token,
                dot?.let { SyncOperationCanonicalizer.operationId(operation.syncSpaceId, it.actorIncarnationId, it.replicationLaneId, it.sequence) },
                candidate.valueJson, System.currentTimeMillis(), candidate.causalContextJson, candidate.logicalClock,
            )))
        }
        val winner = SyncVersionResolver.resolve(candidates.values.toList(), policy)
        val dot = SyncVersionToken.parseOperationDot(winner.token)
        database.syncInboxDao().upsertFieldVersion(
            SyncFieldVersionEntity(
                syncSpaceId = operation.syncSpaceId,
                entityType = operation.entityType,
                entitySyncId = operation.entitySyncId,
                fieldId = field,
                entityGeneration = operation.entityGeneration,
                versionToken = winner.token,
                sourceOperationId =
                    dot?.let {
                        SyncOperationCanonicalizer.operationId(
                            operation.syncSpaceId,
                            it.actorIncarnationId,
                            it.replicationLaneId,
                            it.sequence,
                        )
                    },
                valueJson = winner.valueJson,
                updatedAt = System.currentTimeMillis(),
                causalContextJson = winner.causalContextJson,
                logicalClock = winner.logicalClock,
            )
        )
        return winner
    }

    private suspend fun fieldCandidate(value: SyncFieldVersionEntity): SyncFieldCandidate {
        val source = value.sourceOperationId?.let { database.syncOperationDao().findById(it) }
        val dot = SyncVersionToken.parseOperationDot(value.versionToken)
        val pending = if (source == null && dot != null) database.syncOperationDao()
            .findLocalOutboxByDot(value.syncSpaceId, dot.actorIncarnationId, dot.replicationLaneId, dot.sequence) else null
        return SyncFieldCandidate(
            value.fieldId,
            value.valueJson,
            value.versionToken,
            SyncVersionToken.source(value.versionToken),
            causalContextJson =
                source?.causalContextJson ?: pending?.causalContextJson ?: value.causalContextJson,
            logicalClock = source?.logicalClock ?: pending?.sequence ?: value.logicalClock ?: 0L,
        )
    }

    private suspend fun updateProjectedTable(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        field: String,
        valueJson: String,
        projectedWinnerToken: String? = null,
    ) {
        val mapping = database.syncIdentityMappingDao().findBySyncId(syncSpaceId, entityType, entitySyncId) ?: return
        val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId) ?: return

        when (entityType) {
            SyncEntityType.ARTICLE.wireName -> {
                if (field == "feedGeneration") return
                val articleWithFeed = database.articleDao().queryById(mapping.localId) ?: return
                val article = articleWithFeed.article
                if (article.accountId != binding.localAccountId) return
                val element = runCatching { json.parseToJsonElement(valueJson) }.getOrNull()
                    ?: throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: malformed Article rollback field $field"
                    )
                val primitive = element as? JsonPrimitive
                val updated =
                    when (field) {
                        "feedSyncId" -> {
                            val feedSyncId =
                                primitive?.content
                                    ?: throw SyncRebaseUnsafeException(
                                        "REBASE_UNSAFE: invalid Article feedSyncId"
                                    )
                            val feedGeneration =
                                projectedWinnerToken?.let {
                                    pairedGenerationForProjectedWinner(
                                        syncSpaceId,
                                        entityType,
                                        entitySyncId,
                                        mapping.generation,
                                        "feedGeneration",
                                        it,
                                    )
                                } ?: pairedGenerationForProjectedField(
                                    syncSpaceId = syncSpaceId,
                                    entityType = entityType,
                                    entitySyncId = entitySyncId,
                                    entityGeneration = mapping.generation,
                                    idField = "feedSyncId",
                                    generationField = "feedGeneration",
                                )
                            val localFeedId =
                                resolveFeedScopedConfigParent(
                                    syncSpaceId = syncSpaceId,
                                    feedSyncId = feedSyncId,
                                    feedGeneration = feedGeneration,
                                    label = "Article $entitySyncId",
                                ) ?: return
                            val feed =
                                database.feedDao().queryById(localFeedId)
                                    ?: throw SyncApplyDeferredException(
                                        "Missing winning Article feed row"
                                    )
                            check(feed.accountId == binding.localAccountId) {
                                "Winning Article feed belongs to another account"
                            }
                            article.copy(feedId = feed.id)
                        }
                        "title" -> article.copy(title = primitive?.content ?: article.title)
                        "url" -> article.copy(link = primitive?.content ?: article.link)
                        "author" ->
                            article.copy(
                                author = if (element is JsonNull) null else primitive?.content
                            )
                        "publishedAt" ->
                            article.copy(
                                date =
                                    Date(
                                        primitive?.content?.toLongOrNull()
                                            ?: article.date.time
                                    )
                            )
                        "description" ->
                            article.copy(
                                shortDescription =
                                    primitive?.content ?: article.shortDescription
                            )
                        "contentHtml" ->
                            article.copy(
                                rawDescription = primitive?.content ?: article.rawDescription
                            )
                        "imageUrl" ->
                            article.copy(
                                img = if (element is JsonNull) null else primitive?.content
                            )
                        "isStarred" ->
                            article.copy(
                                isStarred = primitive?.booleanOrNull ?: article.isStarred
                            )
                        "isUnread" ->
                            article.copy(
                                isUnread = primitive?.booleanOrNull ?: article.isUnread
                            )
                        "isReadLater" ->
                            article.copy(
                                isReadLater = primitive?.booleanOrNull ?: article.isReadLater
                            )
                        else ->
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: unsupported Article rollback field $field"
                            )
                    }
                database.articleDao().update(updated)
                val feedMapping =
                    database.syncIdentityMappingDao().findByLocalId(
                        syncSpaceId,
                        SyncEntityType.FEED.wireName,
                        updated.feedId,
                    )
                val canonicalKey =
                    SyncCanonicalIdentity.articleKey(feedMapping?.canonicalKey, updated.link)
                if (mapping.canonicalKey != canonicalKey) {
                    database.syncIdentityMappingDao().update(
                        mapping.copy(
                            canonicalKey = canonicalKey,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            SyncEntityType.GROUP.wireName -> {
                if (field == "name") {
                    val group = database.groupDao().queryById(mapping.localId) ?: return
                    if (group.accountId != binding.localAccountId) return
                    val rawName = runCatching { json.parseToJsonElement(valueJson).jsonPrimitive.content }.getOrDefault(valueJson)
                    database.groupDao().update(group.copy(name = rawName))
                }
            }
            SyncEntityType.FEED.wireName -> {
                if (field == "groupGeneration") return
                val feed = database.feedDao().queryById(mapping.localId) ?: return
                if (feed.accountId != binding.localAccountId) return
                val primitive = runCatching { json.parseToJsonElement(valueJson).jsonPrimitive }.getOrNull()
                val updated = when (field) {
                    "groupSyncId" -> {
                        val groupSyncId =
                            primitive?.content
                                ?: throw SyncRebaseUnsafeException(
                                    "REBASE_UNSAFE: invalid Feed groupSyncId"
                                )
                        val groupGeneration =
                            projectedWinnerToken?.let {
                                pairedGenerationForProjectedWinner(
                                    syncSpaceId,
                                    entityType,
                                    entitySyncId,
                                    mapping.generation,
                                    "groupGeneration",
                                    it,
                                )
                            } ?: pairedGenerationForProjectedField(
                                syncSpaceId = syncSpaceId,
                                entityType = entityType,
                                entitySyncId = entitySyncId,
                                entityGeneration = mapping.generation,
                                idField = "groupSyncId",
                                generationField = "groupGeneration",
                            )
                        val localGroupId =
                            resolveGroupParent(
                                syncSpaceId = syncSpaceId,
                                groupSyncId = groupSyncId,
                                groupGeneration = groupGeneration,
                                label = "Feed $entitySyncId",
                            ) ?: return
                        val group = database.groupDao().queryById(localGroupId)
                            ?: throw SyncApplyDeferredException("Missing winning Feed group")
                        check(group.accountId == binding.localAccountId) {
                            "Winning Feed group belongs to another account"
                        }
                        feed.copy(groupId = group.id)
                    }
                    "name" -> feed.copy(name = primitive?.content ?: valueJson)
                    "url" -> feed.copy(url = primitive?.content ?: valueJson)
                    "icon" -> feed.copy(icon = primitive?.content?.takeIf { it.isNotBlank() })
                    "sourceType" -> {
                        val sourceTypeWire =
                            primitive?.content?.trim()?.takeIf(String::isNotBlank)
                                ?: throw SyncRebaseUnsafeException(
                                    "REBASE_UNSAFE: invalid Feed sourceType"
                                )
                        val sourceType =
                            runCatching { SourceType.valueOf(sourceTypeWire.uppercase()) }
                                .getOrElse {
                                    throw SyncRebaseUnsafeException(
                                        "REBASE_UNSAFE: unsupported Feed sourceType $sourceTypeWire"
                                    )
                                }
                        feed.copy(sourceType = sourceType)
                    }
                    "isNotification" -> feed.copy(isNotification = primitive?.booleanOrNull ?: feed.isNotification)
                    "isFullContent" -> feed.copy(isFullContent = primitive?.booleanOrNull ?: feed.isFullContent)
                    "isBrowser" -> feed.copy(isBrowser = primitive?.booleanOrNull ?: feed.isBrowser)
                    else -> throw SyncRebaseUnsafeException("REBASE_UNSAFE: unsupported feed rollback field $field")
                }
                database.feedDao().updateAll(listOf(updated))
                val canonicalKey = SyncCanonicalIdentity.feedKey(updated.sourceType, updated.url)
                if (mapping.canonicalKey != canonicalKey) {
                    database.syncIdentityMappingDao().update(
                        mapping.copy(
                            canonicalKey = canonicalKey,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            SyncEntityType.FILTER_RULE.wireName -> {
                if (field == "feedGeneration") return
                val currentRule =
                    articleFilterRepository.getAll().firstOrNull { it.id == mapping.localId } ?: return
                val element =
                    runCatching { json.parseToJsonElement(valueJson) }.getOrNull()
                        ?: throw SyncRebaseUnsafeException(
                            "REBASE_UNSAFE: malformed FilterRule rollback field $field"
                        )
                val primitive = element as? JsonPrimitive
                val updated =
                    when (field) {
                        "feedSyncId" -> {
                            if (element is JsonNull) {
                                currentRule.copy(feedId = null)
                            } else {
                                val feedSyncId =
                                    primitive?.content
                                        ?: throw SyncRebaseUnsafeException(
                                            "REBASE_UNSAFE: invalid FilterRule feedSyncId"
                                        )
                                val feedGeneration =
                                    projectedWinnerToken?.let {
                                        pairedGenerationForProjectedWinner(
                                            syncSpaceId,
                                            entityType,
                                            entitySyncId,
                                            mapping.generation,
                                            "feedGeneration",
                                            it,
                                        )
                                    } ?: pairedGenerationForProjectedField(
                                        syncSpaceId = syncSpaceId,
                                        entityType = entityType,
                                        entitySyncId = entitySyncId,
                                        entityGeneration = mapping.generation,
                                        idField = "feedSyncId",
                                        generationField = "feedGeneration",
                                    )
                                val localFeedId =
                                    resolveFeedScopedConfigParent(
                                        syncSpaceId = syncSpaceId,
                                        feedSyncId = feedSyncId,
                                        feedGeneration = feedGeneration,
                                        label = "Filter rule $entitySyncId",
                                    ) ?: run {
                                        articleFilterRepository.delete(currentRule)
                                        return
                                    }
                                currentRule.copy(feedId = localFeedId)
                            }
                        }
                        "keyword" ->
                            currentRule.copy(
                                keyword =
                                    primitive?.content
                                        ?: throw SyncRebaseUnsafeException(
                                            "REBASE_UNSAFE: invalid FilterRule keyword"
                                        )
                            )
                        "feedName" ->
                            currentRule.copy(
                                feedName = if (element is JsonNull) null else primitive?.content
                            )
                        "type" ->
                            currentRule.copy(
                                type =
                                    runCatching {
                                        ArticleFilterRuleType.valueOf(
                                            primitive?.content
                                                ?: throw IllegalArgumentException()
                                        )
                                    }.getOrElse {
                                        throw SyncRebaseUnsafeException(
                                            "REBASE_UNSAFE: invalid FilterRule type"
                                        )
                                    }
                            )
                        "enabled" ->
                            currentRule.copy(
                                enabled =
                                    primitive?.booleanOrNull
                                        ?: throw SyncRebaseUnsafeException(
                                            "REBASE_UNSAFE: invalid FilterRule enabled"
                                        )
                            )
                        else ->
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: unsupported FilterRule rollback field $field"
                            )
                    }
                articleFilterRepository.upsert(updated)
            }
            SyncEntityType.WEBSITE_RULE.wireName -> {
                if (field != "rule") {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: unsupported WebsiteRule rollback field $field"
                    )
                }
                val restored =
                    runCatching { json.decodeFromString<WebsiteRule>(valueJson) }
                        .getOrElse {
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: malformed WebsiteRule rollback payload"
                            )
                        }
                if (restored.id != mapping.localId) {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: WebsiteRule rollback identity mismatch"
                    )
                }
                websiteRuleRepository.replaceSyncRules(
                    websiteRuleRepository.listSyncRules().filterNot { it.id == restored.id } + restored
                )
            }
            SyncEntityType.JSON_RULE.wireName -> {
                if (field != "rule") {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: unsupported JsonRule rollback field $field"
                    )
                }
                val restored =
                    runCatching { json.decodeFromString<JsonRule>(valueJson) }
                        .getOrElse {
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: malformed JsonRule rollback payload"
                            )
                        }
                if (restored.id != mapping.localId) {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: JsonRule rollback identity mismatch"
                    )
                }
                jsonRuleRepository.replaceSyncRules(
                    jsonRuleRepository.listSyncRules().filterNot { it.id == restored.id } + restored
                )
            }
            SyncEntityType.RSSHUB_SETTINGS.wireName -> {
                if (field != "settings") {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: unsupported RSSHub settings rollback field $field"
                    )
                }
                rssHubSettingsRepository.replaceSyncSettings(
                    runCatching { decodeRssHubSettings(valueJson) }
                        .getOrElse {
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: malformed RSSHub settings rollback payload"
                            )
                        }
                )
            }
            SyncEntityType.WEBSITE_PARSE_PREFERENCE.wireName -> {
                if (field != "preference") {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: unsupported website parse preference rollback field $field"
                    )
                }
                val preference =
                    runCatching { json.parseToJsonElement(valueJson).jsonObject }
                        .getOrElse {
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: malformed website parse preference rollback payload"
                            )
                        }
                val feedSyncId =
                    preference["feedSyncId"]?.jsonPrimitive?.content
                        ?: throw SyncRebaseUnsafeException(
                            "REBASE_UNSAFE: website parse preference rollback has no feedSyncId"
                        )
                val feedGeneration =
                    preference["feedGeneration"]
                        ?.takeIf { it != JsonNull }
                        ?.jsonPrimitive
                        ?.longOrNull
                val localFeedId =
                    resolveFeedScopedConfigParent(
                        syncSpaceId,
                        feedSyncId,
                        feedGeneration,
                        "Website parse preference $entitySyncId",
                    ) ?: return
                if (preference["__syncAbsent"]?.jsonPrimitive?.booleanOrNull == true) {
                    websiteParsePreferenceRepository.applyUserSyncState(localFeedId, null)
                } else {
                    fun nullableText(name: String): String? =
                        preference[name]?.let { value ->
                            if (value == JsonNull) null else value.jsonPrimitive.content
                        }
                    websiteParsePreferenceRepository.applyUserSyncState(
                        localFeedId,
                        WebsiteParsePreferenceUserSyncState(
                            dynamicRenderingEnabled =
                                preference["dynamicRenderingEnabled"]
                                    ?.jsonPrimitive
                                    ?.booleanOrNull ?: false,
                            preferredRuleId = nullableText("preferredRuleId"),
                            preferredRuleName = nullableText("preferredRuleName"),
                        ),
                    )
                }
            }
            SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE.wireName -> {
                if (field != "source") {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: unsupported RSSHub source rollback field $field"
                    )
                }
                val source =
                    runCatching { json.parseToJsonElement(valueJson).jsonObject }
                        .getOrElse {
                            throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: malformed RSSHub source rollback payload"
                            )
                        }
                val feedSyncId =
                    source["feedSyncId"]?.jsonPrimitive?.content
                        ?: throw SyncRebaseUnsafeException(
                            "REBASE_UNSAFE: RSSHub source rollback has no feedSyncId"
                        )
                val feedGeneration =
                    source["feedGeneration"]
                        ?.takeIf { it != JsonNull }
                        ?.jsonPrimitive
                        ?.longOrNull
                val localFeedId =
                    resolveFeedScopedConfigParent(
                        syncSpaceId,
                        feedSyncId,
                        feedGeneration,
                        "RSSHub subscription source $entitySyncId",
                    ) ?: return
                if (source["__syncAbsent"]?.jsonPrimitive?.booleanOrNull == true) {
                    rssHubSubscriptionRepository.replaceSyncSource(localFeedId, null)
                } else {
                    val sourceUrl =
                        source["sourceUrl"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
                            ?: throw SyncRebaseUnsafeException(
                                "REBASE_UNSAFE: RSSHub source rollback has no sourceUrl"
                            )
                    rssHubSubscriptionRepository.replaceSyncSource(localFeedId, sourceUrl)
                }
            }
        }
    }
}
