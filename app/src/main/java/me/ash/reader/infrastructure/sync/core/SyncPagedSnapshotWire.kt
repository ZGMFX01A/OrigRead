package me.ash.reader.infrastructure.sync.core

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 分页 wire 校验保持与 Desktop 相同的域、字段和规范 JSON。 */
object SyncPagedSnapshotWire {
    /** 分页清单使用独立签名域，防止旧整包签名跨格式复用。 */
    private const val SIGNING_DOMAIN = "ORIGREAD_SNAPSHOT_PAGED_V1"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /** root 绑定固定视图、策略、逐页摘要与覆盖度，排除 root 和签名自身。 */
    fun rootHash(manifest: SyncPagedSnapshotManifest): String =
        SyncOperationCanonicalizer.sha256Hex(canonicalWithout(manifest, setOf("rootHash", "authorSignature")))

    /** 清单签名只需读取索引，页面内容分别受已签名摘要保护。 */
    fun signingMaterial(manifest: SyncPagedSnapshotManifest): String =
        SIGNING_DOMAIN + "\n" + canonicalWithout(manifest, setOf("authorSignature"))

    /** 先检查清单结构及作者签名，再允许接收页面或进入安装。 */
    fun verifyManifest(manifest: SyncPagedSnapshotManifest, author: SyncPeerKey, keys: SyncDeviceSigningKeyStore) {
        require(manifest.formatVersion == PAGED_SNAPSHOT_FORMAT) { "SNAPSHOT_INCOMPATIBLE: unsupported paged Snapshot format" }
        require(listOf(manifest.snapshotBundleId, manifest.sourceSnapshotBundleId, manifest.syncSpaceId, manifest.genesisBaselineId,
            manifest.crossDbCutId, manifest.policyHash).all { it.isNotBlank() }) { "SNAPSHOT_CORRUPTED: incomplete Snapshot identity" }
        val lanes = manifest.lanes.map { it.replicationLaneId }
        require(lanes.distinct().size == lanes.size && lanes.all { name -> SyncReplicationLane.entries.any { it.wireName == name } }) {
            "SNAPSHOT_CORRUPTED: duplicate or unsupported Snapshot lane"
        }
        manifest.lanes.forEach { lane ->
            val frontier = SyncPagedFrontier.decode(lane)
            require(frontier == manifest.coverage[lane.replicationLaneId]) {
                "SNAPSHOT_CORRUPTED: paged Snapshot frontier and coverage disagree"
            }
            require(lane.pageHashes.isNotEmpty() && lane.recordCount >= 0 && lane.pageHashes.all { it.matches(Regex("[a-f0-9]{64}")) }) {
                "SNAPSHOT_CORRUPTED: invalid Snapshot page index"
            }
        }
        require(manifest.coverage.keys.all { it in lanes } && manifest.snapshotClass in setOf("WORKING", "GC_BASELINE", "BOOTSTRAP_RECOVERY") &&
            manifest.capturedAt >= 0 && manifest.authorDeviceId.isNotBlank()) { "SNAPSHOT_CORRUPTED: invalid Snapshot metadata" }
        require(lanes.containsAll(manifest.requiredCoreShardIds + listOf("AUTH", "CORE_META"))) {
            "SNAPSHOT_CORRUPTED: required Snapshot lane is missing"
        }
        require(rootHash(manifest) == manifest.rootHash) { "SNAPSHOT_CORRUPTED: Snapshot root mismatch" }
        require(author.status == "ACTIVE" && keys.verifyBase64(author.publicKeySpkiBase64,
            signingMaterial(manifest).toByteArray(Charsets.UTF_8), manifest.authorSignature)) {
            "SNAPSHOT_CORRUPTED: Snapshot author signature mismatch"
        }
    }

    /** 页面必须属于清单指定的逻辑 lane 和序号，且编码与字节摘要一致。 */
    fun verifyPage(manifest: SyncPagedSnapshotManifest, page: SyncSnapshotBytePage): ByteArray {
        val lane = manifest.lanes.singleOrNull { it.replicationLaneId == page.replicationLaneId }
            ?: error("SNAPSHOT_CORRUPTED: page lane is absent from signed index")
        require(page.pageIndex in lane.pageHashes.indices) { "SNAPSHOT_CORRUPTED: page index is outside signed index" }
        val bytes = Base64.getDecoder().decode(page.bytesBase64)
        require(bytes.size <= SNAPSHOT_PAGE_BYTES && Base64.getEncoder().encodeToString(bytes) == page.bytesBase64 && hashBytes(bytes) == lane.pageHashes[page.pageIndex]) {
            "SNAPSHOT_CORRUPTED: Snapshot page digest mismatch"
        }
        return bytes
    }

    /** 页面摘要按原始字节计算，不能把跨页 UTF-8 片段分别解码后散列。 */
    fun hashBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    /** 显式剔除自引用字段后规范化，保持两端签名材料一致。 */
    private fun canonicalWithout(manifest: SyncPagedSnapshotManifest, excluded: Set<String>): String {
        val fields = json.parseToJsonElement(json.encodeToString(manifest)).jsonObject
        return SyncOperationCanonicalizer.canonicalJson(JsonObject(fields.filterKeys { it !in excluded }).toString())
    }
}
