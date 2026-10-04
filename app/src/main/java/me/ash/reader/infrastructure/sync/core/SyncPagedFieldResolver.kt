package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 分页字段裁决只持有当前候选和 winner，完整因果极大集通过持久索引重读。 */
class SyncPagedFieldResolver @Inject constructor(private val store: SyncPagedSnapshotStore) {
    private val causalFacts = SyncSnapshotCausalFactsReader()
    data class Options(val filter: SyncPagedSnapshotStore.RecordFilter, val fieldId: String, val includeSource: Boolean = true)
    internal data class IndexedOptions(val fieldId: String, val fields: () -> Sequence<SyncSnapshotFieldMetadata>,
        val includeSource: Boolean = false, val policy: SyncGenesisMergePolicy? = null)
    private data class Decision<T>(val candidate: SyncFieldCandidate, val digest: String, val location: T)

    /** 所有候选参与因果淘汰，页面顺序不决定已读、收藏或普通字段的胜者。 */
    fun resolve(options: Options): SyncSnapshotRecord {
        return resolveIndexed(IndexedOptions(fieldId = options.fieldId, fields = { store.derived.fields(options.filter) },
            includeSource = options.includeSource))
    }

    /** 全部候选只输入轻列，胜者由保存的原业务键精确读取一次。 */
    internal fun resolveIndexed(options: IndexedOptions): SyncSnapshotRecord {
        val selected = winner(options.fieldId, options.fields().map { field ->
            Decision(candidate(field), field.valueDigest, field)
        }, options.policy)
        val filter = SyncPagedSnapshotStore.RecordFilter(selected.bundle, selected.lane, kind = "FIELD_VERSION")
        return store.fieldRecord(filter, selected.key, options.includeSource)
    }

    /** 别名物化复用完整候选裁决，每次重读仅包含当前组件的同一字段。 */
    fun resolveRecords(fieldId: String, records: () -> Sequence<SyncSnapshotRecord>, mergePolicy: SyncGenesisMergePolicy? = null): SyncSnapshotRecord {
        val token = winner(fieldId, records().map { record ->
            val value = candidate(record)
            Decision(value, SyncOperationCanonicalizer.sha256Hex(value.valueJson), value.token)
        }, mergePolicy)
        return records().first { it.value.getValue("versionToken").jsonPrimitive.content == token }
    }

    /** 内存寄存器与分页轻索引复用原因果裁决，同 token 不同值仍明确拒绝。 */
    private fun <T> winner(fieldId: String, entries: Sequence<Decision<T>>, mergePolicy: SyncGenesisMergePolicy?): T {
        val candidates = linkedMapOf<String, SyncFieldCandidate>()
        val digests = mutableMapOf<String, String>()
        val locations = mutableMapOf<String, T>()
        val index = SyncFieldCausalIndex()
        for ((current, digest, location) in entries) {
            require(digests[current.token]?.let { it == digest } != false) { "GENESIS_VERSION_COLLISION: inconsistent field value" }
            digests[current.token] = digest
            if (current.token !in locations) locations[current.token] = location
            val light = current.copy(valueJson = current.valueJson.takeIf { it == "true" || it == "false" } ?: "")
            candidates[light.token] = light
            index.add(light, causalFacts.read(light.causalContextJson))
        }
        val maximal = candidates.values.filterNot(index::dominated)
        val preference = mergePolicy ?: when (fieldId) {
            "isUnread" -> SyncGenesisMergePolicy.READ_WINS
            "isStarred" -> SyncGenesisMergePolicy.STARRED_WINS
            else -> SyncGenesisMergePolicy.DETERMINISTIC
        }
        val policy = if (maximal.all { it.source == SyncVersionSource.GENESIS }) preference else SyncGenesisMergePolicy.DETERMINISTIC
        val winner = maximal.reduceOrNull { previous, current -> SyncVersionResolver.resolve(listOf(previous, current), policy) }
        requireNotNull(winner) {
            "SNAPSHOT_CORRUPTED: field has no causally maximal candidate"
        }
        return locations.getValue(winner.token)
    }

    /** 轻量输入保留完整因果上下文，只移除与因果淘汰无关的大实际值。 */
    private fun candidate(field: SyncSnapshotFieldMetadata): SyncFieldCandidate =
        SyncFieldCandidate(fieldId = field.fieldId, valueJson = field.preferenceValue, token = field.token,
            source = SyncVersionToken.source(field.token), causalContextJson = field.causalContext, logicalClock = field.clock)

    /** 只恢复作者签名承诺的因果证据，缺省值与既有字段寄存器保持一致。 */
    private fun candidate(record: SyncSnapshotRecord): SyncFieldCandidate {
        val value = record.value
        val token = value.getValue("versionToken").jsonPrimitive.content
        return SyncFieldCandidate(fieldId = value.getValue("fieldId").jsonPrimitive.content,
            valueJson = value.getValue("valueJson").jsonPrimitive.content, token = token, source = SyncVersionToken.source(token),
            causalContextJson = value["causalContextJson"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            logicalClock = value["logicalClock"]?.jsonPrimitive?.longOrNull ?: INITIAL_LOGICAL_CLOCK)
    }

    companion object {
        /** Genesis 没有 Operation 逻辑时钟，沿用寄存器的初始值。 */
        private const val INITIAL_LOGICAL_CLOCK = 0L
    }
}
