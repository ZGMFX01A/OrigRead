package me.ash.reader.infrastructure.sync.identity

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.source.SourceUrlNormalizer

/**
 * R10 canonical identity v1.
 *
 * Canonical Key 只负责发现候选等价实体，不替代 Sync ID。算法一旦进入同步历史就必须版本化；
 * 未来即使 URL 归一化策略升级，也不能静默重写已经保存的 v1 key。
 */
object SyncCanonicalIdentity {
    const val CANONICAL_KEY_VERSION = 1
    const val RELATION_ID_VERSION = 1
    const val CONFIG_RULE_ID_VERSION = 1

    private val canonicalUuidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    /** Feed 候选身份：source type + 保守 URL comparison key。 */
    fun feedKey(sourceType: SourceType, sourceUrl: String): String =
        feedKey(sourceType.name.lowercase(Locale.ROOT), sourceUrl)

    internal fun feedKey(sourceTypeWire: String, sourceUrl: String): String {
        val normalizedType = sourceTypeWire.trim().lowercase(Locale.ROOT)
        val normalizedUrl = SourceUrlNormalizer.comparisonKey(sourceUrl)
        return "feed:v$CANONICAL_KEY_VERSION:${sha256Framed(normalizedType, normalizedUrl)}"
    }

    /**
     * Article v1 只使用当前两端都已经持久化、可高置信度复现的 link。
     *
     * GUID / JSON stable id 目前没有在 Android 与 Desktop 两端都独立持久化，所以 Genesis Backfill
     * 不猜测标题/时间等弱身份。link 缺失时返回 null，宁可暂时保留两个实体，也不错误合并。
     */
    fun articleKey(feedCanonicalKey: String?, articleLink: String?): String? {
        val feedKey = feedCanonicalKey?.takeIf(String::isNotBlank) ?: return null
        val link = articleLink?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val normalizedLink = SourceUrlNormalizer.comparisonKey(link)
        return "article:v$CANONICAL_KEY_VERSION:${sha256Framed(feedKey, "link", normalizedLink)}"
    }

    /** 随机、不可从 Local PK 推导的普通 Sync ID。 */
    fun newSyncId(): String = UUID.randomUUID().toString()

    /** UUID 型本地实体可采用其 UUID 值作为 Sync ID，但仍必须经过 Mapping 层。 */
    fun adoptUuidOrNull(localId: String): String? {
        val trimmed = localId.trim()
        if (!canonicalUuidRegex.matches(trimmed)) return null
        return UUID.fromString(trimmed).toString()
    }

    /** 没有独立主键的关系对象，用端点 Sync ID 生成确定性关系 Sync ID。 */
    fun relationSyncId(entityType: SyncEntityType, vararg endpointSyncIds: String): String =
        "rel:v$RELATION_ID_VERSION:${sha256Framed(entityType.wireName, *endpointSyncIds)}"

    /** 关系表在本地 Mapping 中使用的稳定复合 Local ID；不要求可逆。 */
    fun relationLocalId(entityType: SyncEntityType, vararg endpointLocalIds: String): String =
        "local-rel:v$RELATION_ID_VERSION:${sha256Framed(entityType.wireName, *endpointLocalIds)}"

    /**
     * User-authored rule ids are domain identifiers, not database primary keys. Derive a stable
     * cross-device Sync ID so two peers independently modifying the same Website/JSON rule converge
     * on one protocol entity even when the rule id itself is not a UUID.
     */
    fun configRuleSyncId(entityType: SyncEntityType, ruleId: String): String =
        "cfg:v$CONFIG_RULE_ID_VERSION:${sha256Framed(entityType.wireName, ruleId.trim())}"

    private fun sha256Framed(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
