package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository

/**
 * Repairs the crash window between a durable Feed GLOBAL_DELETE and the follow-up CONFIG cleanup.
 *
 * Feed-scoped CONFIG is stored across Room/file/SharedPreferences, so pretending all stores share
 * one atomic transaction would be unsafe. Instead, every sync opportunity deterministically
 * repairs only Feed mappings whose same/newer generation already has a durable Feed tombstone.
 */
@Singleton
class SyncFeedScopedConfigReconciler @Inject constructor(
    private val database: AndroidDatabase,
    private val filterRepository: ArticleFilterRepository,
    private val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    private val syncMutations: LibrarySyncMutationCapture,
) {
    suspend fun reconcile(syncSpaceId: String): Int {
        val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId) ?: return 0
        if (binding.lifecycleState != SyncSpaceLifecycleState.ACTIVE.name) return 0
        if (database.syncRuntimeDao().findActiveActor(syncSpaceId) == null) return 0

        val orphanFeedIds =
            database.syncIdentityMappingDao()
                .findByType(syncSpaceId, SyncEntityType.FEED.wireName)
                .filter { mapping ->
                    if (database.feedDao().queryById(mapping.localId) != null) {
                        false
                    } else {
                        database.syncInboxDao()
                            .findTombstone(
                                syncSpaceId,
                                SyncEntityType.FEED.wireName,
                                mapping.syncId,
                            )
                            ?.entityGeneration
                            ?.let { it >= mapping.generation }
                            ?: false
                    }
                }
                .mapTo(linkedSetOf()) { it.localId }

        if (orphanFeedIds.isEmpty()) return 0

        syncMutations.captureFilterRulesMutation(
            accountId = binding.localAccountId,
            readRules = filterRepository::getAll,
            replaceRules = filterRepository::replaceRules,
        ) {
            orphanFeedIds.forEach(filterRepository::deleteByFeed)
        }

        syncMutations.captureWebsiteParsePreferencesMutation(
            accountId = binding.localAccountId,
            feedIds = orphanFeedIds,
            readStates = {
                orphanFeedIds.associateWith(websiteParsePreferenceRepository::getUserSyncState)
            },
            replaceStates = { states ->
                states.forEach { (feedId, state) ->
                    websiteParsePreferenceRepository.applyUserSyncState(feedId, state)
                }
            },
        ) {
            orphanFeedIds.forEach { feedId ->
                websiteParsePreferenceRepository.applyUserSyncState(feedId, null)
            }
        }

        // User fields are synchronized above. Automatic detector/cache state is device-local.
        orphanFeedIds.forEach(websiteParsePreferenceRepository::delete)

        syncMutations.captureRssHubSubscriptionSourcesMutation(
            accountId = binding.localAccountId,
            feedIds = orphanFeedIds,
            readStates = {
                orphanFeedIds.associateWith(rssHubSubscriptionRepository::sourceUrl)
            },
            replaceStates = { states ->
                states.forEach { (feedId, sourceUrl) ->
                    rssHubSubscriptionRepository.replaceSyncSource(feedId, sourceUrl)
                }
            },
        ) {
            orphanFeedIds.forEach(rssHubSubscriptionRepository::remove)
        }

        return orphanFeedIds.size
    }
}
