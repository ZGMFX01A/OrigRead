package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

/**
 * Reconciles syncable CONFIG that is persisted outside the Reader Room database.
 *
 * Local CONFIG writes cannot be made ACID with the Room Outbox when their durable store is a JSON
 * file or SharedPreferences. Every sync opportunity therefore compares the durable external fact
 * with the current CONFIG FieldVersion/Tombstone state and emits any missing local mutation before
 * new operations are built. Remote Inbox replay must run before this reconciler to avoid echoing a
 * partially materialized remote operation as a new local change.
 */
@Singleton
class SyncExternalConfigReconciler @Inject constructor(
    private val database: AndroidDatabase,
    private val websiteRuleRepository: WebsiteRuleRepository,
    private val jsonRuleRepository: JsonRuleRepository,
    private val rssHubSettingsRepository: RssHubSettingsRepository,
    private val websiteParsePreferenceRepository: WebsiteParsePreferenceRepository,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    private val syncMutations: LibrarySyncMutationCapture,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun reconcile(syncSpaceId: String): Int {
        val binding = database.syncRuntimeDao().findBindingBySpace(syncSpaceId) ?: return 0
        if (binding.lifecycleState != SyncSpaceLifecycleState.ACTIVE.name) return 0
        if (database.syncRuntimeDao().findActiveActor(syncSpaceId) == null) return 0

        var changed = 0
        changed +=
            syncMutations.reconcileAtomicConfigState(
                accountId = binding.localAccountId,
                entityType = SyncEntityType.WEBSITE_RULE,
                fieldId = "rule",
                actual =
                    websiteRuleRepository.listSyncRules()
                        .associate { rule ->
                            rule.id to encodeWebsiteRule(rule)
                        },
            )
        changed +=
            syncMutations.reconcileAtomicConfigState(
                accountId = binding.localAccountId,
                entityType = SyncEntityType.JSON_RULE,
                fieldId = "rule",
                actual =
                    jsonRuleRepository.listSyncRules()
                        .associate { rule ->
                            rule.id to encodeJsonRule(rule)
                        },
            )
        changed +=
            syncMutations.reconcileAtomicConfigState(
                accountId = binding.localAccountId,
                entityType = SyncEntityType.RSSHUB_SETTINGS,
                fieldId = "settings",
                actual = mapOf("rsshub-settings" to encodeRssHubSettings()),
            )

        val feedMappings =
            database.feedDao().queryAll(binding.localAccountId)
                .associate { feed ->
                    val mapping =
                        database.syncIdentityMappingDao().findByLocalId(
                            syncSpaceId,
                            SyncEntityType.FEED.wireName,
                            feed.id,
                        ) ?: throw IllegalStateException(
                            "Active Feed has no Sync mapping during CONFIG reconciliation: ${feed.id}"
                        )
                    feed.id to mapping
                }

        val websitePreferences = linkedMapOf<String, JsonElement>()
        feedMappings.forEach { (localFeedId, feedMapping) ->
            websiteParsePreferenceRepository.getUserSyncState(localFeedId)?.let { state ->
                websitePreferences[feedMapping.syncId] =
                    buildJsonObject {
                        put("feedSyncId", feedMapping.syncId)
                        put("feedGeneration", feedMapping.generation)
                        put("dynamicRenderingEnabled", state.dynamicRenderingEnabled)
                        put(
                            "preferredRuleId",
                            state.preferredRuleId?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put(
                            "preferredRuleName",
                            state.preferredRuleName?.let(::JsonPrimitive) ?: JsonNull,
                        )
                    }
            }
        }
        changed +=
            syncMutations.reconcileAtomicConfigState(
                accountId = binding.localAccountId,
                entityType = SyncEntityType.WEBSITE_PARSE_PREFERENCE,
                fieldId = "preference",
                actual = websitePreferences,
            )

        val rssHubSources = linkedMapOf<String, JsonElement>()
        feedMappings.forEach { (localFeedId, feedMapping) ->
            rssHubSubscriptionRepository.sourceUrl(localFeedId)?.let { sourceUrl ->
                rssHubSources[feedMapping.syncId] =
                    buildJsonObject {
                        put("feedSyncId", feedMapping.syncId)
                        put("feedGeneration", feedMapping.generation)
                        put("sourceUrl", sourceUrl)
                    }
            }
        }
        changed +=
            syncMutations.reconcileAtomicConfigState(
                accountId = binding.localAccountId,
                entityType = SyncEntityType.RSSHUB_SUBSCRIPTION_SOURCE,
                fieldId = "source",
                actual = rssHubSources,
            )
        return changed
    }

    private fun encodeWebsiteRule(rule: WebsiteRule): JsonElement =
        json.parseToJsonElement(json.encodeToString(WebsiteRule.serializer(), rule))

    private fun encodeJsonRule(rule: JsonRule): JsonElement =
        json.parseToJsonElement(json.encodeToString(JsonRule.serializer(), rule))

    private fun encodeRssHubSettings(): JsonElement {
        val settings = rssHubSettingsRepository.current()
        return buildJsonObject {
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
    }
}
