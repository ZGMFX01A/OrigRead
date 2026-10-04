package me.ash.reader.infrastructure.sync.core

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.rsshub.RssHubInstance
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceUserSyncState
import me.ash.reader.infrastructure.website.WebsiteRule

@Serializable
private data class ArticleStateFieldPayloadV1(
    val field: String,
    val value: Boolean,
)

@Singleton
class LibrarySyncMutationCapture @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val database: AndroidDatabase,
    private val coordinator: SyncRuntimeCoordinator,
    private val allocator: SyncOutboxAllocator,
    private val localBlobStore: SyncLocalBlobStore,
    private val blobState: SyncBlobStateService,
    private val localEviction: SyncLocalEvictionService,
) {
    private val json = Json { encodeDefaults = true }

    /** 旧显式 block 调用保持导入语义；普通编辑使用带 scope 的捕获入口。 */
    suspend fun <T> captureLibraryMutation(accountId: Int, mutate: suspend () -> T): T =
        captureLibraryMutation(accountId, null, mutate)

    /** Captures subscription changes in the same Room transaction as the business write. */
    suspend fun <T> captureLibraryMutation(accountId: Int, scope: SyncLibrarySelection? = null, mutate: suspend () -> T): T {
        data class Row(val type: String, val id: String, val fields: JsonObject)
        suspend fun read(): Map<String, Row> = buildMap {
            val selected = selectedLibraryRows(database, accountId, scope)
            selected.groups.forEach { group ->
                put("group:${group.id}", Row("group", group.id, buildJsonObject { put("name", group.name) }))
            }
            selected.feeds.forEach { feed ->
                put("feed:${feed.id}", Row("feed", feed.id, buildJsonObject {
                    put("name", feed.name); put("url", feed.url); put("sourceType", feed.sourceType.name.lowercase())
                    put("icon", feed.icon?.let(::JsonPrimitive) ?: JsonNull)
                    put("groupLocalId", feed.groupId); put("isNotification", feed.isNotification)
                    put("isFullContent", feed.isFullContent); put("isBrowser", feed.isBrowser)
                }))
            }
            selected.articles.forEach { article ->
                put("article:${article.id}", Row("article", article.id, buildJsonObject {
                    put("feedLocalId", article.feedId)
                    put("title", article.title)
                    put("url", article.link)
                    put("author", article.author?.let(::JsonPrimitive) ?: JsonNull)
                    put("publishedAt", article.date.time)
                    put("description", article.shortDescription)
                    put("contentHtml", article.rawDescription)
                    put("imageUrl", article.img?.let(::JsonPrimitive) ?: JsonNull)
                    put("isUnread", article.isUnread)
                    put("isStarred", article.isStarred)
                    put("isReadLater", article.isReadLater)
                }))
            }
        }
        var captured = false
        suspend fun attempt(): T = coordinator.withLocalMutation(accountId) { context ->
            if (context == null) return@withLocalMutation mutate()
            captured = true
            database.withTransaction {
                val before = read()
                val result = mutate()
                val after = read()
                suspend fun ensure(
                    row: Row,
                    reviveIfDeleted: Boolean = false,
                ): SyncIdentityMappingEntity {
                    database.syncIdentityMappingDao().findByLocalId(context.syncSpaceId, row.type, row.id)?.let { existing ->
                        if (reviveIfDeleted) {
                            val tombstone =
                                database.syncInboxDao().findTombstone(
                                    context.syncSpaceId,
                                    row.type,
                                    existing.syncId,
                                )
                            if (tombstone != null && tombstone.entityGeneration >= existing.generation) {
                                val revived =
                                    existing.copy(
                                        generation = tombstone.entityGeneration + 1L,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                database.syncIdentityMappingDao().update(revived)
                                return revived
                            }
                        }
                        return existing
                    }
                    val now = System.currentTimeMillis()
                    return SyncIdentityMappingEntity(context.syncSpaceId, row.type, row.id,
                        SyncCanonicalIdentity.newSyncId(),
                        when (row.type) {
                            "feed" -> SyncCanonicalIdentity.feedCandidateKey(
                                SourceType.valueOf((row.fields.getValue("sourceType") as JsonPrimitive).content.uppercase(Locale.ROOT)),
                                (row.fields.getValue("url") as JsonPrimitive).content,
                            )
                            "article" -> {
                                // 删除行的身份必须来自 before 快照，不能再查询已经删除的业务行。
                                val rows = if ("article:${row.id}" in after) after else before
                                val feedId = (row.fields.getValue("feedLocalId") as JsonPrimitive).content
                                val feedRow = checkNotNull(rows["feed:$feedId"]) { "Article has no captured feed $feedId" }
                                SyncCanonicalIdentity.articleCandidateKey(
                                    feedCanonicalKey = ensure(feedRow).canonicalKey,
                                    articleLink = (row.fields.getValue("url") as JsonPrimitive).content,
                                )
                            }
                            else -> null
                        },
                        0, now, now).also { database.syncIdentityMappingDao().insert(it) }
                }
                suspend fun allocate(
                    row: Row,
                    mapping: SyncIdentityMappingEntity,
                    type: SyncMutationType,
                    payload: String,
                    observedEntityVersionJson: String = "{}",
                ): SyncOutboxEntity =
                    allocator.allocate(database.syncOutboxDao(), context,
                        if (row.type == "article") SyncReplicationLane.ARTICLE_STATE else SyncReplicationLane.LIBRARY,
                        SyncOutboxDraft(entityType = row.type, entitySyncId = mapping.syncId,
                            entityGeneration = mapping.generation, mutationType = type, payloadJson = payload,
                            observedEntityVersionJson = observedEntityVersionJson),
                        additionalObservedFrontiers = database.syncInboxDao().listCoverage(context.syncSpaceId).map {
                            SyncAppliedFrontierEntity(it.syncSpaceId, it.replicationLaneId, it.actorIncarnationId, it.appliedPrefix, it.updatedAt)
                        })
                for ((key, row) in after) {
                    val known = database.syncIdentityMappingDao().findByLocalId(context.syncSpaceId, row.type, row.id)
                    if (known != null && before[key]?.fields == row.fields) continue
                    var mapping = ensure(row, reviveIfDeleted = before[key] == null)
                    // URL 更新不重写创建身份时已持久化的候选输入与版本。
                    val fields = row.fields.toMutableMap()
                    if (row.type == "feed") {
                        val groupId = (fields.remove("groupLocalId") as JsonPrimitive).content
                        val group = after["group:$groupId"] ?: error("Feed has no local group")
                        val groupMapping = ensure(group)
                        fields["groupSyncId"] = JsonPrimitive(groupMapping.syncId)
                        fields["groupGeneration"] = JsonPrimitive(groupMapping.generation)
                    }
                    if (row.type == "article") {
                        val feedId = (fields.remove("feedLocalId") as JsonPrimitive).content
                        val feed = after["feed:$feedId"] ?: error("Article has no local feed")
                        val feedMapping = ensure(feed)
                        fields["feedSyncId"] = JsonPrimitive(feedMapping.syncId)
                        fields["feedGeneration"] = JsonPrimitive(feedMapping.generation)
                    }
                    if (known != null && before[key] != null) {
                        fields.keys.toList().forEach { field ->
                            val sourceField =
                                when (field) {
                                    "groupSyncId" -> "groupLocalId"
                                    "groupGeneration" -> "groupLocalId"
                                    "feedSyncId" -> "feedLocalId"
                                    "feedGeneration" -> "feedLocalId"
                                    else -> field
                                }
                            if (row.fields[sourceField] == before[key]?.fields?.get(sourceField)) fields.remove(field)
                        }
                    }
                    val observedVersions =
                        buildJsonObject {
                            fields.keys.sorted().forEach { field ->
                                database.syncInboxDao()
                                    .findFieldVersion(
                                        context.syncSpaceId,
                                        row.type,
                                        mapping.syncId,
                                        field,
                                    )
                                    ?.takeIf { it.entityGeneration == mapping.generation }
                                    ?.let { put(field, it.versionToken) }
                            }
                        }
                    val outbox = allocate(row, mapping, SyncMutationType.UPSERT,
                        buildJsonObject { put("fields", JsonObject(fields)) }.toString(),
                        observedVersions.toString())
                    for ((field, value) in fields) database.syncInboxDao().upsertFieldVersion(SyncFieldVersionEntity(
                        context.syncSpaceId, row.type, mapping.syncId, field, mapping.generation,
                        SyncVersionToken.operation(outbox.actorIncarnationId, outbox.replicationLaneId, outbox.sequence),
                        SyncOperationCanonicalizer.operationId(outbox.syncSpaceId, outbox.actorIncarnationId, outbox.replicationLaneId, outbox.sequence),
                        value.toString(), outbox.createdAt, outbox.causalContextJson, outbox.sequence))
                }
                val deletedRows =
                    before
                        .filter { (key, row) ->
                            key !in after && !libraryRowExists(database, SyncLibraryRowKey(accountId, row.type, row.id))
                        }
                        .values
                        .sortedBy { row ->
                            when (row.type) {
                                "article" -> 0
                                "feed" -> 1
                                "group" -> 2
                                else -> 3
                            }
                        }
                for (row in deletedRows) {
                    val mapping = ensure(row)
                    val outbox = allocate(row, mapping, SyncMutationType.GLOBAL_DELETE, "{}")
                    database.syncInboxDao().upsertTombstone(SyncTombstoneEntity(context.syncSpaceId, row.type, mapping.syncId,
                        mapping.generation, SyncVersionToken.operation(outbox.actorIncarnationId, outbox.replicationLaneId, outbox.sequence),
                        SyncOperationCanonicalizer.operationId(outbox.syncSpaceId, outbox.actorIncarnationId, outbox.replicationLaneId, outbox.sequence),
                        outbox.createdAt))
                    if (row.type == SyncEntityType.ARTICLE.wireName) {
                        blobState.removeOwnerReferences(
                            syncSpaceId = context.syncSpaceId,
                            lane = SyncReplicationLane.ARTICLE_STATE.wireName,
                            ownerEntityType = SyncEntityType.ARTICLE.wireName,
                            ownerEntitySyncId = mapping.syncId,
                            ownerEntityGeneration = mapping.generation,
                        )
                    }
                }
                result
            }
        }
        val result = try { attempt() } catch (error: SyncActorRollbackDetectedException) {
            coordinator.rotateActor(accountId, "library-outbox-witness-mismatch")
            attempt()
        }
        if (captured) SyncBackgroundScheduler.enqueueImmediate(applicationContext)
        return result
    }

    /**
     * CONFIG-lane capture for article filter rules.
     *
     * Runtime rules share the Reader database transaction with the Outbox. The compensating
     * restore remains for file-only callers. Remote Snapshot/apply bypasses capture and cannot
     * echo changes back into CONFIG.
     */
    suspend fun <T> captureFilterRulesMutation(
        accountId: Int,
        readRules: () -> List<ArticleFilterRule>,
        replaceRules: (List<ArticleFilterRule>) -> Unit,
        mutate: () -> T,
    ): T {
        var captured = false

        suspend fun attempt(): T =
            coordinator.withLocalMutation(accountId) { context ->
                if (context == null) return@withLocalMutation mutate()
                captured = true
                val before = readRules()
                try {
                    database.withTransaction {
                        val result = mutate()
                        val after = readRules()
                        captureFilterRuleDiff(context, before, after)
                        result
                    }
                } catch (error: Throwable) {
                    if (readRules() != before) {
                        replaceRules(before)
                    }
                    throw error
                }
            }

        val result =
            try {
                attempt()
            } catch (_: SyncActorRollbackDetectedException) {
                coordinator.rotateActor(accountId, "config-outbox-witness-mismatch")
                attempt()
            }
        if (captured) SyncBackgroundScheduler.enqueueImmediate(applicationContext)
        return result
    }

    suspend fun <T> captureWebsiteRulesMutation(
        accountId: Int,
        readRules: () -> List<WebsiteRule>,
        replaceRules: (List<WebsiteRule>) -> Unit,
        mutate: () -> T,
    ): T =
        captureAtomicConfigCollectionMutation(
            accountId = accountId,
            entityType = SyncEntityType.WEBSITE_RULE,
            fieldId = "rule",
            readValues = readRules,
            replaceValues = replaceRules,
            localId = WebsiteRule::id,
            encodeValue = { rule ->
                json.parseToJsonElement(json.encodeToString(WebsiteRule.serializer(), rule))
            },
            mutate = mutate,
        )

    suspend fun <T> captureJsonRulesMutation(
        accountId: Int,
        readRules: () -> List<JsonRule>,
        replaceRules: (List<JsonRule>) -> Unit,
        mutate: () -> T,
    ): T =
        captureAtomicConfigCollectionMutation(
            accountId = accountId,
            entityType = SyncEntityType.JSON_RULE,
            fieldId = "rule",
            readValues = readRules,
            replaceValues = replaceRules,
            localId = JsonRule::id,
            encodeValue = { rule ->
                json.parseToJsonElement(json.encodeToString(JsonRule.serializer(), rule))
            },
            mutate = mutate,
        )

    suspend fun <T> captureRssHubSettingsMutation(
        accountId: Int,
        readSettings: () -> RssHubSettings,
        replaceSettings: (RssHubSettings) -> Unit,
        mutate: () -> T,
    ): T =
        captureAtomicConfigCollectionMutation(
            accountId = accountId,
            entityType = SyncEntityType.RSSHUB_SETTINGS,
            fieldId = "settings",
            readValues = { listOf(readSettings()) },
            replaceValues = { values -> replaceSettings(values.single()) },
            localId = { "rsshub-settings" },
            encodeValue = ::rssHubSettingsJson,
            mutate = mutate,
        )

    suspend fun <T> captureWebsiteParsePreferenceMutation(
        accountId: Int,
        feedId: String,
        readState: () -> WebsiteParsePreferenceUserSyncState?,
        replaceState: (WebsiteParsePreferenceUserSyncState?) -> Unit,
        mutate: () -> T,
    ): T =
        captureWebsiteParsePreferencesMutation(
            accountId = accountId,
            feedIds = setOf(feedId),
            readStates = { mapOf(feedId to readState()) },
            replaceStates = { states -> replaceState(states[feedId]) },
            mutate = mutate,
        )

    suspend fun <T> captureWebsiteParsePreferencesMutation(
        accountId: Int,
        feedIds: Set<String>,
        readStates: () -> Map<String, WebsiteParsePreferenceUserSyncState?>,
        replaceStates: (Map<String, WebsiteParsePreferenceUserSyncState?>) -> Unit,
        mutate: () -> T,
    ): T {
        val orderedFeedIds = feedIds.filter(String::isNotBlank).distinct().sorted()
        if (orderedFeedIds.isEmpty()) return mutate()
        var captured = false

        suspend fun attempt(): T =
            coordinator.withLocalMutation(accountId) { context ->
                if (context == null) return@withLocalMutation mutate()
                val feedMappings =
                    orderedFeedIds.associateWith { localFeedId ->
                        database.syncIdentityMappingDao().findByLocalId(
                            context.syncSpaceId,
                            SyncEntityType.FEED.wireName,
                            localFeedId,
                        ) ?: error(
                            "Website parse preference feed is not mapped: $localFeedId"
                        )
                    }

                fun normalizedStates(
                    values: Map<String, WebsiteParsePreferenceUserSyncState?>,
                ): Map<String, WebsiteParsePreferenceUserSyncState?> =
                    orderedFeedIds.associateWith(values::get)

                fun encoded(
                    states: Map<String, WebsiteParsePreferenceUserSyncState?>,
                ): Map<String, JsonElement> =
                    buildMap {
                        orderedFeedIds.forEach { localFeedId ->
                            val state = states[localFeedId] ?: return@forEach
                            val feedSyncId = feedMappings.getValue(localFeedId).syncId
                            put(
                                feedSyncId,
                                buildJsonObject {
                                    put("feedSyncId", feedSyncId)
                                    put("feedGeneration", feedMappings.getValue(localFeedId).generation)
                                    put("dynamicRenderingEnabled", state.dynamicRenderingEnabled)
                                    state.preferredRuleId?.let { value ->
                                        put("preferredRuleId", value)
                                    } ?: put("preferredRuleId", JsonNull)
                                    state.preferredRuleName?.let { value ->
                                        put("preferredRuleName", value)
                                    } ?: put("preferredRuleName", JsonNull)
                                },
                            )
                        }
                    }

                captured = true
                val before = normalizedStates(readStates())
                try {
                    database.withTransaction {
                        val result = mutate()
                        val after = normalizedStates(readStates())
                        captureAtomicConfigEntityDiff(
                            context = context,
                            entityType = SyncEntityType.WEBSITE_PARSE_PREFERENCE,
                            fieldId = "preference",
                            before = encoded(before),
                            after = encoded(after),
                        )
                        result
                    }
                } catch (error: Throwable) {
                    if (normalizedStates(readStates()) != before) replaceStates(before)
                    throw error
                }
            }

        val result =
            try {
                attempt()
            } catch (_: SyncActorRollbackDetectedException) {
                coordinator.rotateActor(accountId, "config-outbox-witness-mismatch")
                attempt()
            }
        if (captured) SyncBackgroundScheduler.enqueueImmediate(applicationContext)
        return result
    }

    suspend fun <T> captureRssHubSubscriptionSourceMutation(
        accountId: Int,
        feedId: String,
        readState: () -> String?,
        replaceState: (String?) -> Unit,
        mutate: () -> T,
    ): T =
        captureRssHubSubscriptionSourcesMutation(
            accountId = accountId,
            feedIds = setOf(feedId),
            readStates = { mapOf(feedId to readState()) },
            replaceStates = { states -> replaceState(states[feedId]) },
            mutate = mutate,
        )

    suspend fun <T> captureRssHubSubscriptionSourcesMutation(
        accountId: Int,
        feedIds: Set<String>,
        readStates: () -> Map<String, String?>,
        replaceStates: (Map<String, String?>) -> Unit,
        mutate: () -> T,
    ): T {
        val orderedFeedIds = feedIds.filter(String::isNotBlank).distinct().sorted()
        if (orderedFeedIds.isEmpty()) return mutate()
        var captured = false

        suspend fun attempt(): T =
            coordinator.withLocalMutation(accountId) { context ->
                if (context == null) return@withLocalMutation mutate()
                val feedMappings =
                    orderedFeedIds.associateWith { localFeedId ->
                        database.syncIdentityMappingDao().findByLocalId(
                            context.syncSpaceId,
                            SyncEntityType.FEED.wireName,
                            localFeedId,
                        ) ?: error(
                            "RSSHub subscription source feed is not mapped: $localFeedId"
                        )
                    }

                fun normalizedStates(values: Map<String, String?>): Map<String, String?> =
                    orderedFeedIds.associateWith { feedId ->
                        values[feedId]?.trim()?.takeIf(String::isNotBlank)
                    }

                fun encoded(states: Map<String, String?>): Map<String, JsonElement> =
                    buildMap {
                        orderedFeedIds.forEach { localFeedId ->
                            val sourceUrl = states[localFeedId] ?: return@forEach
                            val feedSyncId = feedMappings.getValue(localFeedId).syncId
                            put(
                                feedSyncId,
                                buildJsonObject {
                                    put("feedSyncId", feedSyncId)
                                    put("feedGeneration", feedMappings.getValue(localFeedId).generation)
                                    put("sourceUrl", sourceUrl)
                                },
                            )
                        }
                    }

                captured = true
                val before = normalizedStates(readStates())
                try {
                    database.withTransaction {
                        val result = mutate()
                        val after = normalizedStates(readStates())
                        captureAtomicConfigEntityDiff(
                            context = context,
                            entityType = SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE,
                            fieldId = "source",
                            before = encoded(before),
                            after = encoded(after),
                        )
                        result
                    }
                } catch (error: Throwable) {
                    if (normalizedStates(readStates()) != before) replaceStates(before)
                    throw error
                }
            }

        val result =
            try {
                attempt()
            } catch (_: SyncActorRollbackDetectedException) {
                coordinator.rotateActor(accountId, "config-outbox-witness-mismatch")
                attempt()
            }
        if (captured) SyncBackgroundScheduler.enqueueImmediate(applicationContext)
        return result
    }

    private suspend fun <T, V> captureAtomicConfigCollectionMutation(
        accountId: Int,
        entityType: SyncEntityType,
        fieldId: String,
        readValues: () -> List<V>,
        replaceValues: (List<V>) -> Unit,
        localId: (V) -> String,
        encodeValue: (V) -> JsonElement,
        mutate: () -> T,
    ): T {
        var captured = false

        suspend fun attempt(): T =
            coordinator.withLocalMutation(accountId) { context ->
                if (context == null) return@withLocalMutation mutate()
                captured = true
                val before = readValues()
                try {
                    database.withTransaction {
                        val result = mutate()
                        val after = readValues()
                        captureAtomicConfigEntityDiff(
                            context = context,
                            entityType = entityType,
                            fieldId = fieldId,
                            before = before.associate { localId(it) to encodeValue(it) },
                            after = after.associate { localId(it) to encodeValue(it) },
                        )
                        result
                    }
                } catch (error: Throwable) {
                    if (readValues() != before) replaceValues(before)
                    throw error
                }
            }

        val result =
            try {
                attempt()
            } catch (_: SyncActorRollbackDetectedException) {
                coordinator.rotateActor(accountId, "config-outbox-witness-mismatch")
                attempt()
            }
        if (captured) SyncBackgroundScheduler.enqueueImmediate(applicationContext)
        return result
    }

    private suspend fun captureAtomicConfigEntityDiff(
        context: SyncWritableActorContext,
        entityType: SyncEntityType,
        fieldId: String,
        before: Map<String, JsonElement>,
        after: Map<String, JsonElement>,
    ) {
        suspend fun ensureMapping(
            localId: String,
            reviveIfDeleted: Boolean = false,
        ): SyncIdentityMappingEntity {
            database.syncIdentityMappingDao()
                .findByLocalId(context.syncSpaceId, entityType.wireName, localId)
                ?.let { existing ->
                    if (reviveIfDeleted) {
                        val tombstone =
                            database.syncInboxDao().findTombstone(
                                context.syncSpaceId,
                                entityType.wireName,
                                existing.syncId,
                            )
                        if (
                            tombstone != null &&
                            tombstone.entityGeneration >= existing.generation
                        ) {
                            val revived =
                                existing.copy(
                                    generation = tombstone.entityGeneration + 1L,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            database.syncIdentityMappingDao().update(revived)
                            return revived
                        }
                    }
                    return existing
                }
            val syncId = SyncCanonicalIdentity.configRuleSyncId(entityType, localId)
            database.syncIdentityMappingDao()
                .findBySyncId(context.syncSpaceId, entityType.wireName, syncId)
                ?.let { existing ->
                    check(existing.localId == localId) {
                        "CONFIG mapping collision for ${entityType.wireName}/$localId"
                    }
                    if (reviveIfDeleted) {
                        val tombstone =
                            database.syncInboxDao().findTombstone(
                                context.syncSpaceId,
                                entityType.wireName,
                                existing.syncId,
                            )
                        if (
                            tombstone != null &&
                            tombstone.entityGeneration >= existing.generation
                        ) {
                            val revived =
                                existing.copy(
                                    generation = tombstone.entityGeneration + 1L,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            database.syncIdentityMappingDao().update(revived)
                            return revived
                        }
                    }
                    return existing
                }
            val now = System.currentTimeMillis()
            return SyncIdentityMappingEntity(
                syncSpaceId = context.syncSpaceId,
                entityType = entityType.wireName,
                localId = localId,
                syncId = syncId,
                canonicalKey = null,
                generation = 0,
                createdAt = now,
                updatedAt = now,
            ).also { database.syncIdentityMappingDao().insert(it) }
        }

        val observedFrontiers =
            database.syncInboxDao().listCoverage(context.syncSpaceId).map {
                SyncAppliedFrontierEntity(
                    it.syncSpaceId,
                    it.replicationLaneId,
                    it.actorIncarnationId,
                    it.appliedPrefix,
                    it.updatedAt,
                )
            }

        for ((id, value) in after) {
            if (before[id] == value) continue
            val mapping = ensureMapping(id, reviveIfDeleted = id !in before)
            val previous =
                database.syncInboxDao().findFieldVersion(
                    context.syncSpaceId,
                    entityType.wireName,
                    mapping.syncId,
                    fieldId,
                )
                    ?.takeIf { it.entityGeneration == mapping.generation }
            val outbox =
                allocator.allocate(
                    dao = database.syncOutboxDao(),
                    context = context,
                    lane = SyncReplicationLane.CONFIG,
                    additionalObservedFrontiers = observedFrontiers,
                    draft =
                        SyncOutboxDraft(
                            entityType = entityType.wireName,
                            entitySyncId = mapping.syncId,
                            entityGeneration = mapping.generation,
                            mutationType = SyncMutationType.UPSERT,
                            payloadJson =
                                buildJsonObject {
                                    put(
                                        "fields",
                                        buildJsonObject {
                                            put(fieldId, value)
                                        },
                                    )
                                }.toString(),
                            observedEntityVersionJson =
                                buildJsonObject {
                                    previous?.let { put(fieldId, it.versionToken) }
                                }.toString(),
                        ),
                )
            database.syncInboxDao().upsertFieldVersion(
                SyncFieldVersionEntity(
                    syncSpaceId = context.syncSpaceId,
                    entityType = entityType.wireName,
                    entitySyncId = mapping.syncId,
                    fieldId = fieldId,
                    entityGeneration = mapping.generation,
                    versionToken =
                        SyncVersionToken.operation(
                            outbox.actorIncarnationId,
                            outbox.replicationLaneId,
                            outbox.sequence,
                        ),
                    sourceOperationId =
                        SyncOperationCanonicalizer.operationId(
                            outbox.syncSpaceId,
                            outbox.actorIncarnationId,
                            outbox.replicationLaneId,
                            outbox.sequence,
                        ),
                    valueJson = value.toString(),
                    updatedAt = outbox.createdAt,
                    causalContextJson = outbox.causalContextJson,
                    logicalClock = outbox.sequence,
                )
            )
        }

        for ((id, _) in before) {
            if (id in after) continue
            val mapping = ensureMapping(id)
            val outbox =
                allocator.allocate(
                    dao = database.syncOutboxDao(),
                    context = context,
                    lane = SyncReplicationLane.CONFIG,
                    additionalObservedFrontiers = observedFrontiers,
                    draft =
                        SyncOutboxDraft(
                            entityType = entityType.wireName,
                            entitySyncId = mapping.syncId,
                            entityGeneration = mapping.generation,
                            mutationType = SyncMutationType.GLOBAL_DELETE,
                            payloadJson = "{}",
                        ),
                )
            database.syncInboxDao().upsertTombstone(
                SyncTombstoneEntity(
                    syncSpaceId = context.syncSpaceId,
                    entityType = entityType.wireName,
                    entitySyncId = mapping.syncId,
                    entityGeneration = mapping.generation,
                    versionToken =
                        SyncVersionToken.operation(
                            outbox.actorIncarnationId,
                            outbox.replicationLaneId,
                            outbox.sequence,
                        ),
                    sourceOperationId =
                        SyncOperationCanonicalizer.operationId(
                            outbox.syncSpaceId,
                            outbox.actorIncarnationId,
                            outbox.replicationLaneId,
                            outbox.sequence,
                        ),
                    updatedAt = outbox.createdAt,
                )
            )
        }
    }

    /**
     * Repairs the crash window for CONFIG repositories that live outside Room.
     *
     * [actual] is the durable file/SharedPreferences fact observed after all pending remote Inbox
     * operations have been replayed. The expected state is reconstructed from active generation
     * FieldVersion/Tombstone metadata, then any drift is emitted through the normal CONFIG Outbox
     * path. This makes a process death between an external config write and the Room transaction
     * commit recoverable without inventing a second mutation protocol.
     */
    suspend fun reconcileAtomicConfigState(
        accountId: Int,
        entityType: SyncEntityType,
        fieldId: String,
        actual: Map<String, JsonElement>,
    ): Int {
        require(
            entityType in
                setOf(
                    SyncEntityType.WEBSITE_RULE,
                    SyncEntityType.JSON_RULE,
                    SyncEntityType.RSSHUB_SETTINGS,
                    SyncEntityType.WEBSITE_PARSE_PREFERENCE,
                    SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE,
                )
        ) {
            "Unsupported external CONFIG reconciliation type: ${entityType.wireName}"
        }

        suspend fun attempt(): Int =
            coordinator.withLocalMutation(accountId) { context ->
                if (context == null) return@withLocalMutation 0
                database.withTransaction {
                    val expected = linkedMapOf<String, JsonElement>()
                    database.syncIdentityMappingDao()
                        .findByType(context.syncSpaceId, entityType.wireName)
                        .forEach { mapping ->
                            val tombstone =
                                database.syncInboxDao().findTombstone(
                                    context.syncSpaceId,
                                    entityType.wireName,
                                    mapping.syncId,
                                )
                            if (
                                tombstone != null &&
                                tombstone.entityGeneration >= mapping.generation
                            ) {
                                return@forEach
                            }
                            val version =
                                database.syncInboxDao().findFieldVersion(
                                    context.syncSpaceId,
                                    entityType.wireName,
                                    mapping.syncId,
                                    fieldId,
                                )?.takeIf { it.entityGeneration == mapping.generation }
                                    ?: return@forEach
                            val value =
                                runCatching { json.parseToJsonElement(version.valueJson) }
                                    .getOrElse {
                                        throw IllegalStateException(
                                            "Malformed CONFIG field version for " +
                                                entityType.wireName + "/" + mapping.syncId,
                                            it,
                                        )
                                    }
                            expected[mapping.localId] = value
                        }

                    val changed =
                        (expected.keys + actual.keys).count { key ->
                            expected[key] != actual[key]
                        }
                    if (changed == 0) return@withTransaction 0

                    captureAtomicConfigEntityDiff(
                        context = context,
                        entityType = entityType,
                        fieldId = fieldId,
                        before = expected,
                        after = actual,
                    )
                    changed
                }
            }

        return try {
            attempt()
        } catch (_: SyncActorRollbackDetectedException) {
            coordinator.rotateActor(accountId, "config-drift-outbox-witness-mismatch")
            attempt()
        }
    }

    private fun rssHubSettingsJson(settings: RssHubSettings): JsonElement =
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
        }

    private suspend fun captureFilterRuleDiff(
        context: SyncWritableActorContext,
        before: List<ArticleFilterRule>,
        after: List<ArticleFilterRule>,
    ) {
        val beforeById = before.associateBy(ArticleFilterRule::id)
        val afterById = after.associateBy(ArticleFilterRule::id)

        suspend fun ensureMapping(
            rule: ArticleFilterRule,
            reviveIfDeleted: Boolean = false,
        ): SyncIdentityMappingEntity {
            database.syncIdentityMappingDao()
                .findByLocalId(
                    context.syncSpaceId,
                    SyncEntityType.FILTER_RULE.wireName,
                    rule.id,
                )
                ?.let { existing ->
                    if (reviveIfDeleted) {
                        val tombstone =
                            database.syncInboxDao().findTombstone(
                                context.syncSpaceId,
                                SyncEntityType.FILTER_RULE.wireName,
                                existing.syncId,
                            )
                        if (
                            tombstone != null &&
                            tombstone.entityGeneration >= existing.generation
                        ) {
                            val revived =
                                existing.copy(
                                    generation = tombstone.entityGeneration + 1L,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            database.syncIdentityMappingDao().update(revived)
                            return revived
                        }
                    }
                    return existing
                }
            val now = System.currentTimeMillis()
            return SyncIdentityMappingEntity(
                syncSpaceId = context.syncSpaceId,
                entityType = SyncEntityType.FILTER_RULE.wireName,
                localId = rule.id,
                syncId = SyncCanonicalIdentity.newSyncId(),
                canonicalKey = null,
                generation = 0,
                createdAt = now,
                updatedAt = now,
            ).also { database.syncIdentityMappingDao().insert(it) }
        }

        suspend fun fieldsFor(rule: ArticleFilterRule): JsonObject {
            val feedMapping =
                rule.feedId?.let { localFeedId ->
                    database.syncIdentityMappingDao()
                        .findByLocalId(
                            context.syncSpaceId,
                            SyncEntityType.FEED.wireName,
                            localFeedId,
                        )
                        ?: throw IllegalStateException(
                            "Filter rule " + rule.id + " references a feed with no Sync mapping"
                        )
                }
            return buildJsonObject {
                put("keyword", rule.keyword)
                put("feedSyncId", feedMapping?.syncId?.let(::JsonPrimitive) ?: JsonNull)
                put("feedGeneration", feedMapping?.generation?.let(::JsonPrimitive) ?: JsonNull)
                put("feedName", rule.feedName?.let(::JsonPrimitive) ?: JsonNull)
                put("type", rule.type.name)
                put("enabled", rule.enabled)
            }
        }

        val observedFrontiers =
            database.syncInboxDao().listCoverage(context.syncSpaceId).map {
                SyncAppliedFrontierEntity(
                    it.syncSpaceId,
                    it.replicationLaneId,
                    it.actorIncarnationId,
                    it.appliedPrefix,
                    it.updatedAt,
                )
            }

        for ((id, rule) in afterById) {
            if (beforeById[id] == rule) continue
            val mapping = ensureMapping(rule, reviveIfDeleted = id !in beforeById)
            val fields = fieldsFor(rule)
            val observed = mutableMapOf<String, String>()
            for (field in fields.keys) {
                database.syncInboxDao()
                    .findFieldVersion(
                        context.syncSpaceId,
                        SyncEntityType.FILTER_RULE.wireName,
                        mapping.syncId,
                        field,
                    )
                    ?.takeIf { it.entityGeneration == mapping.generation }
                    ?.let { observed[field] = it.versionToken }
            }
            val outbox =
                allocator.allocate(
                    dao = database.syncOutboxDao(),
                    context = context,
                    lane = SyncReplicationLane.CONFIG,
                    additionalObservedFrontiers = observedFrontiers,
                    draft =
                        SyncOutboxDraft(
                            entityType = SyncEntityType.FILTER_RULE.wireName,
                            entitySyncId = mapping.syncId,
                            entityGeneration = mapping.generation,
                            mutationType = SyncMutationType.UPSERT,
                            payloadJson =
                                buildJsonObject {
                                    put("fields", fields)
                                }.toString(),
                            observedEntityVersionJson =
                                buildJsonObject {
                                    observed.toSortedMap().forEach { (field, token) ->
                                        put(field, token)
                                    }
                                }.toString(),
                        ),
                )
            val versionToken =
                SyncVersionToken.operation(
                    outbox.actorIncarnationId,
                    outbox.replicationLaneId,
                    outbox.sequence,
                )
            val operationId =
                SyncOperationCanonicalizer.operationId(
                    outbox.syncSpaceId,
                    outbox.actorIncarnationId,
                    outbox.replicationLaneId,
                    outbox.sequence,
                )
            for ((field, value) in fields) {
                database.syncInboxDao().upsertFieldVersion(
                    SyncFieldVersionEntity(
                        syncSpaceId = context.syncSpaceId,
                        entityType = SyncEntityType.FILTER_RULE.wireName,
                        entitySyncId = mapping.syncId,
                        fieldId = field,
                        entityGeneration = mapping.generation,
                        versionToken = versionToken,
                        sourceOperationId = operationId,
                        valueJson = value.toString(),
                        updatedAt = outbox.createdAt,
                        causalContextJson = outbox.causalContextJson,
                        logicalClock = outbox.sequence,
                    )
                )
            }
        }

        for ((id, rule) in beforeById) {
            if (id in afterById) continue
            val mapping = ensureMapping(rule)
            val outbox =
                allocator.allocate(
                    dao = database.syncOutboxDao(),
                    context = context,
                    lane = SyncReplicationLane.CONFIG,
                    additionalObservedFrontiers = observedFrontiers,
                    draft =
                        SyncOutboxDraft(
                            entityType = SyncEntityType.FILTER_RULE.wireName,
                            entitySyncId = mapping.syncId,
                            entityGeneration = mapping.generation,
                            mutationType = SyncMutationType.GLOBAL_DELETE,
                            payloadJson = "{}",
                        ),
                )
            database.syncInboxDao().upsertTombstone(
                SyncTombstoneEntity(
                    syncSpaceId = context.syncSpaceId,
                    entityType = SyncEntityType.FILTER_RULE.wireName,
                    entitySyncId = mapping.syncId,
                    entityGeneration = mapping.generation,
                    versionToken =
                        SyncVersionToken.operation(
                            outbox.actorIncarnationId,
                            outbox.replicationLaneId,
                            outbox.sequence,
                        ),
                    sourceOperationId =
                        SyncOperationCanonicalizer.operationId(
                            outbox.syncSpaceId,
                            outbox.actorIncarnationId,
                            outbox.replicationLaneId,
                            outbox.sequence,
                        ),
                    updatedAt = outbox.createdAt,
                )
            )
        }
    }

    suspend fun <T> captureArticleField(
        accountId: Int,
        changedArticleIds: suspend () -> List<String>,
        field: String,
        value: Boolean,
        mutate: suspend () -> T,
    ): T {
        var context: SyncWritableActorContext? = null

        suspend fun attempt(): T =
            coordinator.withLocalMutation(accountId) { currentContext ->
                context = currentContext
                if (currentContext == null) return@withLocalMutation mutate()
                database.withTransaction {
                    val ids = changedArticleIds().distinct()
                    ids.forEach { articleId ->
                        val mapping = ensureArticleMapping(currentContext, articleId)
                        val previous = database.syncInboxDao().findFieldVersion(
                            currentContext.syncSpaceId, SyncEntityType.ARTICLE.wireName, mapping.syncId, field)
                        val outbox = allocator.allocate(
                            dao = database.syncOutboxDao(),
                            context = currentContext,
                            lane = SyncReplicationLane.ARTICLE_STATE,
                            additionalObservedFrontiers = database.syncInboxDao()
                                .listCoverage(currentContext.syncSpaceId).map {
                                    SyncAppliedFrontierEntity(it.syncSpaceId, it.replicationLaneId,
                                        it.actorIncarnationId, it.appliedPrefix, it.updatedAt)
                                },
                            draft =
                                SyncOutboxDraft(
                                    entityType = SyncEntityType.ARTICLE.wireName,
                                    entitySyncId = mapping.syncId,
                                    entityGeneration = mapping.generation,
                                    mutationType = SyncMutationType.FIELD_SET,
                                    payloadJson = json.encodeToString(ArticleStateFieldPayloadV1(field, value)),
                                    observedEntityVersionJson = buildJsonObject {
                                        previous?.let { put(field, it.versionToken) }
                                    }.toString(),
                                ),
                        )
                        database.syncInboxDao().upsertFieldVersion(SyncFieldVersionEntity(
                            syncSpaceId = currentContext.syncSpaceId,
                            entityType = SyncEntityType.ARTICLE.wireName,
                            entitySyncId = mapping.syncId,
                            fieldId = field,
                            entityGeneration = mapping.generation,
                            versionToken = SyncVersionToken.operation(outbox.actorIncarnationId, outbox.replicationLaneId, outbox.sequence),
                            sourceOperationId = SyncOperationCanonicalizer.operationId(outbox.syncSpaceId, outbox.actorIncarnationId, outbox.replicationLaneId, outbox.sequence),
                            valueJson = value.toString(),
                            updatedAt = outbox.createdAt,
                            causalContextJson = outbox.causalContextJson,
                            logicalClock = outbox.sequence,
                        ))
                    }
                    mutate()
                }
            }

        val result = try {
            attempt()
        } catch (error: SyncActorRollbackDetectedException) {
            context = coordinator.rotateActor(accountId, "article-state-outbox-witness-mismatch")
            attempt()
        }
        if (context != null) SyncBackgroundScheduler.enqueueImmediate(applicationContext)
        return result
    }

    /**
     * Capture downloaded/readability full content as a REHYDRATABLE Blob.
     *
     * The Operation carries only the manifest/reference. The large HTML bytes live in the local
     * content-addressed Blob store and are uploaded separately by the Sync session.
     */
    suspend fun <T> captureArticleFullContent(
        accountId: Int,
        articleId: String,
        content: String,
        mutate: suspend () -> T,
    ): T {
        require(content.isNotBlank()) { "Article full content must not be blank" }
        var context: SyncWritableActorContext? = null

        suspend fun attempt(): T =
            coordinator.withLocalMutation(accountId) { currentContext ->
                context = currentContext
                if (currentContext == null) return@withLocalMutation mutate()

                val reference = SyncBlobPayloadCodec.articleFullContentReference(content)
                localBlobStore.putUtf8Text(reference, content)
                database.withTransaction {
                    val mapping = ensureArticleMapping(currentContext, articleId)
                    blobState.registerManifest(
                        reference.manifest,
                        SyncBlobAvailabilityState.READY,
                    )
                    blobState.replaceOwnerReference(
                        syncSpaceId = currentContext.syncSpaceId,
                        lane = SyncReplicationLane.ARTICLE_STATE.wireName,
                        ownerEntityType = SyncEntityType.ARTICLE.wireName,
                        ownerEntitySyncId = mapping.syncId,
                        ownerEntityGeneration = mapping.generation,
                        referenceKind = reference.referenceKind,
                        hash = reference.manifest.hash,
                    )
                    localEviction.clearEvicted(
                        currentContext.syncSpaceId,
                        SyncEntityType.ARTICLE.wireName,
                        mapping.syncId,
                        mapping.generation,
                        SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND,
                    )

                    val hashValueJson = JsonPrimitive(reference.manifest.hash).toString()
                    val previous =
                        database.syncInboxDao().findFieldVersion(
                            currentContext.syncSpaceId,
                            SyncEntityType.ARTICLE.wireName,
                            mapping.syncId,
                            SYNC_ARTICLE_FULL_CONTENT_FIELD,
                        )
                    if (previous?.valueJson != hashValueJson) {
                        val payloadJson =
                            buildJsonObject {
                                put("field", SYNC_ARTICLE_FULL_CONTENT_FIELD)
                                put("value", reference.manifest.hash)
                                put(
                                    "blobRefs",
                                    buildJsonArray {
                                        add(
                                            json.encodeToJsonElement(
                                                SyncPayloadBlobRefWire.serializer(),
                                                reference,
                                            )
                                        )
                                    },
                                )
                            }.toString()
                        val outbox =
                            allocator.allocate(
                                dao = database.syncOutboxDao(),
                                context = currentContext,
                                lane = SyncReplicationLane.ARTICLE_STATE,
                                additionalObservedFrontiers =
                                    database.syncInboxDao()
                                        .listCoverage(currentContext.syncSpaceId)
                                        .map {
                                            SyncAppliedFrontierEntity(
                                                it.syncSpaceId,
                                                it.replicationLaneId,
                                                it.actorIncarnationId,
                                                it.appliedPrefix,
                                                it.updatedAt,
                                            )
                                        },
                                draft =
                                    SyncOutboxDraft(
                                        entityType = SyncEntityType.ARTICLE.wireName,
                                        entitySyncId = mapping.syncId,
                                        entityGeneration = mapping.generation,
                                        mutationType = SyncMutationType.FIELD_SET,
                                        payloadJson = payloadJson,
                                        observedEntityVersionJson =
                                            buildJsonObject {
                                                previous?.let {
                                                    put(SYNC_ARTICLE_FULL_CONTENT_FIELD, it.versionToken)
                                                }
                                            }.toString(),
                                    ),
                            )
                        database.syncInboxDao().upsertFieldVersion(
                            SyncFieldVersionEntity(
                                syncSpaceId = currentContext.syncSpaceId,
                                entityType = SyncEntityType.ARTICLE.wireName,
                                entitySyncId = mapping.syncId,
                                fieldId = SYNC_ARTICLE_FULL_CONTENT_FIELD,
                                entityGeneration = mapping.generation,
                                versionToken =
                                    SyncVersionToken.operation(
                                        outbox.actorIncarnationId,
                                        outbox.replicationLaneId,
                                        outbox.sequence,
                                    ),
                                sourceOperationId =
                                    SyncOperationCanonicalizer.operationId(
                                        outbox.syncSpaceId,
                                        outbox.actorIncarnationId,
                                        outbox.replicationLaneId,
                                        outbox.sequence,
                                    ),
                                valueJson = hashValueJson,
                                updatedAt = outbox.createdAt,
                                causalContextJson = outbox.causalContextJson,
                                logicalClock = outbox.sequence,
                            )
                        )
                    }
                    mutate()
                }
            }

        val result =
            try {
                attempt()
            } catch (_: SyncActorRollbackDetectedException) {
                context = coordinator.rotateActor(accountId, "article-full-content-outbox-witness-mismatch")
                attempt()
            }
        if (context != null) SyncBackgroundScheduler.enqueueImmediate(applicationContext)
        return result
    }

    /**
     * Device-local full-content cleanup. This intentionally creates no Outbox/Tombstone.
     */
    suspend fun markArticleFullContentEvicted(
        accountId: Int,
        articleId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val binding = database.syncRuntimeDao().findBinding(accountId) ?: return
        val mapping =
            database.syncIdentityMappingDao().findByLocalId(
                binding.syncSpaceId,
                SyncEntityType.ARTICLE.wireName,
                articleId,
            ) ?: return
        localEviction.markEvicted(
            binding.syncSpaceId,
            SyncEntityType.ARTICLE.wireName,
            mapping.syncId,
            mapping.generation,
            SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND,
            now,
        )
    }

    suspend fun markAllArticleFullContentEvicted(
        accountId: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        val binding = database.syncRuntimeDao().findBinding(accountId) ?: return
        database.syncIdentityMappingDao()
            .findByType(binding.syncSpaceId, SyncEntityType.ARTICLE.wireName)
            .forEach { mapping ->
                localEviction.markEvicted(
                    binding.syncSpaceId,
                    SyncEntityType.ARTICLE.wireName,
                    mapping.syncId,
                    mapping.generation,
                    SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND,
                    now,
                )
            }
    }

    private suspend fun ensureArticleMapping(
        context: SyncWritableActorContext,
        articleId: String,
    ): SyncIdentityMappingEntity {
        val mappingDao = database.syncIdentityMappingDao()
        mappingDao.findByLocalId(context.syncSpaceId, SyncEntityType.ARTICLE.wireName, articleId)?.let {
            return it
        }

        val articleWithFeed = checkNotNull(database.articleDao().queryById(articleId)) {
            "Cannot create Sync mapping for missing article $articleId"
        }
        var feedMapping =
            mappingDao.findByLocalId(
                context.syncSpaceId,
                SyncEntityType.FEED.wireName,
                articleWithFeed.feed.id,
            )
        if (feedMapping == null) {
            val now = System.currentTimeMillis()
            feedMapping =
                SyncIdentityMappingEntity(
                    syncSpaceId = context.syncSpaceId,
                    entityType = SyncEntityType.FEED.wireName,
                    localId = articleWithFeed.feed.id,
                    syncId = SyncCanonicalIdentity.newSyncId(),
                    canonicalKey = SyncCanonicalIdentity.feedCandidateKey(articleWithFeed.feed.sourceType, articleWithFeed.feed.url),
                    generation = 0,
                    createdAt = now,
                    updatedAt = now,
                )
            mappingDao.insert(feedMapping)
        }

        val now = System.currentTimeMillis()
        val mapping =
            SyncIdentityMappingEntity(
                syncSpaceId = context.syncSpaceId,
                entityType = SyncEntityType.ARTICLE.wireName,
                localId = articleId,
                syncId = SyncCanonicalIdentity.newSyncId(),
                canonicalKey =
                    SyncCanonicalIdentity.articleCandidateKey(
                        feedCanonicalKey = feedMapping.canonicalKey,
                        articleLink = articleWithFeed.article.link,
                    ),
                generation = 0,
                createdAt = now,
                updatedAt = now,
            )
        mappingDao.insert(mapping)
        return mapping
    }
}
