package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rsshub.RssHubInstance
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceUserSyncState
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

/** CONFIG 只构造当前产品仓库需要的类型列表，不构造第二份整 lane JSON。 */
class SyncPagedConfigRestore @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val identities: SyncPagedEntityIdentity,
) {
    @Inject lateinit var filters: ArticleFilterRepository
    @Inject lateinit var feedConfigs: SyncPagedFeedConfig
    @Inject lateinit var aliasProjection: SyncPagedAliasProjection
    @Inject lateinit var websites: WebsiteRuleRepository
    @Inject lateinit var jsonRules: JsonRuleRepository
    @Inject lateinit var rssSettings: RssHubSettingsRepository
    @Inject lateinit var preferences: WebsiteParsePreferenceRepository
    @Inject lateinit var subscriptions: RssHubSubscriptionRepository
    data class Options(val space: String, val bundle: String, val now: Long)
    private val json = Json { ignoreUnknownKeys = true }

    /** 完整索引及本地恢复副本验证后才替换外部仓库；失败由安装 journal 保持 REBASE_PREPARE。 */
    suspend fun restore(options: Options) {
        restoreFilters(options)
        restoreWebsites(options)
        restoreJsonRules(options)
        restoreRssSettings(options)
        restoreFeedStates(options, "website_parse_preference")
        restoreFeedStates(options, "rsshub_subscription_source")
    }

    /** 删除摘要优先，列表替换不会复活同代次已删除的配置。 */
    private suspend fun forEach(options: Options, type: String, consume: suspend (JsonObject) -> Unit) {
        for (record in store.records(SyncPagedSnapshotStore.RecordFilter(options.bundle, "CONFIG", "ENTITY", type))) {
            val value = record.value
            val tombstone = database.syncInboxDao().findTombstone(options.space, type, text(value, "entitySyncId"))
            if (tombstone == null || tombstone.entityGeneration < value.getValue("generation").jsonPrimitive.content.toLong()) {
                val resolved = aliasProjection.project(SyncPagedAliasProjection.Options(options.space, options.bundle, record))
                consume(feedConfigs.project(SyncPagedFeedConfig.Projection(options.space, options.bundle, resolved)))
            }
        }
    }

    /** Filter 的本地 ID 由身份映射决定，可空 Feed 关联保持真实代次约束。 */
    private suspend fun restoreFilters(options: Options) {
        val rules = mutableListOf<ArticleFilterRule>()
        forEach(options, "filter_rule") { value ->
            val fields = value.getValue("fields").jsonObject
            val mapping = identities.materialize(SyncPagedEntityIdentity.Options(options.space, value, options.now))
            val feed = fields["feedSyncId"]?.takeUnless { it == JsonNull }?.let {
                identities.parent(SyncPagedEntityIdentity.Parent(options.space, fields, "feed"))
            }
            rules += ArticleFilterRule(id = mapping.localId, keyword = text(fields, "keyword"), feedId = feed?.localId,
                feedName = nullableText(fields, "feedName"), type = ArticleFilterRuleType.valueOf(text(fields, "type")),
                enabled = fields.getValue("enabled").jsonPrimitive.boolean)
        }
        filters.replaceRules(rules)
    }

    /** Website 规则携带固定产品 ID，身份与内容必须同时成立。 */
    private suspend fun restoreWebsites(options: Options) {
        val rules = mutableListOf<WebsiteRule>()
        forEach(options, "website_rule") { value ->
            val rule = json.decodeFromString<WebsiteRule>(value.getValue("fields").jsonObject.getValue("rule").toString())
            identities.materialize(SyncPagedEntityIdentity.Options(options.space, value, options.now, localId = rule.id))
            rules += rule
        }
        websites.replaceSyncRules(rules)
    }

    /** JSON 规则只占现有仓库的类型列表，不同时保留 Website/Filter 的复制列表。 */
    private suspend fun restoreJsonRules(options: Options) {
        val rules = mutableListOf<JsonRule>()
        forEach(options, "json_rule") { value ->
            val rule = json.decodeFromString<JsonRule>(value.getValue("fields").jsonObject.getValue("rule").toString())
            identities.materialize(SyncPagedEntityIdentity.Options(options.space, value, options.now, localId = rule.id))
            rules += rule
        }
        jsonRules.replaceSyncRules(rules)
    }

    /** RSSHub 总设置必须唯一，实例列表本身是一个真实产品配置对象。 */
    private suspend fun restoreRssSettings(options: Options) {
        var found = false
        forEach(options, "rsshub_settings") { value ->
            check(!found) { "SNAPSHOT_CORRUPTED: duplicate RSSHub settings" }
            found = true
            identities.materialize(SyncPagedEntityIdentity.Options(options.space, value, options.now, localId = "rsshub-settings"))
            val fields = value.getValue("fields").jsonObject.getValue("settings").jsonObject
            val instances = fields.getValue("instances").jsonArray.map { item ->
                val row = item.jsonObject
                RssHubInstance(id = text(row, "id"), url = text(row, "url"), location = text(row, "location"),
                    maintainer = text(row, "maintainer"), enabled = row.getValue("enabled").jsonPrimitive.boolean,
                    builtIn = row.getValue("builtIn").jsonPrimitive.boolean)
            }
            rssSettings.replaceSyncSettings(RssHubSettings(fields.getValue("enabled").jsonPrimitive.boolean, instances))
        }
        check(found) { "SNAPSHOT_CORRUPTED: RSSHub settings are missing" }
    }

    /** Feed 配置只保留轻量父身份集合，用于清理 baseline 中不存在的用户配置。 */
    private suspend fun restoreFeedStates(options: Options, type: String) {
        val incoming = mutableSetOf<String>()
        val incomingLocalIds = mutableSetOf<String>()
        forEach(options, type) { value ->
            val fields = value.getValue("fields").jsonObject
            val content = fields.getValue(if (type == "website_parse_preference") "preference" else "source").jsonObject
            val feedId = text(content, "feedSyncId")
            check(incoming.add(feedId)) { "SNAPSHOT_CORRUPTED: duplicate CONFIG Feed state" }
            identities.materialize(SyncPagedEntityIdentity.Options(options.space, value, options.now, localId = feedId))
            val feed = identities.parent(SyncPagedEntityIdentity.Parent(options.space, content, "feed"))
            incomingLocalIds.add(feed.localId)
            if (content["__syncAbsent"]?.jsonPrimitive?.content == "true") {
                if (type == "website_parse_preference") preferences.applyUserSyncState(feed.localId, null)
                else subscriptions.replaceSyncSource(feed.localId, null)
            } else if (type == "website_parse_preference") preferences.applyUserSyncState(feed.localId,
                WebsiteParsePreferenceUserSyncState(content.getValue("dynamicRenderingEnabled").jsonPrimitive.boolean,
                    nullableText(content, "preferredRuleId"), nullableText(content, "preferredRuleName")))
            else subscriptions.replaceSyncSource(feed.localId, text(content, "sourceUrl"))
        }
        for (mapping in database.syncIdentityMappingDao().findByType(options.space, type)) {
            if (mapping.localId in incoming) continue
            val localFeedId = cleanupFeed(options.space, mapping.localId) ?: continue
            if (localFeedId in incomingLocalIds) continue
            if (type == "website_parse_preference") preferences.applyUserSyncState(localFeedId, null)
            else subscriptions.replaceSyncSource(localFeedId, null)
        }
    }

    /** 同代次别名引用同一个真实 Feed，缺失配置才清理该业务行。 */
    private suspend fun cleanupFeed(space: String, feedSyncId: String): String? {
        database.syncIdentityMappingDao().findBySyncId(space, "feed", feedSyncId)?.let { return it.localId }
        return database.openHelper.writableDatabase.query("""SELECT m.localId FROM sync_entity_alias a
            JOIN sync_entity_alias b ON b.syncSpaceId=a.syncSpaceId AND b.entityType=a.entityType
              AND b.generation=a.generation AND b.canonicalSyncId=a.canonicalSyncId
            JOIN sync_identity_mapping m ON m.syncSpaceId=b.syncSpaceId AND m.entityType=b.entityType
              AND m.syncId=b.aliasSyncId AND m.generation=b.generation
            WHERE a.syncSpaceId=? AND a.entityType='feed' AND a.aliasSyncId=? ORDER BY m.generation DESC LIMIT 1""",
            arrayOf(space, feedSyncId)).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun text(value: JsonObject, field: String): String = value.getValue(field).jsonPrimitive.content
    private fun nullableText(value: JsonObject, field: String): String? = value[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
}
