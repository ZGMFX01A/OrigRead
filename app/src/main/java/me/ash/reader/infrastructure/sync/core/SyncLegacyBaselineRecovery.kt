package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.CancellationException
import me.ash.reader.infrastructure.db.AndroidDatabase

/** Durable Peer 旧格式基线恢复独立编译，避免会话协程超过 JVM 单方法字节码上限。 */
internal object SyncLegacyBaselineRecovery {
    data class Input(
        val space: String, val account: Int, val lanes: Set<String>, val now: Long,
        val session: SyncEndpointSession, val installer: AndroidSnapshotInstallService,
        val remote: SyncRemoteApplyCoordinator, val database: AndroidDatabase, val streaming: Boolean,
        val report: suspend () -> Unit,
        val stage: suspend (SyncSnapshotStreamManifestWire, Set<String>) -> ((String) -> SyncSnapshotShardWire),
        val fetchBlobs: suspend (List<SyncSnapshotBlobIndex>, Set<String>) -> Unit,
        val recover: suspend (Int, SyncSnapshotBundleWire?) -> Unit,
    )

    /** 同步格式由协商能力决定；远端缺失基线时沿用真实本地 Recovery，不制造空快照。 */
    suspend fun run(input: Input): String? {
        if (input.streaming) {
            val manifest = input.session.getLatestSnapshotStreamManifest("GC_BASELINE", input.lanes.toList())
                ?: input.session.getLatestSnapshotStreamManifest("WORKING", input.lanes.toList())
            if (manifest != null) return installStream(input, manifest)
        } else {
            val snapshot = input.session.getLatestSnapshot("GC_BASELINE", input.lanes.toList())
                ?: input.session.getLatestSnapshot("WORKING", input.lanes.toList())
            if (snapshot != null) return installWire(input, snapshot)
        }
        input.recover(input.account, null)
        return null
    }

    /** 完整流式清单必须匹配空间/域；只有完成安装才删除暂存 shard。 */
    private suspend fun installStream(input: Input, manifest: SyncSnapshotStreamManifestWire): String? {
        requireScope(input, manifest.syncSpaceId, manifest.shardDescriptors.map { it.replicationLaneId }.toSet())
        val peer = trustedAuthor(input, manifest.authorDeviceId)
        input.report()
        try {
            val loader = input.stage(manifest, input.lanes)
            val result = input.installer.installStream(input.account, manifest, peer, loader, input.now, input.lanes)
            input.database.syncGenesisDao().deleteStreamShards(input.space, manifest.snapshotBundleId)
            input.database.syncGenesisDao().deleteStreamStage(input.space, manifest.snapshotBundleId)
            input.report()
            return result.snapshotBundleId
        } catch (error: Throwable) {
            // 取消必须向上抛出；需要本地恢复时重新核对不可变远端目标，其他安装错误显式失败。
            if (error is CancellationException) throw error
            if (error !is SyncLocalRecoverySnapshotRequiredException) throw SyncSessionSnapshotInstallException(
                "Failed to install streamed baseline snapshot: ${error.message}", error)
            val target = input.session.getLatestSnapshot(manifest.snapshotClass, input.lanes.toList())
                ?: throw SyncRebaseUnsafeException("REBASE_UNSAFE: recovery target disappeared while streamed baseline was staged")
            check(target.snapshotBundleId == manifest.snapshotBundleId && target.rootHash == manifest.rootHash) {
                "REBASE_UNSAFE: recovery target changed while streamed baseline was staged"
            }
            input.recover(input.account, target)
            return null
        }
    }

    /** 聚合旧格式仅用于既有 Durable Peer；LAN 继续使用分页协议。 */
    private suspend fun installWire(input: Input, snapshot: SyncSnapshotBundleWire): String? {
        requireScope(input, snapshot.syncSpaceId, snapshot.shards.map { it.replicationLaneId }.toSet())
        val peer = trustedAuthor(input, snapshot.authorDeviceId)
        input.report()
        try {
            input.fetchBlobs(snapshot.shards.map(SyncSnapshotBlobIndex::fromShard), input.lanes)
            val result = input.installer.installWire(input.account, snapshot, peer, input.now, input.lanes)
            input.report()
            return result.snapshotBundleId
        } catch (error: Throwable) {
            // Recovery 请求沿用原始目标；普通错误不退化为成功结果。
            if (error is CancellationException) throw error
            if (error !is SyncLocalRecoverySnapshotRequiredException) throw SyncSessionSnapshotInstallException(
                "Failed to install baseline snapshot: ${error.message}", error)
            input.recover(input.account, snapshot)
            return null
        }
    }

    /** 作者身份通过当前已验证空间信任查找，不能接受清单自报公钥。 */
    private suspend fun trustedAuthor(input: Input, author: String?): SyncPeerKey {
        val id = author ?: throw SyncSessionBaselineMissingException("Baseline snapshot has no author")
        return input.remote.trustedPeer(input.space, id)
            ?: throw SyncSessionBaselineMissingException("Baseline snapshot author is not trusted")
    }

    /** 旧格式也必须覆盖所有协商域，不能把部分视图安装成完整基线。 */
    private fun requireScope(input: Input, space: String, lanes: Set<String>) {
        check(space == input.space && lanes.containsAll(input.lanes)) { "Snapshot is outside the negotiated space or lane policy" }
    }
}
