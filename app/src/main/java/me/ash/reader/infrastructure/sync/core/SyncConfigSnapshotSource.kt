package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteRuleRepository

/** CONFIG 使用现有产品配置缓存，逐条输出；不再构造第二份整 lane JSON。 */
class SyncConfigSnapshotSource @Inject constructor(
    database: AndroidDatabase,
    private val rows: SyncSnapshotSqlRows,
) {
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val liveDatabase = database
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    @Inject lateinit var filters: ArticleFilterRepository
    @Inject lateinit var websiteRules: WebsiteRuleRepository
    @Inject lateinit var jsonRules: JsonRuleRepository
    @Inject lateinit var rssHubSettings: RssHubSettingsRepository
    @Inject lateinit var websitePreferences: WebsiteParsePreferenceRepository
    @Inject lateinit var subscriptions: RssHubSubscriptionRepository
    @Inject lateinit var aliases: AndroidSyncAliasResolver
    data class Options(val space: String, val accountId: Int, val consume: suspend (SyncGenesisProjectionEntity) -> Unit)
    private data class Absence(val options: Options, val row: JsonObject, val parent: JsonObject,
        val activeIds: Set<String>, val feedIds: Set<String>)
    private val json = Json { encodeDefaults = true }

    /** 与旧格式复用相同产品来源和载荷字段，父 Feed 的身份按需查询。 */
    suspend fun forEach(options: Options) {
        appendFilters(options)
        for (rule in websiteRules.listSyncRules()) append(options, "website_rule" to rule.id,
            buildJsonObject { put("rule", json.parseToJsonElement(json.encodeToString(WebsiteRule.serializer(), rule))) })
        for (rule in jsonRules.listSyncRules()) append(options, "json_rule" to rule.id,
            buildJsonObject { put("rule", json.parseToJsonElement(json.encodeToString(JsonRule.serializer(), rule))) })
        append(options, "rsshub_settings" to "rsshub-settings", buildJsonObject { put("settings", settings(rssHubSettings.current())) })
        appendFeedSettings(options)
    }

    /** 过滤规则保留可空 Feed 绑定，关联丢失属于身份错误而不是自动降级为全局规则。 */
    private suspend fun appendFilters(options: Options) {
        for (rule in filters.getAll()) {
            val feed = rule.feedId?.let { mapping(options.space, "feed", it) }
            append(options, "filter_rule" to rule.id, buildJsonObject {
                put("keyword", rule.keyword); put("type", rule.type.name); put("enabled", rule.enabled)
                put("feedSyncId", feed?.syncId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                put("feedGeneration", feed?.generation?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                put("feedName", rule.feedName?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            })
        }
    }

    /** 用户配置各读取一次；轻量 Feed ID 集合避免每条 Feed 都重新扫描配置文件。 */
    private suspend fun appendFeedSettings(options: Options) {
        val sql = database.openHelper.writableDatabase
        val ids = sql.query("SELECT id FROM feed WHERE accountId=?", arrayOf(options.accountId)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val preferences = websitePreferences.listUserSyncStates(ids)
        val sources = subscriptions.listSyncSources(ids)
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql,
            "SELECT rowid FROM feed WHERE accountId=? ORDER BY rowid", arrayOf(options.accountId))) { id ->
            val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "feed", id))
            val localId = row.getValue("id").jsonPrimitive.content
            val feed = mapping(options.space, "feed", localId)
            val preference = preferences[localId]
            if (preference != null) append(options, "website_parse_preference" to feed.syncId, buildJsonObject {
                put("preference", buildJsonObject {
                    put("feedSyncId", feed.syncId); put("feedGeneration", feed.generation)
                    put("dynamicRenderingEnabled", preference.dynamicRenderingEnabled)
                    put("preferredRuleId", preference.preferredRuleId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                    put("preferredRuleName", preference.preferredRuleName?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                })
            })
            val source = sources[localId]
            if (source != null) append(options, "rsshub_subscription_source" to feed.syncId, buildJsonObject {
                put("source", buildJsonObject {
                    put("feedSyncId", feed.syncId); put("feedGeneration", feed.generation); put("sourceUrl", source)
                })
            })
        }
        appendAbsences(options, mapOf("website_parse_preference" to preferences.keys, "rsshub_subscription_source" to sources.keys), ids)
    }

    /** 已删除用户配置仍携带缺席寄存器与旧候选，不能因产品行消失而重置因果历史。 */
    private suspend fun appendAbsences(options: Options, active: Map<String, Set<String>>, feedIds: Set<String>) {
        val sql = database.openHelper.writableDatabase
        rows.forEachRowId(SyncSnapshotSqlRows.Selection(sql, """SELECT v.rowid FROM sync_field_version v
            JOIN sync_identity_mapping m ON m.syncSpaceId=v.syncSpaceId AND m.entityType=v.entityType
              AND m.syncId=v.entitySyncId AND m.generation=v.entityGeneration
            WHERE v.syncSpaceId=? AND ((v.entityType='website_parse_preference' AND v.fieldId='preference')
              OR (v.entityType='rsshub_subscription_source' AND v.fieldId='source'))
            AND NOT EXISTS(SELECT 1 FROM sync_tombstone t WHERE t.syncSpaceId=m.syncSpaceId
              AND t.entityType=m.entityType AND t.entitySyncId=m.syncId AND t.entityGeneration>=m.generation) ORDER BY v.rowid""",
            arrayOf(options.space))) { id ->
            val row = rows.readRow(SyncSnapshotSqlRows.Row(sql, "sync_field_version", id))
            val type = row.getValue("entityType").jsonPrimitive.content
            val parent = json.parseToJsonElement(row.getValue("valueJson").jsonPrimitive.content).jsonObject
            if (parent["__syncAbsent"]?.jsonPrimitive?.content == "true") appendAbsence(Absence(options, row, parent, active[type].orEmpty(), feedIds))
        }
    }

    /** 只有当前账户的存活父组件且物理配置缺席，才输出归一的缺席业务值。 */
    private suspend fun appendAbsence(input: Absence) {
        val (options, row, parent, activeIds, feedIds) = input
        val feed = aliases.resolveMapping(options.space, "feed", parent.getValue("feedSyncId").jsonPrimitive.content,
            parent.getValue("feedGeneration").jsonPrimitive.long) ?: return
        if (feed.localId !in feedIds || feed.localId in activeIds) return
        val fields = buildJsonObject {
            put(row.getValue("fieldId").jsonPrimitive.content, buildJsonObject {
                put("feedSyncId", parent.getValue("feedSyncId")); put("feedGeneration", parent.getValue("feedGeneration"))
                put("__syncAbsent", true)
            })
        }
        options.consume(SyncGenesisProjectionEntity(row.getValue("entityType").jsonPrimitive.content,
            row.getValue("entitySyncId").jsonPrimitive.content, row.getValue("entityGeneration").jsonPrimitive.long, fields.toString()))
    }

    /** 配置输出沿用已 backfill 的身份，不能在固定视图内临时创造新的映射。 */
    private suspend fun append(options: Options, identity: Pair<String, String>, fields: JsonObject) {
        val mapping = mapping(options.space, identity.first, identity.second)
        options.consume(SyncGenesisProjectionEntity(identity.first, mapping.syncId, mapping.generation, fields.toString()))
    }

    /** 每条配置只持有自己的身份，不将整个 mapping 表装入内存。 */
    private suspend fun mapping(space: String, type: String, localId: String): SyncIdentityMappingEntity =
        database.syncIdentityMappingDao().findByLocalId(space, type, localId)
            ?: error("Missing Genesis CONFIG mapping: $type/$localId")

    /** RSSHub 的真实配置列表由产品仓库持有，输出协议既有字段。 */
    private fun settings(value: RssHubSettings): JsonObject = buildJsonObject {
        put("enabled", value.enabled)
        put("instances", buildJsonArray {
            value.instances.forEach { instance -> add(buildJsonObject {
                put("id", instance.id); put("url", instance.url); put("location", instance.location)
                put("maintainer", instance.maintainer); put("enabled", instance.enabled); put("builtIn", instance.builtIn)
            }) }
        })
    }
}
