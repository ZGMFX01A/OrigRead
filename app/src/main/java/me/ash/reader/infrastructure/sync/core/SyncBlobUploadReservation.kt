package me.ash.reader.infrastructure.sync.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SyncBlobUploadReservationWire(
    val manifest: SyncBlobManifestWire,
    val references: List<SyncBlobReferenceWire>,
)

@Serializable
private data class SavedBlobReservation(
    val space: String,
    val peer: String,
    val value: SyncBlobUploadReservationWire,
)

/** 请求签名已认证的上传引用；预约不代替 Operation/Snapshot 的业务验签。 */
internal object SyncBlobUploadReservations {
    private val json = Json { encodeDefaults = true }
    // 每类附件的业务 lane 固定，禁止将暂停的 AI 字节伪装成 Article 附件。
    private val ownerLanes = mapOf(
        "article" to "ARTICLE_STATE", "conversation" to "AI_HISTORY", "conversation_article" to "AI_HISTORY",
        "message" to "AI_HISTORY", "tool_call" to "AI_HISTORY", "context_ref" to "AI_HISTORY",
        "evidence_block" to "AI_HISTORY", "citation_ref" to "AI_HISTORY", "citation_annotation" to "AI_HISTORY",
        "citation_annotation_ref" to "AI_HISTORY",
    )

    /** 验证 hash、长度、业务引用和当前 lane 策略；策略修改后再次调用同一检查。 */
    fun validate(value: SyncBlobUploadReservationWire, policy: Map<String, String>) {
        kotlin.require(Regex("^[0-9a-f]{64}$").matches(value.manifest.hash) && value.manifest.totalBytes >= 0) {
            "INVALID_BLOB_RESERVATION"
        }
        kotlin.require(value.references.isNotEmpty()) { "INVALID_BLOB_REFERENCE" }
        value.references.forEach { ref ->
            kotlin.require(ref.hash == value.manifest.hash && ownerLanes[ref.ownerEntityType] == ref.replicationLaneId &&
                ref.ownerEntitySyncId.isNotBlank() && ref.ownerEntityGeneration >= 0 && ref.referenceKind.isNotBlank()) {
                "INVALID_BLOB_REFERENCE"
            }
        }
        check(value.references.any { (policy[it.replicationLaneId] ?: "ENABLED") == "ENABLED" }) {
            "AUTH_FORBIDDEN: Blob reservation belongs exclusively to disabled lanes"
        }
    }

    /** 持久化 Peer/Space 绑定，重启续传不会丢失预约授权。 */
    fun save(root: File, reservation: ReservationContext) {
        validate(reservation.value, reservation.policy)
        val path = File(root, "${reservation.value.manifest.hash}.reservation")
        if (path.isFile) {
            val previous = json.decodeFromString<SavedBlobReservation>(path.readText())
            check(previous.space == reservation.space && previous.peer == reservation.peer &&
                previous.value.manifest.totalBytes == reservation.value.manifest.totalBytes) { "BLOB_STAGE_CONFLICT" }
        }
        path.writeText(json.encodeToString(SavedBlobReservation(reservation.space, reservation.peer, reservation.value)))
    }

    /** 每块正文落盘前重新验证预约，直接 PUT 与暂停后的续传都会明确失败。 */
    fun require(root: File, context: ReservationCheck) {
        val path = File(root, "${context.hash}.reservation")
        check(path.isFile) { "AUTH_FORBIDDEN: authenticated Blob upload reservation is required" }
        val saved = json.decodeFromString<SavedBlobReservation>(path.readText())
        check(saved.space == context.space && saved.peer == context.peer && saved.value.manifest.hash == context.hash &&
            (context.totalBytes == null || saved.value.manifest.totalBytes == context.totalBytes)) {
            "AUTH_FORBIDDEN: Blob reservation mismatch"
        }
        validate(saved.value, context.policy)
    }

    data class ReservationContext(val space: String, val peer: String, val value: SyncBlobUploadReservationWire,
        val policy: Map<String, String>)
    data class ReservationCheck(val space: String, val peer: String, val hash: String,
        val policy: Map<String, String>, val totalBytes: Long? = null)
}
