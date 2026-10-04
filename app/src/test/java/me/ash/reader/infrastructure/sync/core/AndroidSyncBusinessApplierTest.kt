package me.ash.reader.infrastructure.sync.core

import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AndroidSyncBusinessApplierTest {
    private fun stubSupportedSchema(operation: SyncOperationEntity) {
        `when`(operation.schemaVersion).thenReturn(1)
        `when`(operation.payloadSchemaVersion).thenReturn(1)
    }

    private fun createApplier(database: AndroidDatabase): AndroidSyncBusinessApplier {
        runBlocking {
            `when`(database.syncInboxDao().listFieldCandidates(org.mockito.kotlin.any()))
                .thenReturn(emptyList())
            `when`(
                database.syncOperationDao().listAppliedEntityOperations(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                )
            ).thenReturn(emptyList())
            `when`(
                database.syncOperationDao().listPendingEntityOutbox(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                )
            ).thenReturn(emptyList())
        }
        return AndroidSyncBusinessApplier(
            database = database,
            localBlobStore = mock(SyncLocalBlobStore::class.java),
            blobState = mock(SyncBlobStateService::class.java),
            localEviction = mock(SyncLocalEvictionService::class.java),
            readerCacheHelper = mock(me.ash.reader.infrastructure.rss.ReaderCacheHelper::class.java),
            articleFilterRepository =
                mock(me.ash.reader.infrastructure.filter.ArticleFilterRepository::class.java),
            websiteRuleRepository =
                mock(me.ash.reader.infrastructure.website.WebsiteRuleRepository::class.java),
            jsonRuleRepository =
                mock(me.ash.reader.infrastructure.json.JsonRuleRepository::class.java),
            rssHubSettingsRepository =
                mock(me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository::class.java),
            rssHubSubscriptionRepository =
                mock(me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository::class.java),
            websiteParsePreferenceRepository =
                mock(me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository::class.java),
        ).also { wireRowFixtures(it, database) }
    }

    /** 手动构造投影器时显式接入 Hilt 在生产中提供的三个行读取依赖。 */
    private fun wireRowFixtures(applier: AndroidSyncBusinessApplier, database: AndroidDatabase) {
        applier.fieldRows = fieldRowsFixture(database)
        applier.retainedFields = retainedFieldsFixture(applier.fieldRows)
        applier.boundedArticles = mock(SyncBoundedArticleRows::class.java).also { articles ->
            runBlocking {
                org.mockito.kotlin.whenever(articles.queryById(org.mockito.kotlin.any())).thenAnswer { call ->
                    runBlocking { database.articleDao().queryById(call.getArgument(0)) }
                }
            }
        }
    }

    /** 业务投影测试没有额外磁盘历史，使用原版本裁决器保持当前值与新值的合并语义。 */
    private fun retainedFieldsFixture(fields: SyncFieldStateRows): SyncRetainedFieldMerge =
        mock(SyncRetainedFieldMerge::class.java).also { merge ->
            runBlocking {
                org.mockito.kotlin.whenever(merge.resolve(org.mockito.kotlin.any())).thenAnswer { call ->
                    val input = call.getArgument<SyncRetainedFieldMerge.Options>(0)
                    val operation = input.operation
                    val incoming = SyncFieldCandidate(input.field, input.valueJson,
                        SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence),
                        SyncVersionSource.OPERATION, causalContextJson = operation.causalContextJson,
                        logicalClock = operation.logicalClock)
                    SyncVersionResolver.resolve(listOfNotNull(input.current?.let(fields::candidate), incoming), input.policy)
                }
            }
        }

    /** 原 DAO 夹具仍描述相同寄存器，通过新的分段读取依赖接入原数据。 */
    private fun fieldRowsFixture(database: AndroidDatabase): SyncFieldStateRows =
        mock(SyncFieldStateRows::class.java).also { rows ->
            org.mockito.kotlin.whenever(rows.find(org.mockito.kotlin.any())).thenAnswer { call ->
                val field = call.getArgument<SyncFieldStateRows.Field>(0)
                runBlocking { database.syncInboxDao().findFieldVersion(field.syncSpaceId, field.entityType, field.entitySyncId, field.fieldId) }
            }
            org.mockito.kotlin.whenever(rows.candidates(org.mockito.kotlin.any())).thenReturn(emptySequence())
            org.mockito.kotlin.whenever(rows.candidate(org.mockito.kotlin.any())).thenAnswer { call ->
                val value = call.getArgument<SyncFieldVersionEntity>(0)
                SyncFieldCandidate(value.fieldId, value.valueJson, value.versionToken,
                    SyncVersionToken.source(value.versionToken), causalContextJson = value.causalContextJson,
                    logicalClock = value.logicalClock ?: 0L)
            }
        }

    @Test
    fun groupCreationAllocatesLocalIdentity() {
        runBlocking {
            val (database, operation) = groupFixture()
            `when`(operation.operationType).thenReturn(SyncMutationType.UPSERT.name)
            `when`(operation.payloadJson).thenReturn("{\"fields\":{\"name\":\"new\"}}")
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "group", "remote-group")).thenReturn(null)
            var inserted: List<Group> = emptyList()
            val groups = database.groupDao()
            doAnswer { invocation -> inserted = invocation.getArgument(0); Unit }
                .`when`(groups).insertAll(anyList())
            createApplier(database).apply(operation)
            assertEquals("new", inserted.single().name)
            assertEquals(7, inserted.single().accountId)
            assertNotEquals("remote-group", inserted.single().id)
        }
    }

    @Test
    fun groupRenameUsesStableMappingAndCurrentAccount() {
        runBlocking {
            val (database, operation) = groupFixture()
            val group = Group("local-group", "old", 7)
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "group", "remote-group"))
                .thenReturn(SyncIdentityMappingEntity("space", "group", group.id, "remote-group", createdAt = 1, updatedAt = 1))
            `when`(database.groupDao().queryById(group.id)).thenReturn(group)
            createApplier(database).apply(operation)
            verify(database.groupDao()).update(group.copy(name = "new"))
            verify(database.groupDao(), never()).insertAll(anyList())
        }
    }

    @Test
    fun groupFromAnotherAccountCannotBeRenamed() {
        runBlocking {
            val (database, operation) = groupFixture()
            val group = Group("local-group", "old", 99)
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "group", "remote-group"))
                .thenReturn(SyncIdentityMappingEntity("space", "group", group.id, "remote-group", createdAt = 1, updatedAt = 1))
            `when`(database.groupDao().queryById(group.id)).thenReturn(group)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { createApplier(database).apply(operation) }
            }
        }
    }

    @Test
    fun deletedGroupIgnoresOldGeneration() {
        runBlocking {
            val (database, operation) = groupFixture()
            `when`(database.syncInboxDao().findTombstone("space", "group", "remote-group"))
                .thenReturn(SyncTombstoneEntity("space", "group", "remote-group", 0, "tombstone", updatedAt = 1))
            createApplier(database).apply(operation)
            verify(database, never()).groupDao()
        }
    }

    @Test
    fun groupDeletionRemovesLocalRowAndRecordsTombstone() {
        runBlocking {
            val (database, operation) = groupFixture()
            val group = Group("local-group", "old", 7)
            `when`(operation.operationType).thenReturn(SyncMutationType.GLOBAL_DELETE.name)
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "group", "remote-group"))
                .thenReturn(SyncIdentityMappingEntity("space", "group", group.id, "remote-group", createdAt = 1, updatedAt = 1))
            `when`(database.groupDao().queryById(group.id)).thenReturn(group)
            `when`(database.feedDao().queryByGroupId(7, group.id)).thenReturn(emptyList())

            createApplier(database).apply(operation)
            verify(database.groupDao()).delete(group)
        }
    }

    @Test
    fun feedCreationDefersWhenGroupMappingMissing() {
        runBlocking {
            val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
            val operation = mock(SyncOperationEntity::class.java)
            stubSupportedSchema(operation)
            `when`(operation.entityType).thenReturn("feed")
            `when`(operation.operationType).thenReturn(SyncMutationType.UPSERT.name)
            `when`(operation.payloadJson).thenReturn("{\"fields\":{\"name\":\"Tech\",\"url\":\"https://example.com/rss\",\"groupSyncId\":\"group-sync-1\"}}")
            `when`(operation.syncSpaceId).thenReturn("space")
            `when`(operation.entitySyncId).thenReturn("feed-sync-1")
            `when`(operation.actorIncarnationId).thenReturn("actor")
            `when`(operation.replicationLaneId).thenReturn("LIBRARY")
            `when`(operation.sequence).thenReturn(1L)
            `when`(database.syncRuntimeDao().findBindingBySpace("space"))
                .thenReturn(SyncLocalSpaceBindingEntity(7, "space", "ACTIVE", createdAt = 1, updatedAt = 1))
            `when`(database.syncInboxDao().findTombstone("space", "feed", "feed-sync-1")).thenReturn(null)
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "group", "group-sync-1")).thenReturn(null)
            `when`(database.syncAliasDao().listEdges("space", "feed")).thenReturn(emptyList())
            `when`(database.groupDao().queryAll(7)).thenReturn(
                listOf(Group("local-default", "Default", 7))
            )

            assertThrows(SyncApplyDeferredException::class.java) {
                runBlocking { createApplier(database).apply(operation) }
            }
        }
    }

    @Test
    fun feedCreationSucceedsWithResolvedGroup() {
        runBlocking {
            val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
            val operation = mock(SyncOperationEntity::class.java)
            stubSupportedSchema(operation)
            `when`(operation.entityType).thenReturn("feed")
            `when`(operation.operationType).thenReturn(SyncMutationType.UPSERT.name)
            `when`(operation.payloadJson).thenReturn("{\"fields\":{\"name\":\"Tech News\",\"url\":\"https://example.com/rss\",\"groupSyncId\":\"group-sync-1\",\"sourceType\":\"RSS\"}}")
            `when`(operation.syncSpaceId).thenReturn("space")
            `when`(operation.entitySyncId).thenReturn("feed-sync-1")
            `when`(operation.actorIncarnationId).thenReturn("actor")
            `when`(operation.replicationLaneId).thenReturn("LIBRARY")
            `when`(operation.sequence).thenReturn(1L)
            `when`(database.syncRuntimeDao().findBindingBySpace("space"))
                .thenReturn(SyncLocalSpaceBindingEntity(7, "space", "ACTIVE", createdAt = 1, updatedAt = 1))
            `when`(database.syncInboxDao().findTombstone("space", "feed", "feed-sync-1")).thenReturn(null)

            val group = Group("local-group-1", "Tech", 7)
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "group", "group-sync-1"))
                .thenReturn(SyncIdentityMappingEntity("space", "group", group.id, "group-sync-1", createdAt = 1, updatedAt = 1))
            `when`(database.groupDao().queryById(group.id)).thenReturn(group)
            `when`(database.groupDao().queryAll(7)).thenReturn(listOf(group))
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "feed", "feed-sync-1")).thenReturn(null)
            `when`(database.syncAliasDao().listEdges("space", "feed")).thenReturn(emptyList())

            var insertedFeeds: List<Feed> = emptyList()
            val feedDao = database.feedDao()
            doAnswer { invocation ->
                insertedFeeds = invocation.getArgument(0)
                Unit
            }.`when`(feedDao).insertAll(anyList())

            createApplier(database).apply(operation)
            assertEquals("Tech News", insertedFeeds.single().name)
            assertEquals("local-group-1", insertedFeeds.single().groupId)
        }
    }

    @Test
    fun feedRelationUpdateChangesGroupId() {
        runBlocking {
            val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
            val operation =
                SyncOperationEntity(
                    operationId = "operation",
                    syncSpaceId = "space",
                    authorDeviceId = "peer",
                    actorIncarnationId = "actor",
                    replicationLaneId = "LIBRARY",
                    sequence = 1L,
                    logicalClock = 1L,
                    causalContextJson = "{}",
                    dependencyDotsJson = "[]",
                    entityType = "feed",
                    entitySyncId = "feed-sync-1",
                    entityGeneration = 0L,
                    operationType = SyncMutationType.RELATION_SET.name,
                    payloadSchemaVersion = 1,
                    payloadJson = "{\"feedSyncId\":\"feed-sync-1\",\"groupSyncId\":\"group-sync-2\"}",
                    schemaVersion = 1,
                    createdWallClock = 1L,
                    payloadHash = "hash",
                    signingDigest = "digest",
                    authorSignature = "signature",
                    buildStatus = SyncOperationBuildStatus.SIGNED.name,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            `when`(database.syncRuntimeDao().findBindingBySpace("space"))
                .thenReturn(SyncLocalSpaceBindingEntity(7, "space", "ACTIVE", createdAt = 1, updatedAt = 1))

            val feed = Feed("local-feed-1", "Tech", null, "https://example.com/rss", "local-group-1", 7, false, false, false, SourceType.RSS)
            val newGroup = Group("local-group-2", "New Group", 7)
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "feed", "feed-sync-1"))
                .thenReturn(SyncIdentityMappingEntity("space", "feed", feed.id, "feed-sync-1", createdAt = 1, updatedAt = 1))
            `when`(database.syncIdentityMappingDao().findBySyncId("space", "group", "group-sync-2"))
                .thenReturn(SyncIdentityMappingEntity("space", "group", newGroup.id, "group-sync-2", createdAt = 1, updatedAt = 1))
            `when`(database.feedDao().queryById(feed.id)).thenReturn(feed)
            `when`(database.groupDao().queryById(newGroup.id)).thenReturn(newGroup)

            var updatedFeeds: List<Feed> = emptyList()
            val feedDao = database.feedDao()
            doAnswer { invocation ->
                updatedFeeds = invocation.getArgument(0)
                Unit
            }.`when`(feedDao).updateAll(anyList())

            createApplier(database).apply(operation)
            assertEquals("local-group-2", updatedFeeds.single().groupId)
        }
    }

    private suspend fun groupFixture(): Pair<AndroidDatabase, SyncOperationEntity> {
        val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
        val operation = mock(SyncOperationEntity::class.java)
            stubSupportedSchema(operation)
        `when`(operation.entityType).thenReturn("group")
        `when`(operation.operationType).thenReturn(SyncMutationType.FIELD_SET.name)
        `when`(operation.payloadJson).thenReturn("{\"field\":\"name\",\"value\":\"new\"}")
        `when`(operation.syncSpaceId).thenReturn("space")
        `when`(operation.entitySyncId).thenReturn("remote-group")
        `when`(operation.actorIncarnationId).thenReturn("actor")
        `when`(operation.replicationLaneId).thenReturn("LIBRARY")
        `when`(operation.operationId).thenReturn("operation")
        `when`(operation.sequence).thenReturn(1L)
        `when`(database.syncRuntimeDao().findBindingBySpace("space"))
            .thenReturn(SyncLocalSpaceBindingEntity(7, "space", "ACTIVE", createdAt = 1, updatedAt = 1))
        `when`(database.syncInboxDao().findTombstone("space", "group", "remote-group")).thenReturn(null)
        `when`(database.syncInboxDao().findFieldVersion("space", "group", "remote-group", "name")).thenReturn(null)
        `when`(database.syncAliasDao().listEdges("space", "group")).thenReturn(emptyList())
        return database to operation
    }

    @Test
    fun unsupportedRelationRemainsDeferredWithoutWritingBusinessState() {
        runBlocking {
            val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
            val operation = mock(SyncOperationEntity::class.java)
            stubSupportedSchema(operation)
            `when`(operation.entityType).thenReturn("unknown_entity")
            `when`(operation.operationType).thenReturn(SyncMutationType.RELATION_SET.name)
            `when`(operation.payloadJson).thenReturn("{}")
            `when`(operation.syncSpaceId).thenReturn("space")
            `when`(database.syncRuntimeDao().findBindingBySpace("space"))
                .thenReturn(SyncLocalSpaceBindingEntity(7, "space", "ACTIVE", createdAt = 1, updatedAt = 1))
            assertThrows(SyncApplyDeferredException::class.java) {
                runBlocking { createApplier(database).apply(operation) }
            }
        }
    }

    @Test
    fun malformedPayloadCannotBecomeSuccessfulNoOp() {
        runBlocking {
            val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
            val operation = mock(SyncOperationEntity::class.java)
            stubSupportedSchema(operation)
            `when`(operation.entityType).thenReturn(SyncEntityType.ARTICLE.wireName)
            `when`(operation.operationType).thenReturn(SyncMutationType.FIELD_SET.name)
            `when`(operation.payloadJson).thenReturn("not json")
            `when`(operation.syncSpaceId).thenReturn("space")
            `when`(operation.entitySyncId).thenReturn("article")
            `when`(database.syncRuntimeDao().findBindingBySpace("space"))
                .thenReturn(SyncLocalSpaceBindingEntity(7, "space", "ACTIVE", createdAt = 1, updatedAt = 1))
            `when`(
                database.syncInboxDao().findTombstone(
                    "space",
                    SyncEntityType.ARTICLE.wireName,
                    "article",
                )
            ).thenReturn(null)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { createApplier(database).apply(operation) }
            }
        }
    }

    @Test
    fun `rollbackField rolls back tainted field and restores default state`() {
        runBlocking {
            val database = mock(AndroidDatabase::class.java, RETURNS_DEEP_STUBS)
            val applier = createApplier(database)

            val currentFieldVersion = SyncFieldVersionEntity(
                syncSpaceId = "space-1",
                entityType = "article",
                entitySyncId = "remote-art-1",
                fieldId = "isStarred",
                entityGeneration = 0,
                versionToken = "tok-1",
                sourceOperationId = "bad-op-1",
                valueJson = "true",
                updatedAt = 1000,
            )
            `when`(database.syncInboxDao().findFieldVersion("space-1", "article", "remote-art-1", "isStarred"))
                .thenReturn(currentFieldVersion)
            `when`(database.syncInboxDao().listAppliedInbox("space-1"))
                .thenReturn(emptyList())
            `when`(database.syncIdentityMappingDao().findBySyncId("space-1", "article", "remote-art-1"))
                .thenReturn(SyncIdentityMappingEntity("space-1", "article", "local-art-1", "remote-art-1", createdAt = 1, updatedAt = 1))
            `when`(database.syncRuntimeDao().findBindingBySpace("space-1"))
                .thenReturn(SyncLocalSpaceBindingEntity(7, "space-1", "ACTIVE", createdAt = 1, updatedAt = 1))
            `when`(
                database.syncInboxDao().findRollbackBaseline(
                    "space-1",
                    "article",
                    "remote-art-1",
                    0,
                    "isStarred",
                ),
            ).thenReturn(
                SyncFieldRollbackBaselineEntity(
                    syncSpaceId = "space-1",
                    entityType = "article",
                    entitySyncId = "remote-art-1",
                    entityGeneration = 0,
                    fieldId = "isStarred",
                    valueJson = "false",
                ),
            )

            val dummyFeed = Feed(
                id = "f1",
                name = "Feed 1",
                url = "https://example.com/rss",
                groupId = "g1",
                sourceType = SourceType.RSS,
                accountId = 7,
            )
            val existingArticle = Article(
                id = "local-art-1",
                date = Date(1_000_000L),
                title = "Test",
                rawDescription = "",
                shortDescription = "",
                link = "https://example.com/1",
                feedId = "f1",
                accountId = 7,
                isStarred = true,
                isUnread = true,
            )
            `when`(database.articleDao().queryById("local-art-1"))
                .thenReturn(ArticleWithFeed(existingArticle, dummyFeed))

            // 鎵ц鍥炴粴
            applier.rollbackField("space-1", "article", "remote-art-1", "isStarred", "bad-op-1")

            // 楠岃瘉瀛楁鐗堟湰琚垹闄わ紝骞舵仮澶嶅埌鎸佷箙鍖?rollback baseline銆?            verify(database.syncInboxDao()).deleteFieldVersion("space-1", "article", "remote-art-1", "isStarred")
            verify(database.articleDao()).update(existingArticle.copy(isStarred = false))
        }
    }
}
