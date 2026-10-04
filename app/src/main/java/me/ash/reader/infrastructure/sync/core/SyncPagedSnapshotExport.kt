package me.ash.reader.infrastructure.sync.core

import java.util.Base64
import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 仅导出 Reader 已发布、当前设备拥有的分页固定视图，私有接收 stage 不成为对外来源。 */
class SyncPagedSnapshotExport @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val keys: SyncDeviceSigningKeyStore,
) {
    data class Scope(val bundleId: String, val selectedLanes: Set<String>? = null)
    data class Page(val bundleId: String, val lane: String, val index: Int)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /** 政策收窄产生独立签名身份，源页面在数据库内复制，不汇总任何正文。 */
    suspend fun manifest(options: Scope): SyncPagedSnapshotManifest {
        val stored = requireNotNull(store.find(options.bundleId)) { "SNAPSHOT_INCOMPATIBLE: source has no paged Snapshot" }
        check(stored.state == "VERIFIED") { "SNAPSHOT_CORRUPTED: Snapshot pages are not verified" }
        val manifest = json.decodeFromString<SyncPagedSnapshotManifest>(stored.manifestJson)
        requireOwnedSource(manifest)
        val selected = options.selectedLanes ?: manifest.lanes.map { it.replicationLaneId }.toSet()
        val names = manifest.lanes.map { it.replicationLaneId }.toSet()
        require(selected.containsAll(manifest.requiredCoreShardIds + listOf("AUTH", "CORE_META")) && names.containsAll(selected)) {
            "SNAPSHOT_INCOMPATIBLE: invalid paged Snapshot lane scope"
        }
        if (selected == names) return manifest
        // Recovery acceptance 绑定最终 ID，不能在 acceptance 之后再改变签名视图。
        require(manifest.snapshotClass != SyncSnapshotClass.BOOTSTRAP_RECOVERY.name) {
            "REBASE_UNSAFE: recovery scope must be determined before OWNER acceptance"
        }
        val policy = SyncSnapshotWireCodec.scopedPolicyHash(manifest.policyHash, selected)
        val unsigned = manifest.copy(snapshotBundleId = SyncSnapshotWireCodec.scopedSnapshotBundleId(manifest.snapshotBundleId, policy),
            policyHash = policy, lanes = manifest.lanes.filter { it.replicationLaneId in selected },
            coverage = manifest.coverage.filterKeys { it in selected }, rootHash = "", authorSignature = "")
        val rooted = unsigned.copy(rootHash = SyncPagedSnapshotWire.rootHash(unsigned))
        store.publishedManifest(rooted)?.let { return it }
        val signed = rooted.copy(authorSignature = keys.signBase64(manifest.authorDeviceId,
            SyncPagedSnapshotWire.signingMaterial(rooted).toByteArray(Charsets.UTF_8)))
        store.copyScope(SyncPagedSnapshotStore.Scope(manifest.snapshotBundleId, signed, System.currentTimeMillis()))
        return signed
    }

    /** 字节页路径使用收窄后的固定身份，页号与摘要均由该签名清单决定。 */
    suspend fun page(options: Page): SyncSnapshotBytePage {
        val manifest = manifest(Scope(options.bundleId))
        val lane = requireNotNull(manifest.lanes.singleOrNull { it.replicationLaneId == options.lane }) {
            "SNAPSHOT_CORRUPTED: requested page lane is outside published manifest"
        }
        require(options.index in lane.pageHashes.indices) { "SNAPSHOT_CORRUPTED: requested page index is outside published manifest" }
        check(store.hasVerifiedPage(manifest, options.lane, options.index)) { "SNAPSHOT_CORRUPTED: published page is missing" }
        return SyncSnapshotBytePage(options.lane, options.index,
            Base64.getEncoder().encodeToString(store.readPage(manifest.snapshotBundleId, options.lane, options.index)))
    }

    /** 接收方暂存的别人的页面不允许冒充本机来源；已完成的 Reader 发布记录才可发送。 */
    private suspend fun requireOwnedSource(manifest: SyncPagedSnapshotManifest) {
        val origin = requireNotNull(database.syncGenesisDao().findBundle(manifest.sourceSnapshotBundleId)) {
            "SNAPSHOT_INCOMPATIBLE: Snapshot source is not published"
        }
        val device = requireNotNull(database.syncRuntimeDao().findDeviceIdentity())
        requireNotNull(database.syncGenesisDao().findPublishedSessionForBundle(manifest.syncSpaceId, origin.snapshotBundleId)) {
            "SNAPSHOT_INCOMPATIBLE: Snapshot capture has no published Genesis journal"
        }
        require(origin.syncSpaceId == manifest.syncSpaceId && origin.createdByDeviceId == device.deviceId &&
            manifest.authorDeviceId == device.deviceId && origin.schemaVersion == PAGED_SNAPSHOT_FORMAT) {
            "SNAPSHOT_INCOMPATIBLE: Snapshot source is not owned by this device"
        }
    }
}
