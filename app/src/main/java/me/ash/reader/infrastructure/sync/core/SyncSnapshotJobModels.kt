package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.Serializable

/** 受理和完成是不同状态，客户端只有看到 COMPLETED 才推进同步完成。 */
@Serializable
data class SyncSnapshotJobStatus(
    val snapshotBundleId: String,
    val rootHash: String,
    val generation: Long,
    val state: String,
    val phase: String,
    val error: String? = null,
)

/** 作业恢复身份包含来源、选定范围和处理版本，不能接管另一固定输入。 */
internal data class SyncSnapshotJobInput(
    val space: String,
    val peer: String,
    val manifest: SyncPagedSnapshotManifest,
)
