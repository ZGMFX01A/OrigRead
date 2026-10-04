package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject

/** 组合页面写入器与真实持久化区，业务合并器只依赖这个注入工厂。 */
class SyncSnapshotPageWriterFactory @Inject constructor(private val store: SyncPagedSnapshotStore) {
    fun create(bundleId: String): SyncSnapshotPageWriter = SyncSnapshotPageWriter(
        SyncSnapshotPageWriter.Options(bundleId, SyncBufferedSnapshotCapture(store)))
}
