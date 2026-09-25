package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

internal data class SyncMergedRecoverySnapshot(
    val shards: List<SyncSnapshotShardWire>,
    val coverage: SyncCoverage,
)

private data class RecoveryMergeEntity(
    val entityType: String,
    val entitySyncId: String,
    val generation: Long,
    val fields: JsonObject,
)

private data class RecoveryMergeTombstone(
    val entityType: String,
    val entitySyncId: String,
    val generation: Long,
    val versionToken: String,
    val deletedAt: Long,
)

/**
 * Pure, deterministic R10 stale-peer Snapshot merge.
 *
 * Inputs must already have passed the normal Snapshot signature/hash/stability verification.
 * AUTH bytes are deliberately never imported from [target]; the locally verified AUTH ledger
 * remains the only state that may be re-signed by this device.
 */
internal object SyncSnapshotRecoveryMergeEngine {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun merge(
        local: SyncSnapshotBundleWire,
        target: SyncSnapshotBundleWire,
        selectedLanes: Set<String>,
        now: Long,
    ): SyncMergedRecoverySnapshot {
        require(local.syncSpaceId == target.syncSpaceId) {
            "REBASE_UNSAFE: cannot merge Snapshots from different Sync Spaces"
        }
        val localByLane = local.shards.associateBy(SyncSnapshotShardWire::replicationLaneId)
        val targetByLane = target.shards.associateBy(SyncSnapshotShardWire::replicationLaneId)
        val shards =
            selectedLanes.toSortedSet().map { lane ->
                val localShard =
                    localByLane[lane]
                        ?: error("REBASE_UNSAFE: local recovery Snapshot is missing lane $lane")
                val targetShard =
                    targetByLane[lane]
                        ?: error("REBASE_UNSAFE: target Snapshot is missing lane $lane")
                mergeShard(
                    lane = lane,
                    local = localShard,
                    target = targetShard,
                    localBaselineId = local.genesisBaselineId ?: "local-recovery-baseline",
                    targetBaselineId = target.genesisBaselineId ?: "remote-recovery-baseline",
                    localFrontier = local.coverage[lane].orEmpty(),
                    targetFrontier = target.coverage[lane].orEmpty(),
                    now = now,
                )
            }
        return SyncMergedRecoverySnapshot(
            shards = shards,
            coverage = SyncSnapshotWireCodec.coverageFromShards(shards),
        )
    }

    private fun mergeShard(
        lane: String,
        local: SyncSnapshotShardWire,
        target: SyncSnapshotShardWire,
        localBaselineId: String,
        targetBaselineId: String,
        localFrontier: Map<String, Long>,
        targetFrontier: Map<String, Long>,
        now: Long,
    ): SyncSnapshotShardWire {
        val generations =
            mergeGenerationMaps(
                generationMap(local),
                generationMap(target),
            ).toMutableMap()
        val tombstones =
            mergeTombstones(
                tombstones(local, now),
                tombstones(target, now),
            )

        val entities: List<RecoveryMergeEntity>
        val fieldVersions: List<GenesisFieldVersionSnapshot>
        when (lane) {
            SyncReplicationLane.AUTH.wireName -> {
                // The remote AUTH shard has not been materialized into the verified local ledger
                // when LOCAL_RECOVERY_REQUIRED is raised. Never launder it through a new signature.
                entities = normalizeEntities(local, lane)
                fieldVersions = emptyList()
            }

            SyncReplicationLane.CORE_META.wireName -> {
                // CORE_META contains installation-local capture metadata. Alias edges are merged
                // separately through causal metadata; local device identity must remain local.
                entities = normalizeEntities(local, lane)
                fieldVersions =
                    normalizeFieldVersions(
                        shard = local,
                        entities = entities,
                        baselineId = localBaselineId,
                        lane = lane,
                    )
            }

            else -> {
                val localEntities = normalizeEntities(local, lane)
                val targetEntities = normalizeEntities(target, lane)
                val merged =
                    mergeBusinessState(
                        lane = lane,
                        localEntities = localEntities,
                        targetEntities = targetEntities,
                        localVersions =
                            normalizeFieldVersions(
                                local,
                                localEntities,
                                localBaselineId,
                                lane,
                            ),
                        targetVersions =
                            normalizeFieldVersions(
                                target,
                                targetEntities,
                                targetBaselineId,
                                lane,
                            ),
                        localBaselineId = localBaselineId,
                        targetBaselineId = targetBaselineId,
                        tombstones = tombstones,
                    )
                entities = merged.first
                fieldVersions = merged.second
            }
        }

        entities.forEach { entity ->
            val key = entityKey(entity.entityType, entity.entitySyncId)
            generations[key] = maxOf(generations[key] ?: 0L, entity.generation)
        }
        tombstones.forEach { tombstone ->
            val key = entityKey(tombstone.entityType, tombstone.entitySyncId)
            generations[key] = maxOf(generations[key] ?: 0L, tombstone.generation)
        }

        val entityStateJson =
            canonical(
                buildJsonObject {
                    put("schemaVersion", 1)
                    put("lane", lane)
                    put(
                        "entities",
                        buildJsonArray {
                            entities.sortedWith(entityComparator).forEach { entity ->
                                add(
                                    buildJsonObject {
                                        put("entityType", entity.entityType)
                                        put("entitySyncId", entity.entitySyncId)
                                        put("generation", entity.generation)
                                        put("fields", entity.fields)
                                    }
                                )
                            }
                        },
                    )
                }
            )
        val fieldVersionStateJson =
            canonical(
                json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        GenesisFieldVersionSnapshot.serializer()
                    ),
                    fieldVersions.sortedWith(fieldVersionComparator),
                )
            )
        val frontierJson =
            SyncGenesisCodec.encodeFrontiers(
                mapOf(lane to mergeActorFrontiers(localFrontier, targetFrontier))
            )
        val causalMetadataJson = mergeCausalMetadata(local, target, now)
        val genesisCoverageJson =
            canonical(
                buildJsonObject {
                    put("schemaVersion", 2)
                    put(
                        "genesisBaselines",
                        buildJsonArray {
                            setOf(localBaselineId, targetBaselineId)
                                .toSortedSet()
                                .forEach { add(JsonPrimitive(it)) }
                        },
                    )
                    put("entityCount", entities.size)
                }
            )
        val deletionSummaryJson =
            canonical(
                buildJsonArray {
                    tombstones.sortedWith(tombstoneComparator).forEach { tombstone ->
                        add(
                            buildJsonObject {
                                put("entityType", tombstone.entityType)
                                put("entitySyncId", tombstone.entitySyncId)
                                put("generation", tombstone.generation)
                                put("versionToken", tombstone.versionToken)
                                put("deletedAt", tombstone.deletedAt)
                            }
                        )
                    }
                }
            )
        val generationSummaryJson =
            canonical(
                JsonObject(
                    generations.toSortedMap().mapValues { (_, value) -> JsonPrimitive(value) }
                )
            )
        val blobIndexes =
            mergeBlobIndexes(
                local = local,
                target = target,
                entities = entities,
                tombstones = tombstones,
            )
        val combinedSummary =
            canonical(
                buildJsonObject {
                    put("schemaVersion", 1)
                    put("deleted", json.parseToJsonElement(deletionSummaryJson))
                    put("generations", json.parseToJsonElement(generationSummaryJson))
                }
            )
        val unsigned =
            SyncSnapshotShardWire(
                replicationLaneId = lane,
                frontierJson = frontierJson,
                entityStateJson = entityStateJson,
                fieldVersionStateJson = fieldVersionStateJson,
                causalMetadataJson = causalMetadataJson,
                genesisCoverageJson = genesisCoverageJson,
                deletionGenerationSummaryJson = combinedSummary,
                contentHash = "",
                deletionSummaryJson = deletionSummaryJson,
                generationSummaryJson = generationSummaryJson,
                blobManifestIndexJson = blobIndexes.first,
                blobReferenceIndexJson = blobIndexes.second,
            )
        return unsigned.copy(contentHash = SyncSnapshotWireCodec.richShardHash(unsigned))
    }

    private fun normalizeEntities(
        shard: SyncSnapshotShardWire,
        lane: String,
    ): List<RecoveryMergeEntity> {
        val root = json.parseToJsonElement(shard.entityStateJson)
        val generations = generationMap(shard)
        fun generic(entity: JsonObject): RecoveryMergeEntity? {
            val entityType = entity.stringValue("entityType") ?: return null
            val entitySyncId = entity.stringValue("entitySyncId") ?: return null
            val key = entityKey(entityType, entitySyncId)
            return RecoveryMergeEntity(
                entityType = entityType,
                entitySyncId = entitySyncId,
                generation =
                    entity.longValue("generation")
                        ?: generations[key]
                        ?: 0L,
                fields = entity["fields"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
        if (root is JsonArray) {
            return root.mapNotNull { (it as? JsonObject)?.let(::generic) }
        }
        val objectRoot = root as? JsonObject ?: return emptyList()
        objectRoot["entities"]?.let { raw ->
            if (raw is JsonArray) {
                return raw.mapNotNull { (it as? JsonObject)?.let(::generic) }
            }
        }
        if (lane == SyncReplicationLane.LIBRARY.wireName) {
            runCatching {
                json.decodeFromString<GenesisLibraryState>(shard.entityStateJson)
            }.getOrNull()?.let { state ->
                return buildList {
                    state.groups.forEach { group ->
                        add(
                            RecoveryMergeEntity(
                                entityType = SyncEntityType.GROUP.wireName,
                                entitySyncId = group.syncId,
                                generation =
                                    generations[
                                        entityKey(SyncEntityType.GROUP.wireName, group.syncId)
                                    ] ?: 0L,
                                fields =
                                    buildJsonObject {
                                        put("name", group.name)
                                    },
                            )
                        )
                    }
                    state.feeds.forEach { feed ->
                        add(
                            RecoveryMergeEntity(
                                entityType = SyncEntityType.FEED.wireName,
                                entitySyncId = feed.syncId,
                                generation =
                                    generations[
                                        entityKey(SyncEntityType.FEED.wireName, feed.syncId)
                                    ] ?: 0L,
                                fields =
                                    buildJsonObject {
                                        put("groupSyncId", feed.groupSyncId)
                                        feed.groupGeneration?.let { put("groupGeneration", it) }
                                        put("name", feed.name)
                                        feed.icon?.let { put("icon", it) } ?: put("icon", JsonNull)
                                        put("url", feed.url)
                                        put("sourceType", feed.sourceType.lowercase())
                                        put("isNotification", feed.isNotification)
                                        put("isFullContent", feed.isFullContent)
                                        put("isBrowser", feed.isBrowser)
                                    },
                            )
                        )
                    }
                }
            }
        }
        if (lane == SyncReplicationLane.ARTICLE_STATE.wireName) {
            runCatching {
                json.decodeFromString<GenesisArticleState>(shard.entityStateJson)
            }.getOrNull()?.let { state ->
                return state.articles.map { article ->
                    RecoveryMergeEntity(
                        entityType = SyncEntityType.ARTICLE.wireName,
                        entitySyncId = article.syncId,
                        generation =
                            generations[
                                entityKey(SyncEntityType.ARTICLE.wireName, article.syncId)
                            ] ?: 0L,
                        fields =
                            buildJsonObject {
                                put("feedSyncId", article.feedSyncId)
                                article.feedGeneration?.let { put("feedGeneration", it) }
                                put("title", article.title)
                                put("url", article.link)
                                article.author?.let { put("author", it) } ?: put("author", JsonNull)
                                put("publishedAt", article.date)
                                put("description", article.description)
                                put("contentHtml", article.contentHtml)
                                article.imageUrl?.let { put("imageUrl", it) }
                                    ?: put("imageUrl", JsonNull)
                                put("isUnread", article.isUnread)
                                put("isStarred", article.isStarred)
                                put("isReadLater", article.isReadLater)
                                article.fullContentHash?.let { put("fullContentHash", it) }
                            },
                    )
                }
            }
        }
        if (lane == SyncReplicationLane.CONFIG.wireName) {
            val rules = objectRoot["rules"] as? JsonArray
            if (rules != null) {
                return rules.mapIndexed { index, element ->
                    val row = element as? JsonObject ?: JsonObject(emptyMap())
                    val entitySyncId = row.stringValue("id") ?: "legacy-filter-rule-$index"
                    RecoveryMergeEntity(
                        entityType = SyncEntityType.FILTER_RULE.wireName,
                        entitySyncId = entitySyncId,
                        generation =
                            generations[
                                entityKey(SyncEntityType.FILTER_RULE.wireName, entitySyncId)
                            ] ?: 0L,
                        fields = row,
                    )
                }
            }
        }
        return emptyList()
    }

    private fun normalizeFieldVersions(
        shard: SyncSnapshotShardWire,
        entities: List<RecoveryMergeEntity>,
        baselineId: String,
        lane: String,
    ): List<GenesisFieldVersionSnapshot> {
        if (shard.fieldVersionStateJson.isBlank() || shard.fieldVersionStateJson == "{}") {
            return synthesizeMissingFieldVersions(emptyList(), entities, baselineId, lane)
        }
        val byIdentity = entities.associateBy { entityKey(it.entityType, it.entitySyncId) }
        val bySyncId = entities.groupBy(RecoveryMergeEntity::entitySyncId)
        fun resolveEntity(entityType: String?, entitySyncId: String): RecoveryMergeEntity? {
            if (entityType != null) return byIdentity[entityKey(entityType, entitySyncId)]
            val candidates = bySyncId[entitySyncId].orEmpty()
            require(candidates.size <= 1) {
                "Snapshot field-version entity type is ambiguous for $entitySyncId"
            }
            return candidates.singleOrNull()
        }
        val result = mutableListOf<GenesisFieldVersionSnapshot>()
        fun append(
            entityType: String?,
            entitySyncId: String,
            generation: Long?,
            fieldId: String,
            versionToken: String,
            rawValueJson: String?,
            causalContextJson: String?,
            logicalClock: Long?,
        ) {
            val entity = resolveEntity(entityType, entitySyncId) ?: return
            val effectiveGeneration = generation ?: entity.generation
            require(effectiveGeneration == entity.generation) {
                "Snapshot field-version generation does not match entity state"
            }
            val entityValue = entityValueForVersion(entity, fieldId)
            val valueJson =
                rawValueJson?.let(::canonicalValueJson)
                    ?: canonical(entityValue ?: JsonNull)
            SyncVersionToken.source(versionToken)
            require(logicalClock == null || logicalClock >= 0L) {
                "Snapshot field-version logicalClock is invalid"
            }
            result +=
                GenesisFieldVersionSnapshot(
                    entitySyncId = entity.entitySyncId,
                    fieldId = fieldId,
                    valueJson = valueJson,
                    versionToken = versionToken,
                    entityType = entity.entityType,
                    entityGeneration = effectiveGeneration,
                    causalContextJson = causalContextJson,
                    logicalClock = logicalClock,
                )
        }

        runCatching {
            json.decodeFromString<List<GenesisFieldVersionSnapshot>>(
                shard.fieldVersionStateJson
            )
        }.getOrNull()?.let { rows ->
            rows.forEach { row ->
                append(
                    entityType = row.entityType,
                    entitySyncId = row.entitySyncId,
                    generation = row.entityGeneration,
                    fieldId = row.fieldId,
                    versionToken = row.versionToken,
                    rawValueJson = row.valueJson,
                    causalContextJson = row.causalContextJson,
                    logicalClock = row.logicalClock,
                )
            }
            return synthesizeMissingFieldVersions(result, entities, baselineId, lane)
        }

        val root = json.parseToJsonElement(shard.fieldVersionStateJson) as? JsonObject
            ?: return synthesizeMissingFieldVersions(result, entities, baselineId, lane)
        val fieldsRoot = root["fields"] as? JsonObject ?: root
        fieldsRoot.forEach entityLoop@{ (entityKey, rawFields) ->
            val fields = rawFields as? JsonObject ?: return@entityLoop
            val separator = entityKey.indexOf(':')
            if (separator <= 0 || separator == entityKey.lastIndex) return@entityLoop
            val entityType = entityKey.substring(0, separator)
            val entitySyncId = entityKey.substring(separator + 1)
            val entity = resolveEntity(entityType, entitySyncId) ?: return@entityLoop
            fields.forEach fieldLoop@{ (fieldId, tokenElement) ->
                val token = (tokenElement as? JsonPrimitive)?.contentOrNull ?: return@fieldLoop
                append(
                    entityType = entityType,
                    entitySyncId = entitySyncId,
                    generation = entity.generation,
                    fieldId = fieldId,
                    versionToken = token,
                    rawValueJson = null,
                    causalContextJson = null,
                    logicalClock = null,
                )
            }
        }
        return synthesizeMissingFieldVersions(result, entities, baselineId, lane)
    }

    private fun synthesizeMissingFieldVersions(
        current: List<GenesisFieldVersionSnapshot>,
        entities: List<RecoveryMergeEntity>,
        baselineId: String,
        lane: String,
    ): List<GenesisFieldVersionSnapshot> {
        val result = current.toMutableList()
        val existing =
            current.mapTo(mutableSetOf()) {
                versionKey(
                    checkNotNull(it.entityType),
                    it.entitySyncId,
                    checkNotNull(it.entityGeneration),
                    it.fieldId,
                )
            }
        entities.forEach { entity ->
            entity.fields.forEach { (entityField, value) ->
                val fieldId =
                    if (
                        entity.entityType == SyncEntityType.ARTICLE.wireName &&
                        entityField == "fullContentHash"
                    ) {
                        SYNC_ARTICLE_FULL_CONTENT_FIELD
                    } else {
                        entityField
                    }
                val key =
                    versionKey(
                        entity.entityType,
                        entity.entitySyncId,
                        entity.generation,
                        fieldId,
                    )
                if (key !in existing) {
                    result +=
                        GenesisFieldVersionSnapshot(
                            entitySyncId = entity.entitySyncId,
                            fieldId = fieldId,
                            valueJson = canonical(value),
                            versionToken =
                                SyncVersionToken.genesis(
                                    baselineId,
                                    lane,
                                    entity.entitySyncId,
                                    fieldId,
                                ),
                            entityType = entity.entityType,
                            entityGeneration = entity.generation,
                        )
                }
            }
        }
        return result.sortedWith(fieldVersionComparator)
    }

    private fun mergeBusinessState(
        lane: String,
        localEntities: List<RecoveryMergeEntity>,
        targetEntities: List<RecoveryMergeEntity>,
        localVersions: List<GenesisFieldVersionSnapshot>,
        targetVersions: List<GenesisFieldVersionSnapshot>,
        localBaselineId: String,
        targetBaselineId: String,
        tombstones: List<RecoveryMergeTombstone>,
    ): Pair<List<RecoveryMergeEntity>, List<GenesisFieldVersionSnapshot>> {
        val localEntityMap = localEntities.associateBy { entityKey(it.entityType, it.entitySyncId) }
        val targetEntityMap = targetEntities.associateBy { entityKey(it.entityType, it.entitySyncId) }
        val tombstoneMap = tombstones.associateBy { entityKey(it.entityType, it.entitySyncId) }
        val localVersionMap =
            localVersions.associateBy {
                versionKey(
                    checkNotNull(it.entityType),
                    it.entitySyncId,
                    checkNotNull(it.entityGeneration),
                    it.fieldId,
                )
            }
        val targetVersionMap =
            targetVersions.associateBy {
                versionKey(
                    checkNotNull(it.entityType),
                    it.entitySyncId,
                    checkNotNull(it.entityGeneration),
                    it.fieldId,
                )
            }
        val outputEntities = mutableListOf<RecoveryMergeEntity>()
        val outputVersions = mutableListOf<GenesisFieldVersionSnapshot>()

        fun versionFor(
            localSide: Boolean,
            entity: RecoveryMergeEntity,
            fieldId: String,
            value: JsonElement,
        ): GenesisFieldVersionSnapshot {
            val map = if (localSide) localVersionMap else targetVersionMap
            map[
                versionKey(
                    entity.entityType,
                    entity.entitySyncId,
                    entity.generation,
                    fieldId,
                )
            ]?.let { return it }
            return GenesisFieldVersionSnapshot(
                entitySyncId = entity.entitySyncId,
                fieldId = fieldId,
                valueJson = canonical(value),
                versionToken =
                    SyncVersionToken.genesis(
                        if (localSide) localBaselineId else targetBaselineId,
                        lane,
                        entity.entitySyncId,
                        fieldId,
                    ),
                entityType = entity.entityType,
                entityGeneration = entity.generation,
            )
        }

        fun copyEntity(localSide: Boolean, entity: RecoveryMergeEntity) {
            outputEntities += entity
            entity.fields.forEach { (entityField, value) ->
                val fieldId =
                    if (
                        entity.entityType == SyncEntityType.ARTICLE.wireName &&
                        entityField == "fullContentHash"
                    ) {
                        SYNC_ARTICLE_FULL_CONTENT_FIELD
                    } else {
                        entityField
                    }
                outputVersions += versionFor(localSide, entity, fieldId, value)
            }
        }

        (localEntityMap.keys + targetEntityMap.keys).toSortedSet().forEach entityLoop@{ key ->
            val localEntity = localEntityMap[key]
            val targetEntity = targetEntityMap[key]
            val maxGeneration =
                maxOf(localEntity?.generation ?: -1L, targetEntity?.generation ?: -1L)
            val tombstone = tombstoneMap[key]
            if (tombstone != null && tombstone.generation >= maxGeneration) return@entityLoop

            when {
                targetEntity == null -> localEntity?.let { copyEntity(true, it) }
                localEntity == null -> copyEntity(false, targetEntity)
                localEntity.generation > targetEntity.generation -> copyEntity(true, localEntity)
                targetEntity.generation > localEntity.generation -> copyEntity(false, targetEntity)
                else -> {
                    val fields =
                        (
                            localEntity.fields.keys +
                                targetEntity.fields.keys +
                                localVersions.filter {
                                    it.entityType == localEntity.entityType &&
                                        it.entitySyncId == localEntity.entitySyncId &&
                                        it.entityGeneration == localEntity.generation
                                }.map(::entityFieldName) +
                                targetVersions.filter {
                                    it.entityType == targetEntity.entityType &&
                                        it.entitySyncId == targetEntity.entitySyncId &&
                                        it.entityGeneration == targetEntity.generation
                                }.map(::entityFieldName)
                            ).toSortedSet()
                    val mergedFields = linkedMapOf<String, JsonElement>()
                    val winnerRows = linkedMapOf<String, GenesisFieldVersionSnapshot>()
                    fields.forEach fieldLoop@{ entityField ->
                        val fieldId =
                            if (
                                localEntity.entityType == SyncEntityType.ARTICLE.wireName &&
                                entityField == "fullContentHash"
                            ) {
                                SYNC_ARTICLE_FULL_CONTENT_FIELD
                            } else {
                                entityField
                            }
                        val localRow =
                            localVersionMap[
                                versionKey(
                                    localEntity.entityType,
                                    localEntity.entitySyncId,
                                    localEntity.generation,
                                    fieldId,
                                )
                            ]
                        val targetRow =
                            targetVersionMap[
                                versionKey(
                                    targetEntity.entityType,
                                    targetEntity.entitySyncId,
                                    targetEntity.generation,
                                    fieldId,
                                )
                            ]
                        val candidates = mutableListOf<Pair<GenesisFieldVersionSnapshot, SyncFieldCandidate>>()
                        (localVersions + targetVersions).filter { row ->
                            row.entityType == localEntity.entityType && row.entitySyncId == localEntity.entitySyncId &&
                                row.entityGeneration == localEntity.generation && row.fieldId == fieldId
                        }.forEach { row -> candidates += row to candidate(row) }
                        localEntity.fields[entityField]?.let { value ->
                            val row = localRow ?: versionFor(true, localEntity, fieldId, value)
                            candidates += row to candidate(row)
                        }
                        if (localRow != null && candidates.none { it.first.versionToken == localRow.versionToken }) {
                            candidates += localRow to candidate(localRow)
                        }
                        targetEntity.fields[entityField]?.let { value ->
                            val row = targetRow ?: versionFor(false, targetEntity, fieldId, value)
                            candidates += row to candidate(row)
                        }
                        if (targetRow != null && candidates.none { it.first.versionToken == targetRow.versionToken }) {
                            candidates += targetRow to candidate(targetRow)
                        }
                        if (candidates.isEmpty()) return@fieldLoop
                        assertMergeCompleteOperationCandidates(candidates.map { it.first }, lane)
                        val winner =
                            SyncVersionResolver.resolve(
                                candidates.map { it.second },
                                policyFor(fieldId),
                            )
                        val winnerRow =
                            candidates
                                .filter { it.first.versionToken == winner.token }
                                .maxWithOrNull(
                                    compareBy<Pair<GenesisFieldVersionSnapshot, SyncFieldCandidate>> {
                                        if (it.first.causalContextJson != null) 1 else 0
                                    }.thenBy { it.first.logicalClock ?: 0L }
                                )
                                ?.first
                                ?: error("Snapshot field winner metadata is unavailable")
                        mergedFields[entityField] = json.parseToJsonElement(winner.valueJson)
                        winnerRows[entityField] = winnerRow
                        outputVersions += candidates.map { it.first }.distinctBy { it.versionToken }
                    }
                    fun pinRelationGeneration(
                        idField: String,
                        generationField: String,
                    ) {
                        val idWinner = winnerRows[idField] ?: return
                        val paired =
                            outputVersions
                                .filter {
                                    it.entityType == localEntity.entityType &&
                                        it.entitySyncId == localEntity.entitySyncId &&
                                        it.entityGeneration == localEntity.generation &&
                                        it.fieldId == generationField &&
                                        sameRelationVersionOrigin(
                                            it.versionToken,
                                            idWinner.versionToken,
                                        )
                                }
                                .maxWithOrNull(
                                    compareBy<GenesisFieldVersionSnapshot> {
                                        if (it.causalContextJson != null) 1 else 0
                                    }.thenBy { it.logicalClock ?: 0L }
                                )
                        mergedFields[generationField] =
                            paired?.valueJson
                                ?.let(json::parseToJsonElement)
                                ?: JsonNull
                    }
                    when (localEntity.entityType) {
                        SyncEntityType.FEED.wireName ->
                            pinRelationGeneration("groupSyncId", "groupGeneration")
                        SyncEntityType.ARTICLE.wireName ->
                            pinRelationGeneration("feedSyncId", "feedGeneration")
                        SyncEntityType.FILTER_RULE.wireName ->
                            pinRelationGeneration("feedSyncId", "feedGeneration")
                    }
                    outputEntities +=
                        RecoveryMergeEntity(
                            entityType = localEntity.entityType,
                            entitySyncId = localEntity.entitySyncId,
                            generation = localEntity.generation,
                            fields = JsonObject(mergedFields),
                        )
                }
            }
        }
        return outputEntities.sortedWith(entityComparator) to
            outputVersions.distinctBy {
                versionKey(
                    checkNotNull(it.entityType),
                    it.entitySyncId,
                    checkNotNull(it.entityGeneration),
                    it.fieldId,
                ) + "|" + it.versionToken
            }.sortedWith(fieldVersionComparator)
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

    private fun assertMergeCompleteOperationCandidates(
        rows: List<GenesisFieldVersionSnapshot>,
        lane: String,
    ) {
        val operationRows =
            rows
                .distinctBy(GenesisFieldVersionSnapshot::versionToken)
                .filter {
                    SyncVersionToken.source(it.versionToken) == SyncVersionSource.OPERATION
                }
        operationRows.forEach { row ->
            val dot =
                SyncVersionToken.parseOperationDot(row.versionToken)
                    ?: throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: Snapshot Operation VersionToken is malformed"
                    )
            if (dot.replicationLaneId != lane) {
                throw SyncRebaseUnsafeException(
                    "REBASE_UNSAFE: Snapshot field winner belongs to another replication lane"
                )
            }
        }
        for (leftIndex in operationRows.indices) {
            for (rightIndex in leftIndex + 1 until operationRows.size) {
                val left = operationRows[leftIndex]
                val right = operationRows[rightIndex]
                val leftDot = checkNotNull(SyncVersionToken.parseOperationDot(left.versionToken))
                val rightDot = checkNotNull(SyncVersionToken.parseOperationDot(right.versionToken))
                if (
                    leftDot.actorIncarnationId == rightDot.actorIncarnationId &&
                    leftDot.replicationLaneId == rightDot.replicationLaneId
                ) {
                    continue
                }
                val leftCandidate = candidate(left)
                val rightCandidate = candidate(right)
                if (
                    SyncVersionToken.happensBefore(leftCandidate, rightCandidate) ||
                    SyncVersionToken.happensBefore(rightCandidate, leftCandidate)
                ) {
                    continue
                }
                if (
                    left.causalContextJson == null ||
                    right.causalContextJson == null ||
                    left.logicalClock == null ||
                    right.logicalClock == null
                ) {
                    throw SyncRebaseUnsafeException(
                        "REBASE_UNSAFE: incomparable legacy Snapshot Operation winners lack merge-complete causal metadata"
                    )
                }
            }
        }
    }

    private fun candidate(row: GenesisFieldVersionSnapshot): SyncFieldCandidate =
        SyncFieldCandidate(
            fieldId = row.fieldId,
            valueJson = row.valueJson,
            token = row.versionToken,
            source = SyncVersionToken.source(row.versionToken),
            causalContextJson = row.causalContextJson,
            logicalClock = row.logicalClock ?: 0L,
        )

    private fun policyFor(fieldId: String): SyncGenesisMergePolicy =
        when (fieldId) {
            "isUnread" -> SyncGenesisMergePolicy.READ_WINS
            "isStarred" -> SyncGenesisMergePolicy.STARRED_WINS
            else -> SyncGenesisMergePolicy.DETERMINISTIC
        }

    private fun generationMap(shard: SyncSnapshotShardWire): Map<String, Long> {
        fun parse(value: String?): JsonObject? =
            value?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val direct = parse(shard.generationSummaryJson)
        val combined = parse(shard.deletionGenerationSummaryJson)
        val root = direct?.takeIf { it.isNotEmpty() } ?: (combined?.get("generations") as? JsonObject)
        return root.orEmpty().mapNotNull { (key, value) ->
            value.jsonPrimitive.longOrNull?.takeIf { it >= 0L }?.let { key to it }
        }.toMap()
    }

    private fun mergeGenerationMaps(
        left: Map<String, Long>,
        right: Map<String, Long>,
    ): Map<String, Long> =
        (left.keys + right.keys).associateWith { key ->
            maxOf(left[key] ?: 0L, right[key] ?: 0L)
        }

    private fun tombstones(
        shard: SyncSnapshotShardWire,
        now: Long,
    ): List<RecoveryMergeTombstone> {
        val generationMap = generationMap(shard)
        val root =
            shard.deletionSummaryJson
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                ?: runCatching {
                    json.parseToJsonElement(shard.deletionGenerationSummaryJson)
                }.getOrNull()
        val rows =
            when (root) {
                is JsonArray -> root
                is JsonObject -> root["deleted"] as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
        return rows.map { element ->
            val row = element.jsonObject
            val entityType =
                row.stringValue("entityType")
                    ?: error("Snapshot tombstone entityType is missing")
            val entitySyncId =
                row.stringValue("entitySyncId")
                    ?: error("Snapshot tombstone entitySyncId is missing")
            val generation =
                row.longValue("generation")
                    ?: generationMap[entityKey(entityType, entitySyncId)]?.coerceAtLeast(1L)
                    ?: 1L
            RecoveryMergeTombstone(
                entityType = entityType,
                entitySyncId = entitySyncId,
                generation = generation,
                versionToken =
                    row.stringValue("versionToken")
                        ?: "TOMBSTONE|MERGE|$entityType|$entitySyncId|$generation",
                deletedAt = row.longValue("deletedAt") ?: now,
            )
        }
    }

    private fun mergeTombstones(
        left: List<RecoveryMergeTombstone>,
        right: List<RecoveryMergeTombstone>,
    ): List<RecoveryMergeTombstone> {
        val result = linkedMapOf<String, RecoveryMergeTombstone>()
        (left + right).forEach { candidate ->
            val key = entityKey(candidate.entityType, candidate.entitySyncId)
            val current = result[key]
            if (
                current == null ||
                candidate.generation > current.generation ||
                (
                    candidate.generation == current.generation &&
                        (
                            candidate.versionToken > current.versionToken ||
                                (
                                    candidate.versionToken == current.versionToken &&
                                        candidate.deletedAt > current.deletedAt
                                    )
                            )
                    )
            ) {
                result[key] = candidate
            }
        }
        return result.values.sortedWith(tombstoneComparator)
    }

    private fun mergeActorFrontiers(
        left: Map<String, Long>,
        right: Map<String, Long>,
    ): Map<String, Long> =
        (left.keys + right.keys).toSortedSet().mapNotNull { actor ->
            maxOf(left[actor] ?: 0L, right[actor] ?: 0L)
                .takeIf { it > 0L }
                ?.let { actor to it }
        }.toMap()

    private fun mergeCausalMetadata(
        left: SyncSnapshotShardWire,
        right: SyncSnapshotShardWire,
        now: Long,
    ): String {
        fun root(value: String): JsonObject =
            runCatching { json.parseToJsonElement(value) as? JsonObject }
                .getOrNull()
                ?: JsonObject(emptyMap())
        val a = root(left.causalMetadataJson)
        val b = root(right.causalMetadataJson)
        val aliases =
            sequenceOf(a, b)
                .flatMap { root -> (root["aliasEdges"] as? JsonArray)?.asSequence() ?: emptySequence() }
                .associateBy(::canonical)
                .toSortedMap()
                .values
                .toList()
        val observed = linkedMapOf<String, MutableSet<String>>()
        sequenceOf(a, b).forEach rootLoop@{ root ->
            val byLane =
                root["observedGenesisBaselinesByLane"] as? JsonObject
                    ?: return@rootLoop
            byLane.forEach laneLoop@{ (lane, raw) ->
                val values = raw as? JsonArray ?: return@laneLoop
                val target = observed.getOrPut(lane) { sortedSetOf() }
                values.forEach { element ->
                    (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                        ?.let(target::add)
                }
            }
        }
        return canonical(
            buildJsonObject {
                put("schemaVersion", 2)
                put(
                    "crossDbCutId",
                    a.stringValue("crossDbCutId")
                        ?: b.stringValue("crossDbCutId")
                        ?: "",
                )
                put("capturedAt", now)
                put("aliasEdges", JsonArray(aliases))
                put(
                    "observedGenesisBaselinesByLane",
                    JsonObject(
                        observed.toSortedMap().mapValues { (_, values) ->
                            JsonArray(values.toSortedSet().map(::JsonPrimitive))
                        }
                    ),
                )
            }
        )
    }

    private fun mergeBlobIndexes(
        local: SyncSnapshotShardWire,
        target: SyncSnapshotShardWire,
        entities: List<RecoveryMergeEntity>,
        tombstones: List<RecoveryMergeTombstone>,
    ): Pair<String, String> {
        fun array(value: String?): JsonArray =
            value?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
                ?: JsonArray(emptyList())

        val manifests = linkedMapOf<String, JsonObject>()
        (array(local.blobManifestIndexJson) + array(target.blobManifestIndexJson)).forEach { element ->
            val row = element as? JsonObject ?: error("Snapshot Blob manifest is malformed")
            val hash = row.stringValue("hash") ?: error("Snapshot Blob manifest hash is missing")
            val previous = manifests[hash]
            if (previous != null) {
                val leftComparable = JsonObject(previous.filterKeys { it != "referenceCount" })
                val rightComparable = JsonObject(row.filterKeys { it != "referenceCount" })
                require(canonical(leftComparable) == canonical(rightComparable)) {
                    "Snapshot Blob manifest collision for hash $hash"
                }
            } else {
                manifests[hash] = row
            }
        }
        val surviving =
            entities.associate {
                entityKey(it.entityType, it.entitySyncId) to it.generation
            }
        val deleted =
            tombstones.associate {
                entityKey(it.entityType, it.entitySyncId) to it.generation
            }
        val references = linkedMapOf<String, JsonObject>()
        (array(local.blobReferenceIndexJson) + array(target.blobReferenceIndexJson)).forEach refLoop@{ element ->
            val row = element as? JsonObject ?: error("Snapshot Blob reference is malformed")
            val lane = row.stringValue("replicationLaneId") ?: error("Blob lane is missing")
            val ownerType = row.stringValue("ownerEntityType") ?: error("Blob owner type is missing")
            val ownerSyncId = row.stringValue("ownerEntitySyncId") ?: error("Blob owner id is missing")
            val ownerGeneration =
                row.longValue("ownerEntityGeneration")
                    ?: error("Blob owner generation is missing")
            val referenceKind = row.stringValue("referenceKind") ?: error("Blob reference kind is missing")
            val hash = row.stringValue("hash") ?: error("Blob hash is missing")
            val entityKey = entityKey(ownerType, ownerSyncId)
            val currentGeneration = surviving[entityKey] ?: return@refLoop
            if (currentGeneration != ownerGeneration) return@refLoop
            if ((deleted[entityKey] ?: -1L) >= currentGeneration) return@refLoop
            val key =
                listOf(
                    lane,
                    ownerType,
                    ownerSyncId,
                    ownerGeneration.toString(),
                    referenceKind,
                    hash,
                ).joinToString("\u0000")
            references[key] = row
        }
        val sortedReferences = references.toSortedMap().values.toList()
        val counts = sortedReferences.groupingBy { checkNotNull(it.stringValue("hash")) }.eachCount()
        val sortedManifests =
            counts.keys.sorted().map { hash ->
                val manifest = manifests[hash]
                    ?: error("Snapshot Blob reference has no manifest: $hash")
                JsonObject(manifest + ("referenceCount" to JsonPrimitive(counts.getValue(hash))))
            }
        return canonical(JsonArray(sortedManifests)) to canonical(JsonArray(sortedReferences))
    }

    private fun entityValueForVersion(
        entity: RecoveryMergeEntity,
        fieldId: String,
    ): JsonElement? =
        if (
            entity.entityType == SyncEntityType.ARTICLE.wireName &&
            fieldId == SYNC_ARTICLE_FULL_CONTENT_FIELD
        ) {
            entity.fields["fullContentHash"]
        } else {
            entity.fields[fieldId]
        }

    private fun entityFieldName(row: GenesisFieldVersionSnapshot): String =
        if (
            row.entityType == SyncEntityType.ARTICLE.wireName &&
            row.fieldId == SYNC_ARTICLE_FULL_CONTENT_FIELD
        ) {
            "fullContentHash"
        } else {
            row.fieldId
        }

    private fun canonicalValueJson(value: String): String =
        canonical(json.parseToJsonElement(value))

    private fun canonical(value: JsonElement): String =
        SyncOperationCanonicalizer.canonicalJson(value.toString())

    private fun entityKey(entityType: String, entitySyncId: String): String =
        "$entityType:$entitySyncId"

    private fun versionKey(
        entityType: String,
        entitySyncId: String,
        generation: Long,
        fieldId: String,
    ): String =
        listOf(entityType, entitySyncId, generation.toString(), fieldId)
            .joinToString("\u0000")

    private fun JsonObject.stringValue(name: String): String? =
        this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.longValue(name: String): Long? =
        this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

    private val entityComparator =
        compareBy<RecoveryMergeEntity>(
            RecoveryMergeEntity::entityType,
            RecoveryMergeEntity::entitySyncId,
            RecoveryMergeEntity::generation,
        )

    private val fieldVersionComparator =
        compareBy<GenesisFieldVersionSnapshot>(
            { it.entityType.orEmpty() },
            GenesisFieldVersionSnapshot::entitySyncId,
            { it.entityGeneration ?: 0L },
            GenesisFieldVersionSnapshot::fieldId,
        )

    private val tombstoneComparator =
        compareBy<RecoveryMergeTombstone>(
            RecoveryMergeTombstone::entityType,
            RecoveryMergeTombstone::entitySyncId,
            RecoveryMergeTombstone::generation,
            RecoveryMergeTombstone::versionToken,
        )
}
