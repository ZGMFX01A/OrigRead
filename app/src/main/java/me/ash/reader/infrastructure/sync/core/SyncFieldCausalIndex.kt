package me.ash.reader.infrastructure.sync.core

/** 不保留历史正文，只累计最大两位独立观察者以排除候选自己的上下文。 */
internal class SyncFieldCausalIndex {
    private data class Observation(val token: String, val prefix: Long)
    private val prefixes = mutableMapOf<Pair<String, String>, List<Observation>>()
    private var baselines: Set<Pair<String, String>> = emptySet()
    private var combinedBaselines: MutableSet<Pair<String, String>>? = null

    /** 隐式 actor 顺序和显式冻结向量均可证明某个候选已被观察。 */
    fun add(candidate: SyncFieldCandidate, prepared: SyncSnapshotCausalFacts? = null) {
        val dot = SyncVersionToken.parseOperationDot(candidate.token) ?: return
        observe(dot.replicationLaneId to dot.actorIncarnationId, Observation(candidate.token, dot.sequence - 1L))
        val facts = prepared ?: SyncSnapshotCausalFactsReader().read(candidate.causalContextJson) ?: return
        for ((key, prefix) in facts.prefixes) observe(key, Observation(candidate.token, prefix))
        addBaselines(facts.baselines)
    }

    /** 单个观察者借用原集合；不同向量才复制并集，不能修改共享因果事实。 */
    private fun addBaselines(values: Set<Pair<String, String>>) {
        if (values.isEmpty() || baselines === values) return
        if (baselines.isEmpty()) { baselines = values; return }
        val combined = combinedBaselines ?: baselines.toMutableSet().also { combinedBaselines = it }
        combined.addAll(values)
        baselines = combined
    }

    /** 仅查询目标 Dot，避免逐候选重读数据库并重新解析所有上下文。 */
    fun dominated(candidate: SyncFieldCandidate): Boolean {
        val dot = SyncVersionToken.parseOperationDot(candidate.token)
        if (dot == null) {
            val parts = candidate.token.split('|')
            return (parts[2] to parts[1]) in baselines
        }
        return prefixes[dot.replicationLaneId to dot.actorIncarnationId].orEmpty()
            .any { it.token != candidate.token && it.prefix >= dot.sequence }
    }

    /** 两个不同观察者足够回答“除自身外是否有人覆盖”这一查询。 */
    private fun observe(key: Pair<String, String>, observation: Observation) {
        val previous = prefixes[key].orEmpty()
        val maximum = maxOf(observation.prefix, previous.firstOrNull { it.token == observation.token }?.prefix ?: observation.prefix)
        prefixes[key] = (previous.filter { it.token != observation.token } + observation.copy(prefix = maximum))
            .sortedByDescending { it.prefix }.take(INDEPENDENT_OBSERVERS)
    }

    companion object {
        /** 排除一个候选自身后仍保留一份最高观察证明。 */
        private const val INDEPENDENT_OBSERVERS = 2
    }
}
