package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 与两端既有普通业务 HTTP 请求额度一致，不扩大接收端限制。 */
internal const val SYNC_OPERATION_BODY_BYTES = 16 * 1024 * 1024
// 固定 JSON 包装必须与发送格式一致，其字节数纳入正文限制。
private const val OPERATION_PREFIX = "{\"operations\":["
private const val OPERATION_SUFFIX = "]}"

/** 保持原操作与签名不变，按真实 UTF-8 请求字节顺序发送并累计服务端回执。 */
internal suspend fun pushHttpOperationBatches(
    batch: List<SyncOperationEnvelope>,
    json: Json,
    send: suspend (String) -> SyncOperationBatchResult,
): SyncOperationBatchResult {
    var result: SyncOperationBatchResult? = null
    for ((body, ids) in operationBodies(batch, json)) {
        val next = send(body)
        val receipts = next.acceptedOperationIds + next.duplicateOperationIds
        check(receipts.all { it in ids }) { "Remote acknowledged an operation outside the HTTP batch" }
        result = next.copy(
            acceptedOperationIds = result?.acceptedOperationIds.orEmpty() + next.acceptedOperationIds,
            duplicateOperationIds = result?.duplicateOperationIds.orEmpty() + next.duplicateOperationIds,
            rejected = result?.rejected.orEmpty() + next.rejected,
        )
        if (next.rejected.isNotEmpty() || ids.any { it !in receipts }) return result
    }
    return checkNotNull(result) { "Operation HTTP batch did not produce a server response" }
}

/** JSON 包装与逗号计入额度；单条超额明确拒绝，避免反复发送同一失败请求。 */
private fun operationBodies(batch: List<SyncOperationEnvelope>, json: Json): Sequence<Pair<String, List<String>>> = sequence {
    val overhead = (OPERATION_PREFIX + OPERATION_SUFFIX).toByteArray(Charsets.UTF_8).size
    var bytes = overhead
    val rows = mutableListOf<String>()
    val ids = mutableListOf<String>()
    for (operation in batch) {
        val row = json.encodeToString(operation)
        val rowBytes = row.toByteArray(Charsets.UTF_8).size
        check(overhead + rowBytes <= SYNC_OPERATION_BODY_BYTES) { "SYNC_OPERATION_TOO_LARGE: ${operation.operationId}" }
        val separatorBytes = if (rows.isEmpty()) 0 else ",".toByteArray(Charsets.UTF_8).size
        if (bytes + separatorBytes + rowBytes > SYNC_OPERATION_BODY_BYTES) {
            yield((OPERATION_PREFIX + rows.joinToString(",") + OPERATION_SUFFIX) to ids.toList())
            rows.clear()
            ids.clear()
            bytes = overhead
        }
        bytes += (if (rows.isEmpty()) 0 else ",".toByteArray(Charsets.UTF_8).size) + rowBytes
        rows.add(row)
        ids.add(operation.operationId)
    }
    yield((OPERATION_PREFIX + rows.joinToString(",") + OPERATION_SUFFIX) to ids.toList())
}
