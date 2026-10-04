package me.ash.reader.infrastructure.sync.core

/** 输出原列按短事务复制，规范化来源片段和分页在写事务外执行。 */
internal object SyncSnapshotOutputRecords {
    data class Options(val store: SyncPagedSnapshotStore, val index: SyncPagedRecoveryIndex,
        val outputBundleId: String, val writer: SyncSnapshotPageWriter)

    /** 已完成索引保留真实签名承诺，页面仍使用既有规范记录与字节边界。 */
    fun write(options: Options) {
        options.store.copyIndex(SyncSnapshotScopeCopy.Index(source = options.index.workId,
            target = options.outputBundleId, lanes = options.index.lanes))
        for (lane in options.index.lanes) {
            for (fragments in options.store.canonicalRecordFragments(SyncPagedSnapshotStore.RecordFilter(options.outputBundleId, lane))) {
                SyncSnapshotCancellation.checkpoint()
                options.writer.appendIndexed(lane, fragments)
            }
        }
    }
}
