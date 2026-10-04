package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 清单式分页格式独立于历史整包快照，作者签名提交所有页面摘要。 */
const val PAGED_SNAPSHOT_FORMAT = 3
/** 单页原始字节目标；一条业务记录可以跨页，不限制快照总量。 */
const val SNAPSHOT_PAGE_BYTES = 512 * 1024

@Serializable
data class SyncSnapshotLanePages(
    val replicationLaneId: String,
    val frontierJson: String,
    val pageHashes: List<String>,
    val recordCount: Long,
)

@Serializable
data class SyncPagedSnapshotManifest(
    val formatVersion: Int,
    val snapshotBundleId: String,
    val sourceSnapshotBundleId: String,
    val syncSpaceId: String,
    val snapshotClass: String,
    val genesisBaselineId: String,
    val crossDbCutId: String,
    val policyHash: String,
    val capturedAt: Long,
    val lanes: List<SyncSnapshotLanePages>,
    val coverage: SyncCoverage,
    val requiredCoreShardIds: List<String>,
    val authStabilityCheckpoint: String?,
    val coverageCommitment: String?,
    val authorDeviceId: String,
    val rootHash: String,
    val authorSignature: String,
)

@Serializable
data class SyncSnapshotBytePage(
    val replicationLaneId: String,
    val pageIndex: Int,
    val bytesBase64: String,
)

/** 续传以已验证并持久化的页序号为准，不依赖当前连接的发送进度。 */
@Serializable
data class SyncSnapshotPageStatus(
    val rootHash: String,
    val receivedPages: Map<String, List<Int>>,
)

/** 唯一键属于业务记录，与任意页面切分位置无关。 */
@Serializable
data class SyncSnapshotRecord(
    val kind: String,
    val key: String,
    val value: JsonObject,
)
