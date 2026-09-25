package me.ash.reader.infrastructure.sync.identity

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

/** Android Reader 主库的 Genesis Identity Backfill。首期仅允许 Local Account。 */
@Singleton
class LibraryGenesisIdentityBackfill @Inject constructor(
    private val database: AndroidDatabase,
    private val articleFilterRepository: ArticleFilterRepository,
    private val websiteRuleRepository: WebsiteRuleRepository,
    private val jsonRuleRepository: JsonRuleRepository,
    private val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
) {
    suspend fun backfill(
        syncSpaceId: String,
        accountId: Int,
        now: Long = System.currentTimeMillis(),
    ): GenesisIdentityBackfillReport {
        val account = requireNotNull(database.accountDao().queryById(accountId)) {
            "Account $accountId does not exist"
        }
        require(account.type.id == AccountType.Local.id) {
            "R10 Genesis Library backfill only supports Local Account; account=$accountId type=${account.type.id}"
        }

        // 先读取当前本地事实；正式 cutover 会在 Outbox 落地后由 Genesis cut 固定其 Snapshot 视图。
        val groups = database.groupDao().queryAll(accountId)
        val feeds = database.feedDao().queryAll(accountId)
        val articles = database.articleDao().queryAllByAccountId(accountId)
        val filterRules = articleFilterRepository.getAll()
        val websiteRules = websiteRuleRepository.listSyncRules()
        val jsonRules = jsonRuleRepository.listSyncRules()
        val websiteParsePreferences =
            websiteParsePreferenceRepository.listUserSyncStates(
                feeds.mapTo(linkedSetOf()) { it.id }
            )
        val rssHubSubscriptionSources =
            rssHubSubscriptionRepository.listSyncSources(
                feeds.mapTo(linkedSetOf()) { it.id }
            )

        return database.withTransaction {
            SyncIdentityBackfillSupport.ensureSpace(database.syncSpaceDao(), syncSpaceId, now)
            val mappingDao = database.syncIdentityMappingDao()
            val results = mutableListOf<SyncIdentityTypeBackfillResult>()

            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.GROUP,
                    seeds = groups.map { SyncIdentitySeed(localId = it.id) },
                    now = now,
                )

            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.FEED,
                    seeds =
                        feeds.map { feed ->
                            SyncIdentitySeed(
                                localId = feed.id,
                                canonicalKey = SyncCanonicalIdentity.feedKey(feed.sourceType, feed.url),
                            )
                        },
                    now = now,
                )

            // Article Key 必须引用已经保存的 Feed canonical key，而不是每次用当前 URL 重算后级联改身份。
            val effectiveFeedCanonicalKeys =
                mappingDao.findByType(syncSpaceId, SyncEntityType.FEED.wireName)
                    .associate { it.localId to it.canonicalKey }
            val effectiveFeedSyncIds =
                mappingDao.findByType(syncSpaceId, SyncEntityType.FEED.wireName)
                    .associate { it.localId to it.syncId }
            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.ARTICLE,
                    seeds =
                        articles.map { article ->
                            SyncIdentitySeed(
                                localId = article.id,
                                canonicalKey =
                                    SyncCanonicalIdentity.articleKey(
                                        effectiveFeedCanonicalKeys[article.feedId],
                                        article.link,
                                    ),
                            )
                        },
                    now = now,
                )

            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.FILTER_RULE,
                    seeds =
                        filterRules.map { rule ->
                            SyncIdentitySeed(
                                localId = rule.id,
                                preferredSyncId = SyncCanonicalIdentity.adoptUuidOrNull(rule.id),
                            )
                        },
                    now = now,
                )

            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.WEBSITE_RULE,
                    seeds =
                        websiteRules.map { rule ->
                            SyncIdentitySeed(
                                localId = rule.id,
                                preferredSyncId =
                                    SyncCanonicalIdentity.configRuleSyncId(
                                        SyncEntityType.WEBSITE_RULE,
                                        rule.id,
                                    ),
                            )
                        },
                    now = now,
                )

            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.JSON_RULE,
                    seeds =
                        jsonRules.map { rule ->
                            SyncIdentitySeed(
                                localId = rule.id,
                                preferredSyncId =
                                    SyncCanonicalIdentity.configRuleSyncId(
                                        SyncEntityType.JSON_RULE,
                                        rule.id,
                                    ),
                            )
                        },
                    now = now,
                )

            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.RSSHUB_SETTINGS,
                    seeds =
                        listOf(
                            SyncIdentitySeed(
                                localId = "rsshub-settings",
                                preferredSyncId =
                                    SyncCanonicalIdentity.configRuleSyncId(
                                        SyncEntityType.RSSHUB_SETTINGS,
                                        "rsshub-settings",
                                    ),
                            )
                        ),
                    now = now,
                )

            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.WEBSITE_PARSE_PREFERENCE,
                    seeds =
                        websiteParsePreferences.keys.map { localFeedId ->
                            val feedSyncId =
                                effectiveFeedSyncIds[localFeedId]
                                    ?: error(
                                        "Website parse preference references an unmapped feed $localFeedId"
                                    )
                            SyncIdentitySeed(
                                localId = feedSyncId,
                                preferredSyncId =
                                    SyncCanonicalIdentity.configRuleSyncId(
                                        SyncEntityType.WEBSITE_PARSE_PREFERENCE,
                                        feedSyncId,
                                    ),
                            )
                        },
                    now = now,
                )

            results +=
                SyncIdentityBackfillSupport.backfillType(
                    dao = mappingDao,
                    syncSpaceId = syncSpaceId,
                    entityType = SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE,
                    seeds =
                        rssHubSubscriptionSources.keys.map { localFeedId ->
                            val feedSyncId =
                                effectiveFeedSyncIds[localFeedId]
                                    ?: error(
                                        "RSSHub subscription source references an unmapped feed $localFeedId"
                                    )
                            SyncIdentitySeed(
                                localId = feedSyncId,
                                preferredSyncId =
                                    SyncCanonicalIdentity.configRuleSyncId(
                                        SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE,
                                        feedSyncId,
                                    ),
                            )
                        },
                    now = now,
                )

            GenesisIdentityBackfillReport(syncSpaceId = syncSpaceId, results = results)
        }
    }
}
