package me.ash.reader.infrastructure.sync.core

/** 原因果向量的不可变轻量观察事实，不携带正文或改变签名材料。 */
internal data class SyncSnapshotCausalFacts(
    val prefixes: List<Pair<Pair<String, String>, Long>>,
    val baselines: Set<Pair<String, String>>,
)

/** 读取阶段仅保留最近一份因果向量；逐字不同的向量必须重新按既有 codec 展开。 */
internal class SyncSnapshotCausalFactsReader {
    private data class Recent(val raw: String, val facts: SyncSnapshotCausalFacts)
    private var recent: Recent? = null

    /** 解码约束沿用原 codec；派生事实只减少重复工作，原字段绑定验证仍逐条执行。 */
    fun read(raw: String?): SyncSnapshotCausalFacts? {
        if (raw == null) return null
        recent?.takeIf { it.raw == raw }?.let { return it.facts }
        val context = SyncCausalContextCodec.decode(raw)
        val prefixes = context?.lanes.orEmpty().flatMap { lane -> lane.actors.map { actor ->
            (lane.replicationLaneId to actor.actorIncarnationId) to actor.prefix
        } }
        val baselines = context?.observedGenesisBaselinesByLane.orEmpty().flatMap { (lane, ids) -> ids.map { lane to it } }.toSet()
        val facts = SyncSnapshotCausalFacts(prefixes, baselines)
        recent = Recent(raw, facts)
        return facts
    }
}
