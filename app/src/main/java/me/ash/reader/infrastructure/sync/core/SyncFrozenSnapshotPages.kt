package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 业务固定视图已复制到专用索引，分页、规范序列化与 hash 不再持有 SnapshotBarrier。 */
internal object SyncFrozenSnapshotPages {
    data class Options(val store: SyncPagedSnapshotStore, val bundle: String, val cut: SyncGenesisCut)

    /** 从冻结记录逐条生成页，后来的 Reader/Chat 写入不会进入这份 cut。 */
    suspend fun build(options: Options): List<SyncSnapshotLanePages> = withContext(Dispatchers.Default) {
        SyncSnapshotTrace.suspendPhase("capture.normalize", options.bundle) { options.store.normalizeFrozenRecords(options.bundle) }
        val writer = SyncSnapshotPageWriter(SyncSnapshotPageWriter.Options(options.bundle, options.store,
            recordsAlreadyIndexed = true))
        for (lane in SyncReplicationLane.entries) {
            for (fragments in options.store.canonicalRecordFragments(SyncPagedSnapshotStore.RecordFilter(options.bundle, lane.wireName))) {
                writer.appendIndexed(lane.wireName, fragments)
            }
        }
        writer.finish(SyncReplicationLane.entries.associate { lane -> lane.wireName to
            SyncGenesisCodec.encodeFrontiers(mapOf(lane.wireName to options.cut.laneFrontiers[lane.wireName].orEmpty())) })
    }
}
