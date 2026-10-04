package me.ash.reader.infrastructure.sync.core

import java.util.Base64
import javax.inject.Inject

/** 单个会话只持有当前字节页，断线进度由快照专用数据库保留。 */
class SyncPagedSnapshotTransfer @Inject constructor(private val store: SyncPagedSnapshotStore) {
    data class Options(
        val manifest: SyncPagedSnapshotManifest,
        val session: SyncEndpointSession,
        val authorize: suspend () -> Unit,
        val now: Long,
    )

    /** 只拉取缺页；签名作者与传输成员的权限在每次请求前后重新核验。 */
    suspend fun receive(options: Options) {
        val owner = store.lifecycle.pin(options.manifest.snapshotBundleId)
        try { receivePinned(options) } finally { store.lifecycle.release(options.manifest.snapshotBundleId, owner) }
    }

    /** 固定 root 在网络等待期间持有 lease，失败保留经过验证的页面。 */
    private suspend fun receivePinned(options: Options) {
        val manifest = options.manifest
        options.authorize()
        store.beginReceive(manifest, options.now)
        if (store.reusable(manifest)) return
        if (store.find(manifest.snapshotBundleId)?.state != "VERIFIED") store.lifecycle.reserve(manifest, manifest.authorDeviceId, options.now)
        for (lane in manifest.lanes) {
            for (index in lane.pageHashes.indices) {
                options.authorize()
                if (store.hasVerifiedPage(manifest, lane.replicationLaneId, index)) continue
                // 收窄策略后的 bundle ID 才代表本次签名视图，不能用原始 source ID 代替。
                val page = options.session.fetchSnapshotPage(manifest.snapshotBundleId, lane.replicationLaneId, index)
                options.authorize()
                require(page.replicationLaneId == lane.replicationLaneId && page.pageIndex == index) {
                    "SNAPSHOT_CORRUPTED: fetched page identity differs from requested page"
                }
                store.receivePage(manifest, page)
            }
        }
        options.authorize()
        store.verifyAndPublish(manifest, options.now)
    }

    /** 清单绑定后查询已接收的页号，补齐缺页才提交安装，不依赖内存发送计数。 */
    suspend fun push(options: Options) {
        val owner = store.lifecycle.pin(options.manifest.snapshotBundleId)
        try { pushPinned(options) } finally { store.lifecycle.release(options.manifest.snapshotBundleId, owner) }
    }

    /** 页面发送、续传状态与 commit 共同持有固定视图。 */
    private suspend fun pushPinned(options: Options) {
        val manifest = options.manifest
        options.authorize()
        options.session.pushPagedSnapshotManifest(manifest)
        options.authorize()
        val status = options.session.getSnapshotPageStatus(manifest.snapshotBundleId)
        validateStatus(manifest, status)
        for (lane in manifest.lanes) {
            val received = status.receivedPages.getValue(lane.replicationLaneId).toSet()
            for (index in lane.pageHashes.indices) {
                options.authorize()
                if (index in received) continue
                check(store.hasVerifiedPage(manifest, lane.replicationLaneId, index)) {
                    "SNAPSHOT_CORRUPTED: local published page is missing"
                }
                val bytes = store.readPage(manifest.snapshotBundleId, lane.replicationLaneId, index)
                options.session.pushSnapshotPage(manifest.snapshotBundleId,
                    SyncSnapshotBytePage(lane.replicationLaneId, index, Base64.getEncoder().encodeToString(bytes)))
            }
        }
        options.authorize()
        options.session.commitPagedSnapshot(manifest.snapshotBundleId)
    }

    /** 续传游标必须完整对应本次签名 root，未知 lane、重复页和越界值显式拒绝。 */
    private fun validateStatus(manifest: SyncPagedSnapshotManifest, status: SyncSnapshotPageStatus) {
        require(status.rootHash == manifest.rootHash) { "SNAPSHOT_CONFLICT: resume status names another Snapshot" }
        val lanes = manifest.lanes.associateBy { it.replicationLaneId }
        require(status.receivedPages.keys == lanes.keys) { "SNAPSHOT_CORRUPTED: page status lanes differ from signed index" }
        for ((id, indices) in status.receivedPages) {
            val lane = lanes.getValue(id)
            require(indices.toSet().size == indices.size && indices.all { it in lane.pageHashes.indices }) {
                "SNAPSHOT_CORRUPTED: invalid persisted page status"
            }
        }
    }
}
