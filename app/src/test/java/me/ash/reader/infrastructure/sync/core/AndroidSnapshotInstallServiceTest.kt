package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingDao
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteRuleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AndroidSnapshotInstallServiceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun authRoot(syncSpaceId: String): SyncAuthProtocolObject {
        val payloadJson = "{}"
        val payloadHash = SyncOperationCanonicalizer.sha256Hex(payloadJson)
        val withId =
            SyncAuthProtocolObject(
                authObjectId =
                    SyncAuthWireCodec.authObjectId(
                        syncSpaceId = syncSpaceId,
                        authEpoch = 0L,
                        objectType = SyncAuthObjectType.SPACE_ROOT,
                        authorDeviceId = "device-1",
                        payloadHash = payloadHash,
                        authSequence = 0L,
                    ),
                syncSpaceId = syncSpaceId,
                authEpoch = 0L,
                authSequence = 0L,
                objectType = SyncAuthObjectType.SPACE_ROOT,
                authorDeviceId = "device-1",
                ownerDeviceId = "device-1",
                payloadJson = payloadJson,
                payloadHash = payloadHash,
                signingDigest = "",
                authorSignature = "fixture-signature",
            )
        return withId.copy(signingDigest = SyncAuthWireCodec.signingDigest(withId))
    }

    private fun createValidBundleAndShards(
        syncSpaceId: String = "space-1",
        baselineId: String = "baseline-1",
        includeOrphanArticle: Boolean = false,
        includeAi: Boolean = false,
    ): Pair<SyncSnapshotBundleEntity, List<SyncSnapshotShardEntity>> {
        val bundleId = "genesis:v1:$baselineId"
        val now = 1000L

        val groupSnapshot = GenesisGroupSnapshot(syncId = "group-sync-1", name = "Tech")
        val feedSnapshot = GenesisFeedSnapshot(
            syncId = "feed-sync-1",
            groupSyncId = "group-sync-1",
            name = "Hacker News",
            url = "https://news.ycombinator.com/rss",
            sourceType = "RSS",
            isNotification = true,
            isFullContent = false,
            isBrowser = true,
        )
        val articleSnapshot = GenesisArticleSnapshot(
            syncId = "article-sync-1",
            feedSyncId = if (includeOrphanArticle) "non-existent-feed-sync" else "feed-sync-1",
            title = "Cool News",
            author = "author",
            link = "https://news.ycombinator.com/item?id=1",
            date = 1600000000000L,
            isUnread = true,
            isStarred = false,
            isReadLater = false,
        )
        val rssHubSettingsSyncId =
            SyncCanonicalIdentity.configRuleSyncId(
                SyncEntityType.RSSHUB_SETTINGS,
                "rsshub-settings",
            )

        val lanes = listOf(
            SyncReplicationLane.CORE_META,
            SyncReplicationLane.CONFIG,
            SyncReplicationLane.AUTH,
            SyncReplicationLane.LIBRARY,
            SyncReplicationLane.ARTICLE_STATE,
        )

        val shards = (lanes + if (includeAi) listOf(SyncReplicationLane.AI_HISTORY) else emptyList()).map { lane ->
            val frontierJson =
                SyncGenesisCodec.encodeFrontiers(
                    mapOf(lane.wireName to mapOf("actor-1" to 10L)),
                )
            val entityStateJson = when (lane) {
                SyncReplicationLane.CORE_META -> json.encodeToString(
                    GenesisCoreState(genesisBaselineId = baselineId, mappings = emptyList()),
                )
                SyncReplicationLane.CONFIG ->
                    """{"schemaVersion":1,"lane":"CONFIG","entities":[{"entityType":"${SyncEntityType.RSSHUB_SETTINGS.wireName}","entitySyncId":"$rssHubSettingsSyncId","generation":0,"fields":{"settings":{"enabled":true,"instances":[]}}}]}"""
                SyncReplicationLane.AUTH -> {
                    val root = authRoot(syncSpaceId)
                    """{"schemaVersion":1,"lane":"AUTH","entities":[{"entityType":"auth_ledger","entitySyncId":"$syncSpaceId:auth","generation":0,"fields":{"objects":[${json.encodeToString(root)}]}}]}"""
                }
                SyncReplicationLane.LIBRARY -> json.encodeToString(
                    GenesisLibraryState(groups = listOf(groupSnapshot), feeds = listOf(feedSnapshot)),
                )
                SyncReplicationLane.ARTICLE_STATE -> json.encodeToString(
                    GenesisArticleState(articles = listOf(articleSnapshot)),
                )
                SyncReplicationLane.AI_HISTORY -> """{"entities":[{"entityType":"conversation","entitySyncId":"conversation-1","generation":0,"fields":{}}]}"""
                else -> "{}"
            }
            val fieldVersionStateJson = when (lane) {
                SyncReplicationLane.LIBRARY -> json.encodeToString(
                    listOf(
                        GenesisFieldVersionSnapshot(
                            entitySyncId = "group-sync-1",
                            fieldId = "name",
                            valueJson = "\"Tech\"",
                            versionToken = SyncVersionToken.genesis(baselineId, lane.wireName, "group-sync-1", "name"),
                        ),
                    ),
                )
                else -> "[]"
            }
            val causalMetadata = """{"schemaVersion":1,"genesisBaselineId":"$baselineId"}"""
            val genesisCoverageJson = """["$baselineId"]"""
            val material = GenesisShardHashMaterial(
                replicationLaneId = lane.wireName,
                frontierByActorJson = frontierJson,
                entityStateJson = entityStateJson,
                fieldVersionStateJson = fieldVersionStateJson,
                causalMergeMetadataJson = causalMetadata,
                genesisCoverageJson = genesisCoverageJson,
                deletionSummaryJson = "[]",
                generationSummaryJson = "{}",
                blobManifestIndexJson = "[]",
                blobReferenceIndexJson = "[]",
            )
            val shardHash = SyncGenesisCodec.hashCanonicalJson(json.encodeToString(material))
            SyncSnapshotShardEntity(
                snapshotBundleId = bundleId,
                syncSpaceId = syncSpaceId,
                replicationLaneId = lane.wireName,
                frontierByActorJson = frontierJson,
                receivedCoverageSummaryJson = frontierJson,
                entityStateJson = entityStateJson,
                fieldVersionStateJson = fieldVersionStateJson,
                causalMergeMetadataJson = causalMetadata,
                genesisCoverageJson = genesisCoverageJson,
                deletionSummaryJson = "[]",
                generationSummaryJson = "{}",
                blobManifestIndexJson = "[]",
                blobReferenceIndexJson = "[]",
                shardHash = shardHash,
            )
        }

        val descriptors = shards.map {
            GenesisShardDescriptor(it.replicationLaneId, it.shardHash, it.frontierByActorJson)
        }.sortedBy(GenesisShardDescriptor::replicationLaneId)
        val descriptorsJson = json.encodeToString(descriptors)
        val requiredCoreShardIdsJson = SyncGenesisCodec.encodeStringList(listOf("CORE_META", "AUTH"))
        val bundleMaterial = GenesisBundleHashMaterial(
            schemaVersion = 1,
            snapshotEpoch = 1,
            crossDbCutId = "cut-1",
            replicationPolicyHash = "policy-hash",
            requiredCoreShardIdsJson = requiredCoreShardIdsJson,
            shardDescriptorsJson = descriptorsJson,
        )
        val rootHash = SyncGenesisCodec.hashCanonicalJson(json.encodeToString(bundleMaterial))

        val bundle = SyncSnapshotBundleEntity(
            snapshotBundleId = bundleId,
            syncSpaceId = syncSpaceId,
            snapshotClass = "WORKING",
            schemaVersion = 1,
            snapshotEpoch = 1,
            crossDbCutId = "cut-1",
            replicationPolicyHash = "policy-hash",
            requiredCoreShardIdsJson = requiredCoreShardIdsJson,
            shardDescriptorsJson = descriptorsJson,
            rootHash = rootHash,
            createdByDeviceId = "device-1",
            createdAt = now,
        )

        return bundle to shards
    }

    private class DatabaseFixture(val accountId: Int, val syncSpaceId: String) {
        val database: AndroidDatabase = mock(AndroidDatabase::class.java)
        val syncRuntimeDao: SyncRuntimeDao = mock(SyncRuntimeDao::class.java)
        val syncGenesisDao: SyncGenesisDao = mock(SyncGenesisDao::class.java)
        val groupDao: GroupDao = mock(GroupDao::class.java)
        val feedDao: FeedDao = mock(FeedDao::class.java)
        val articleDao: ArticleDao = mock(ArticleDao::class.java)
        val syncIdentityMappingDao: SyncIdentityMappingDao = mock(SyncIdentityMappingDao::class.java)
        val syncAliasDao: SyncAliasDao = mock(SyncAliasDao::class.java)
        val syncInboxDao: SyncInboxDao = mock(SyncInboxDao::class.java)
        val syncOutboxDao: SyncOutboxDao = mock(SyncOutboxDao::class.java)
        val syncOperationDao: SyncOperationDao = mock(SyncOperationDao::class.java)
        val syncBlobDao: SyncBlobDao = mock(SyncBlobDao::class.java)
        val syncAuthLedgerDao: SyncAuthLedgerDao = mock(SyncAuthLedgerDao::class.java)
        val filterRepo: ArticleFilterRepository = mock(ArticleFilterRepository::class.java)
        val websiteRuleRepo: WebsiteRuleRepository = mock(WebsiteRuleRepository::class.java)
        val jsonRuleRepo: JsonRuleRepository = mock(JsonRuleRepository::class.java)
        val rssHubSettingsRepo: RssHubSettingsRepository = mock(RssHubSettingsRepository::class.java)
        val rssHubSubscriptionRepo: RssHubSubscriptionRepository = mock(RssHubSubscriptionRepository::class.java)
        val websiteParsePreferenceRepo: WebsiteParsePreferenceRepository =
            mock(WebsiteParsePreferenceRepository::class.java)

        val insertedGroups = mutableListOf<Group>()
        val insertedFeeds = mutableListOf<Feed>()
        val insertedArticles = mutableListOf<Article>()
        val insertedMappings = mutableListOf<SyncIdentityMappingEntity>()
        val insertedCoverages = mutableListOf<SyncCoverageEntity>()
        val insertedFieldVersions = mutableListOf<SyncFieldVersionEntity>()
        val insertedFieldCandidates = mutableListOf<SyncFieldVersionEntity>()
        val insertedRecoveryCapsules = mutableListOf<SyncRecoveryCapsuleEntity>()

        var binding = SyncLocalSpaceBindingEntity(
            syncSpaceId = syncSpaceId,
            localAccountId = accountId,
            lifecycleState = "JOINING",
            createdAt = 100L,
            updatedAt = 100L,
        )

        init {
            `when`(database.syncRuntimeDao()).thenReturn(syncRuntimeDao)
            `when`(database.syncGenesisDao()).thenReturn(syncGenesisDao)
            `when`(database.syncOutboxDao()).thenReturn(syncOutboxDao)
            `when`(database.syncOperationDao()).thenReturn(syncOperationDao)
            `when`(database.groupDao()).thenReturn(groupDao)
            `when`(database.feedDao()).thenReturn(feedDao)
            `when`(database.articleDao()).thenReturn(articleDao)
            `when`(database.syncIdentityMappingDao()).thenReturn(syncIdentityMappingDao)
            `when`(database.syncAliasDao()).thenReturn(syncAliasDao)
            `when`(database.syncInboxDao()).thenReturn(syncInboxDao)
            `when`(database.syncBlobDao()).thenReturn(syncBlobDao)
            `when`(database.syncAuthLedgerDao()).thenReturn(syncAuthLedgerDao)

            runBlocking {
                val payloadJson = "{}"
                val payloadHash = SyncOperationCanonicalizer.sha256Hex(payloadJson)
                val rootWithId =
                    SyncAuthProtocolObject(
                        authObjectId =
                            SyncAuthWireCodec.authObjectId(
                                syncSpaceId = syncSpaceId,
                                authEpoch = 0L,
                                objectType = SyncAuthObjectType.SPACE_ROOT,
                                authorDeviceId = "device-1",
                                payloadHash = payloadHash,
                                authSequence = 0L,
                            ),
                        syncSpaceId = syncSpaceId,
                        authEpoch = 0L,
                        authSequence = 0L,
                        objectType = SyncAuthObjectType.SPACE_ROOT,
                        authorDeviceId = "device-1",
                        ownerDeviceId = "device-1",
                        payloadJson = payloadJson,
                        payloadHash = payloadHash,
                        signingDigest = "",
                        authorSignature = "fixture-signature",
                    )
                val root = rootWithId.copy(signingDigest = SyncAuthWireCodec.signingDigest(rootWithId))
                `when`(syncAuthLedgerDao.list(syncSpaceId)).thenReturn(
                    listOf(
                        SyncAuthLedgerEntity(
                            authObjectId = root.authObjectId,
                            syncSpaceId = syncSpaceId,
                            authEpoch = root.authEpoch,
                            authObjectJson = Json.encodeToString(root),
                            updatedAt = 1L,
                        )
                    )
                )
                `when`(
                    syncBlobDao.listReferencesForLane(
                        org.mockito.kotlin.eq(syncSpaceId),
                        org.mockito.kotlin.any(),
                    ),
                ).thenReturn(emptyList())
                `when`(syncInboxDao.listCoverage(syncSpaceId)).thenReturn(emptyList())
                `when`(syncRuntimeDao.findBinding(accountId)).thenAnswer { binding }
                `when`(syncRuntimeDao.findBindingBySpace(syncSpaceId)).thenAnswer { binding }
                `when`(syncOutboxDao.listPending(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(emptyList())
                `when`(syncOperationDao.listAllForRecovery(syncSpaceId)).thenReturn(emptyList())
                `when`(groupDao.queryAll(accountId)).thenReturn(emptyList())
                `when`(feedDao.queryAll(accountId)).thenReturn(emptyList())
                `when`(articleDao.queryAllByAccountId(accountId)).thenReturn(emptyList())
                `when`(syncIdentityMappingDao.findByType(syncSpaceId, SyncEntityType.GROUP.wireName)).thenReturn(emptyList())
                `when`(syncIdentityMappingDao.findByType(syncSpaceId, SyncEntityType.FEED.wireName)).thenReturn(emptyList())
                `when`(syncIdentityMappingDao.findByType(syncSpaceId, SyncEntityType.ARTICLE.wireName)).thenReturn(emptyList())
                `when`(
                    syncIdentityMappingDao.findByType(
                        org.mockito.kotlin.eq(syncSpaceId),
                        org.mockito.kotlin.any(),
                    ),
                ).thenAnswer { invocation ->
                    val entityType = invocation.getArgument<String>(1)
                    insertedMappings.filter {
                        it.syncSpaceId == syncSpaceId && it.entityType == entityType
                    }.sortedBy { it.localId }
                }
                `when`(syncInboxDao.listFieldVersions(syncSpaceId)).thenReturn(emptyList())
                `when`(syncInboxDao.listFieldCandidates(syncSpaceId)).thenAnswer {
                    insertedFieldCandidates.toList()
                }
                `when`(syncInboxDao.listRollbackBaselines(syncSpaceId)).thenReturn(emptyList())
                `when`(syncInboxDao.listTombstones(syncSpaceId)).thenReturn(emptyList())
                `when`(syncAliasDao.listEdges(syncSpaceId)).thenReturn(emptyList())
                `when`(filterRepo.exportRules()).thenReturn("{\"schemaVersion\":1,\"rules\":[]}")
                `when`(
                    syncIdentityMappingDao.findBySyncId(
                        org.mockito.kotlin.eq(syncSpaceId),
                        org.mockito.kotlin.any(),
                        org.mockito.kotlin.any(),
                    ),
                ).thenAnswer { invocation ->
                    val entityType = invocation.getArgument<String>(1)
                    val syncId = invocation.getArgument<String>(2)
                    insertedMappings.lastOrNull {
                        it.syncSpaceId == syncSpaceId &&
                            it.entityType == entityType &&
                            it.syncId == syncId
                    }
                }
                `when`(
                    syncIdentityMappingDao.findByLocalId(
                        org.mockito.kotlin.eq(syncSpaceId),
                        org.mockito.kotlin.any(),
                        org.mockito.kotlin.any(),
                    ),
                ).thenAnswer { invocation ->
                    val entityType = invocation.getArgument<String>(1)
                    val localId = invocation.getArgument<String>(2)
                    insertedMappings.lastOrNull {
                        it.syncSpaceId == syncSpaceId &&
                            it.entityType == entityType &&
                            it.localId == localId
                    }
                }
                `when`(groupDao.queryById(org.mockito.kotlin.any())).thenAnswer { invocation ->
                    insertedGroups.lastOrNull { it.id == invocation.getArgument<String>(0) }
                }
                `when`(feedDao.queryById(org.mockito.kotlin.any())).thenAnswer { invocation ->
                    insertedFeeds.lastOrNull { it.id == invocation.getArgument<String>(0) }
                }

                org.mockito.Mockito.doAnswer { invocation ->
                    binding = invocation.getArgument(0)
                    Unit
                }.`when`(syncRuntimeDao).upsertBinding(org.mockito.kotlin.any())

                org.mockito.Mockito.doAnswer { invocation ->
                    insertedGroups.addAll(invocation.getArgument(0))
                    Unit
                }.`when`(groupDao).insertAll(org.mockito.ArgumentMatchers.anyList())

                org.mockito.Mockito.doAnswer { invocation ->
                    insertedFeeds.addAll(invocation.getArgument(0))
                    Unit
                }.`when`(feedDao).insertAll(org.mockito.ArgumentMatchers.anyList())

                org.mockito.Mockito.doAnswer { invocation ->
                    insertedArticles.addAll(invocation.getArgument(0))
                    Unit
                }.`when`(articleDao).insertList(org.mockito.ArgumentMatchers.anyList())

                org.mockito.Mockito.doAnswer { invocation ->
                    insertedMappings.add(invocation.getArgument(0))
                    Unit
                }.`when`(syncIdentityMappingDao).insert(org.mockito.kotlin.any())

                org.mockito.Mockito.doAnswer { invocation ->
                    insertedCoverages.add(invocation.getArgument(0))
                    Unit
                }.`when`(syncInboxDao).upsertCoverage(org.mockito.kotlin.any())

                org.mockito.Mockito.doAnswer { invocation ->
                    insertedFieldVersions.add(invocation.getArgument(0))
                    Unit
                }.`when`(syncInboxDao).upsertFieldVersion(org.mockito.kotlin.any())

                org.mockito.Mockito.doAnswer { invocation ->
                    val candidate = invocation.getArgument<SyncFieldCandidateEntity>(0).version
                    insertedFieldCandidates.removeAll {
                        it.syncSpaceId == candidate.syncSpaceId &&
                            it.entityType == candidate.entityType &&
                            it.entitySyncId == candidate.entitySyncId &&
                            it.entityGeneration == candidate.entityGeneration &&
                            it.fieldId == candidate.fieldId &&
                            it.versionToken == candidate.versionToken
                    }
                    insertedFieldCandidates.add(candidate)
                    Unit
                }.`when`(syncInboxDao).upsertFieldCandidate(org.mockito.kotlin.any())

                `when`(syncGenesisDao.listRecoveryCapsules(syncSpaceId)).thenAnswer { insertedRecoveryCapsules.toList() }
                org.mockito.Mockito.doAnswer { invocation ->
                    val capsule = invocation.getArgument<SyncRecoveryCapsuleEntity>(0)
                    insertedRecoveryCapsules.removeAll { it.capsuleId == capsule.capsuleId }
                    insertedRecoveryCapsules.add(capsule)
                    Unit
                }.`when`(syncGenesisDao).upsertRecoveryCapsule(org.mockito.kotlin.any())
            }
        }

        fun createInstaller(
            signingKeys: SyncDeviceSigningKeyStore = SyncDeviceSigningKeyStore(),
            extensions: Set<SyncBusinessProjectionExtension> = emptySet(),
            applier: AndroidSyncBusinessApplier? = null,
        ): AndroidSnapshotInstallService =
            AndroidSnapshotInstallService(
                database = database,
                filterRepository = filterRepo,
                websiteRuleRepository = websiteRuleRepo,
                jsonRuleRepository = jsonRuleRepo,
                rssHubSettingsRepository = rssHubSettingsRepo,
                rssHubSubscriptionRepository = rssHubSubscriptionRepo,
                websiteParsePreferenceRepository = websiteParsePreferenceRepo,
                signingKeys = signingKeys,
                projectionExtensions = extensions,
                businessApplier = applier,
            )
    }

    @Test
    fun installsSnapshotWithLocalIdIsolationAndCorrectCoverage() = runBlocking {
        val fixture = DatabaseFixture(accountId = 1, syncSpaceId = "space-1")
        val (bundle, shards) = createValidBundleAndShards(syncSpaceId = "space-1")

        val installer = fixture.createInstaller()
        installer.transactionRunner = { it() }
        val result = installer.install(fixture.accountId, bundle, shards, now = 2000L)

        // 1. 验证物化数量与通道
        assertEquals(3, result.materializedEntities) // group, feed, article
        assertTrue(result.rebasedLanes.containsAll(listOf("CORE_META", "LIBRARY", "ARTICLE_STATE")))

        // 2. 验证 Local ID 隔离：分配的本地 ID 是随机 UUID，不是远端 syncId
        assertEquals(1, fixture.insertedGroups.size)
        val group = fixture.insertedGroups.single()
        assertEquals("Tech", group.name)
        assertNotEquals("group-sync-1", group.id)

        assertEquals(1, fixture.insertedFeeds.size)
        val feed = fixture.insertedFeeds.single()
        assertEquals("Hacker News", feed.name)
        assertNotEquals("feed-sync-1", feed.id)
        assertEquals(group.id, feed.groupId) // feed 挂载到了本地生成的全新 group id

        assertEquals(1, fixture.insertedArticles.size)
        val article = fixture.insertedArticles.single()
        assertEquals("Cool News", article.title)
        assertNotEquals("article-sync-1", article.id)
        assertEquals(feed.id, article.feedId) // article 挂载到了本地生成的全新 feed id

        // 3. 验证 Mapping 表写入
        val groupMapping = fixture.insertedMappings.find { it.entityType == SyncEntityType.GROUP.wireName }
        assertNotNull(groupMapping)
        assertEquals(group.id, groupMapping!!.localId)
        assertEquals("group-sync-1", groupMapping.syncId)

        // 4. 验证 Coverage 语义：严禁虚高 retainedPrefix
        assertTrue(fixture.insertedCoverages.isNotEmpty())
        for (coverage in fixture.insertedCoverages) {
            assertEquals(10L, coverage.snapshotPrefix)
            assertEquals(0L, coverage.retainedPrefix) // 绝不虚高！
            assertEquals(10L, coverage.receivedPrefix)
            assertEquals(10L, coverage.appliedPrefix)
        }

        // 5. 验证字段版本恢复
        val groupFieldVersion = fixture.insertedFieldVersions.find { it.fieldId == "name" && it.entitySyncId == "group-sync-1" }
        assertNotNull(groupFieldVersion)
        assertEquals("\"Tech\"", groupFieldVersion!!.valueJson)

        // Snapshot 只是 baseline；Tail 尚未确认前保持 STAGING。
        assertEquals(SyncSpaceLifecycleState.STAGING.name, fixture.binding.lifecycleState)
        `when`(fixture.syncGenesisDao.findBundle(bundle.snapshotBundleId)).thenReturn(bundle)
        `when`(fixture.syncInboxDao.listPending(syncSpaceId = "space-1", limit = 1)).thenReturn(emptyList())
        installer.activateAfterTail(fixture.accountId, bundle.snapshotBundleId, now = 2001L)
        assertEquals(SyncSpaceLifecycleState.ACTIVE.name, fixture.binding.lifecycleState)
    }

    @Test fun completedStagingInstallDoesNotRematerializeConfig(): Unit = runBlocking {
        val fixture = DatabaseFixture(1, "space-1")
        val (bundle, shards) = createValidBundleAndShards()
        val installer = fixture.createInstaller().also { it.transactionRunner = { block -> block() } }
        installer.install(1, bundle, shards, now = 1999, selectedLanes = setOf("CORE_META", "AUTH"))
        `when`(fixture.syncGenesisDao.findBundle(bundle.snapshotBundleId)).thenReturn(bundle)
        org.mockito.Mockito.clearInvocations(fixture.rssHubSettingsRepo)
        installer.install(1, bundle, shards, now = 2000)
        assertTrue(org.mockito.Mockito.mockingDetails(fixture.rssHubSettingsRepo).invocations.isNotEmpty())
        fixture.binding = fixture.binding.copy(lifecycleState = "STAGING")
        `when`(fixture.syncRuntimeDao.findBinding(1)).thenReturn(fixture.binding)
        `when`(fixture.syncGenesisDao.findBundle(bundle.snapshotBundleId)).thenReturn(bundle)
        `when`(fixture.syncGenesisDao.listShards(bundle.snapshotBundleId)).thenReturn(shards)
        `when`(fixture.syncGenesisDao.listRecoveryCapsules("space-1")).thenAnswer { fixture.insertedRecoveryCapsules.toList() }
        org.mockito.Mockito.clearInvocations(fixture.rssHubSettingsRepo)
        installer.install(1, bundle, shards, now = 2001)
        org.mockito.Mockito.verifyNoInteractions(fixture.rssHubSettingsRepo)
    }

    @Test fun interruptedMainCommitPreservesExactInstallScope(): Unit = runBlocking {
        val fixture = DatabaseFixture(1, "space-1")
        val (bundle, shards) = createValidBundleAndShards()
        val installer = fixture.createInstaller()
        installer.transactionRunner = { block ->
            block()
            if (fixture.insertedCoverages.isNotEmpty()) error("process stopped after main commit")
        }
        val scope = setOf("CORE_META", "AUTH")
        val failure = runCatching { installer.install(1, bundle, shards, selectedLanes = scope) }.exceptionOrNull()
        assertEquals("process stopped after main commit", failure?.message)
        assertEquals("REBASE_PREPARE", fixture.binding.lifecycleState)
        val attempt = fixture.insertedRecoveryCapsules.single { it.capsuleId == "snapshot-install:space-1" }
        assertEquals("SNAPSHOT_INSTALL_STARTED", attempt.reason)
        assertEquals(scope, SyncSnapshotInstallJournal.scope(attempt).installedLanes.toSet())
        val retry = fixture.createInstaller().also { it.transactionRunner = { block -> block() } }
        val wrongScope = runCatching { retry.install(1, bundle, shards) }.exceptionOrNull()
        assertTrue(wrongScope?.message.orEmpty().contains("finish the persisted Snapshot install scope"))
        retry.install(1, bundle, shards, selectedLanes = scope)
        assertEquals("STAGING", fixture.binding.lifecycleState)
        val ready = SyncSnapshotInstallJournal.read(fixture.database, "space-1")!!
        assertEquals(scope, SyncSnapshotInstallJournal.scope(ready).installedLanes.toSet())
    }

    @Test fun interruptedProjectionReplaysNewLocalOperations(): Unit = runBlocking {
        val fixture = DatabaseFixture(1, "space-1")
        val (bundle, shards) = createValidBundleAndShards(includeAi = true)
        var failProjection = true
        var projected = false
        val extension = object : SyncBusinessProjectionExtension {
            override fun owns(entityType: String) = entityType == "conversation"
            override suspend fun apply(operation: SyncOperationEntity) = Unit
            override suspend fun materializeSnapshotEntity(syncSpaceId: String, entity: SyncGenesisProjectionEntity, now: Long): Boolean {
                if (failProjection) error("LLM database interrupted")
                projected = true
                return true
            }
        }
        val applier = mock(AndroidSyncBusinessApplier::class.java)
        val first = fixture.createInstaller(extensions = setOf(extension), applier = applier)
            .also { it.transactionRunner = { block -> block() } }
        assertEquals("LLM database interrupted", runCatching { first.install(1, bundle, shards) }.exceptionOrNull()?.message)
        assertEquals("REBASE_PREPARE", fixture.binding.lifecycleState)
        assertTrue(fixture.insertedCoverages.isNotEmpty())
        val lateOperation = SyncOperationEntity(
            "new-local-operation", "space-1", "local", "actor-1", "CONFIG", 11, 11, "{}", "[]",
            "rsshub_settings", "settings", 0, "FIELD_SET", 1, "{}", 1,
            createdWallClock = 2, payloadHash = "hash", signingDigest = "digest", authorSignature = "signature",
            buildStatus = "SIGNED", createdAt = 2, updatedAt = 2,
        )
        `when`(fixture.syncOperationDao.listAllForRecovery("space-1")).thenReturn(listOf(lateOperation))
        failProjection = false
        val retry = fixture.createInstaller(extensions = setOf(extension), applier = applier)
            .also { it.transactionRunner = { block -> block() } }
        retry.install(1, bundle, shards)
        assertTrue(projected)
        verify(applier).apply(lateOperation)
        assertEquals("STAGING", fixture.binding.lifecycleState)
    }

    @Test
    fun installsAuthenticatedRichWireSnapshot() = runBlocking {
        val fixture = DatabaseFixture(accountId = 1, syncSpaceId = "space-1")
        val (bundle, shards) = createValidBundleAndShards(syncSpaceId = "space-1")
        val keys = SyncDeviceSigningKeyStore()
        val wire = SyncSnapshotWireCodec.toWire(
            bundle = bundle,
            shards = shards,
            coverage = SyncSnapshotWireCodec.coverageFromShards(
                shards.map { shard ->
                    SyncSnapshotShardWire(
                        replicationLaneId = shard.replicationLaneId,
                        frontierJson = shard.frontierByActorJson,
                        entityStateJson = shard.entityStateJson,
                        fieldVersionStateJson = shard.fieldVersionStateJson,
                        causalMetadataJson = shard.causalMergeMetadataJson,
                        genesisCoverageJson = shard.genesisCoverageJson,
                        deletionGenerationSummaryJson = "{}",
                        contentHash = shard.shardHash,
                        deletionSummaryJson = shard.deletionSummaryJson,
                        generationSummaryJson = shard.generationSummaryJson,
                        blobManifestIndexJson = shard.blobManifestIndexJson,
                        blobReferenceIndexJson = shard.blobReferenceIndexJson,
                    )
                },
            ),
            keyStore = keys,
        )
        val installer = fixture.createInstaller(keys)
        installer.transactionRunner = { it() }
        val result = installer.installWire(
            fixture.accountId,
            wire,
            SyncPeerKey(keys.publicKeySpkiBase64("device-1")),
            now = 2000L,
        )
        assertEquals(bundle.snapshotBundleId, result.snapshotBundleId)
        assertEquals(3, result.materializedEntities)
    }

    @Test
    fun corruptedShardHashIsRejected() = runBlocking {
        val fixture = DatabaseFixture(accountId = 1, syncSpaceId = "space-1")
        val (bundle, shards) = createValidBundleAndShards(syncSpaceId = "space-1")
        // 篡改其中一个 Shard 的哈希
        val corruptedShards = shards.mapIndexed { idx, shard ->
            if (idx == 0) shard.copy(shardHash = "tampered-hash") else shard
        }

        val installer = fixture.createInstaller()
        installer.transactionRunner = { it() }
        val error = assertThrows(SnapshotCorruptedError::class.java) {
            runBlocking { installer.install(fixture.accountId, bundle, corruptedShards) }
        }
        assertTrue(error.message!!.contains("Shard hash mismatch"))
    }

    @Test fun historicalCheckpointStillAuthorizesItsSnapshot(): Unit = runBlocking {
        val fixture = DatabaseFixture(1, "space-1")
        val (bundle, shards) = createValidBundleAndShards()
        val keys = SyncDeviceSigningKeyStore()
        val coverage = shards.associate { it.replicationLaneId to mapOf("actor-1" to 10L) }
        fun checkpoint(sequence: Long) = SyncAuthWireCodec.sign(authRoot("space-1").copy(
            authSequence = sequence, objectType = SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT,
            payloadJson = "{\"acceptedPrefixByActorLane\":${json.encodeToString(coverage)}}"), keys)
        val c1 = checkpoint(1)
        val c2 = checkpoint(2)
        `when`(fixture.syncAuthLedgerDao.list("space-1")).thenReturn(listOf(authRoot("space-1"), c1, c2).map {
            SyncAuthLedgerEntity(it.authObjectId, it.syncSpaceId, it.authEpoch, json.encodeToString(it), 1L)
        })
        val wire = SyncSnapshotWireCodec.toWire(bundle.copy(snapshotClass = "GC_BASELINE",
            authStabilityCheckpointId = c1.authObjectId), shards, coverage, keys)
        val installer = fixture.createInstaller(keys).also { it.transactionRunner = { block -> block() } }
        val result = installer.installWire(1, wire, SyncPeerKey(keys.publicKeySpkiBase64("device-1")))
        assertEquals(3, result.materializedEntities)
    }

    @Test
    fun corruptedRootHashIsRejected() = runBlocking {
        val fixture = DatabaseFixture(accountId = 1, syncSpaceId = "space-1")
        val (bundle, shards) = createValidBundleAndShards(syncSpaceId = "space-1")
        val corruptedBundle = bundle.copy(rootHash = "corrupted-root-hash")

        val installer = fixture.createInstaller()
        installer.transactionRunner = { it() }
        val error = assertThrows(SnapshotCorruptedError::class.java) {
            runBlocking { installer.install(fixture.accountId, corruptedBundle, shards) }
        }
        assertTrue(error.message!!.contains("Bundle rootHash mismatch"))
    }

    @Test
    fun missingDependentFeedFailsAtomically() = runBlocking {
        val fixture = DatabaseFixture(accountId = 1, syncSpaceId = "space-1")
        // 构造孤儿文章（feedSyncId 不存在）
        val (bundle, shards) = createValidBundleAndShards(syncSpaceId = "space-1", includeOrphanArticle = true)

        val installer = fixture.createInstaller()
        installer.transactionRunner = { it() }
        val error = assertThrows(SnapshotDependencyMissingError::class.java) {
            runBlocking { installer.install(fixture.accountId, bundle, shards) }
        }
        assertTrue(error.message!!.contains("missing dependent feed mapping"))
    }

    @Test
    fun rejectsUnsafeRebaseWhenRootHashOrPolicyHashIsBlank(): Unit = runBlocking {
        val fixture = DatabaseFixture(accountId = 1, syncSpaceId = "space-1")
        val (bundle, shards) = createValidBundleAndShards(syncSpaceId = "space-1")
        val unsafeBundle = bundle.copy(rootHash = "")

        val installer = fixture.createInstaller()
        installer.transactionRunner = { it() }
        assertThrows(SyncRebaseUnsafeException::class.java) {
            runBlocking { installer.install(fixture.accountId, unsafeBundle, shards) }
        }
    }

    @Test
    fun emptyConfigNeverWritesOutsideTheDatabaseTransaction(): Unit = runBlocking {
        val fixture = DatabaseFixture(accountId = 1, syncSpaceId = "space-1")
        val (bundle, shards) = createValidBundleAndShards(syncSpaceId = "space-1")

        org.mockito.Mockito.`when`(fixture.filterRepo.importRules(org.mockito.kotlin.any()))
            .thenThrow(IllegalStateException("Corrupted rule syntax"))

        val installer = fixture.createInstaller()
        installer.transactionRunner = { it() }
        installer.install(fixture.accountId, bundle, shards)
        org.mockito.Mockito.verify(fixture.filterRepo, org.mockito.Mockito.never()).importRules(org.mockito.kotlin.any())
    }

    @Test fun existingLocalRowsAloneDoNotRejectRebase(): Unit = runBlocking {
        val fixture = DatabaseFixture(1, "space-1")
        val (bundle, shards) = createValidBundleAndShards()
        `when`(fixture.groupDao.queryAll(1)).thenReturn(listOf(Group("local", "Tech", 1)))
        val installer = fixture.createInstaller()
        installer.transactionRunner = { it() }
        val result = installer.install(1, bundle, shards)
        assertEquals(bundle.snapshotBundleId, result.snapshotBundleId)
        assertTrue(fixture.insertedGroups.any { it.name == "Tech" })
        assertTrue(fixture.insertedCoverages.isNotEmpty())
        assertTrue(fixture.insertedRecoveryCapsules.all { it.reason == "SNAPSHOT_INSTALL_READY" })
        assertEquals(SyncSpaceLifecycleState.STAGING.name, fixture.binding.lifecycleState)
    }

    @Test fun foreignShardIsRejectedBeforeMaterialization(): Unit = runBlocking {
        val fixture = DatabaseFixture(1, "space-1")
        val (bundle, shards) = createValidBundleAndShards()
        val installer = fixture.createInstaller()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            installer.install(1, bundle, shards.map { it.copy(syncSpaceId = "other") })
        } }
        assertTrue(fixture.insertedGroups.isEmpty())
    }
}
