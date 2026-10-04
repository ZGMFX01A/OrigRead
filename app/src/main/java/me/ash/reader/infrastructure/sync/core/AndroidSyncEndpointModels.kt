package me.ash.reader.infrastructure.sync.core

import java.util.UUID

/** 本机端点连接配置，身份、传输类型和启用状态沿用现有持久契约。 */
data class AndroidSyncEndpointConfig(
    val endpointId: String = UUID.randomUUID().toString(),
    val syncSpaceId: String,
    val baseUrl: String,
    val accessToken: String? = null,
    val transport: String = "CLOUD",
    val enabled: Boolean = true,
)

/** 一次实际同步尝试的结果；待处理和授权稳定性保持单独暴露。 */
data class AndroidSyncRunSummary(
    val attempted: Int,
    val succeeded: Int,
    val failedEndpointIds: List<String>,
    val moreWork: Boolean = false,
    val authStabilityPending: Boolean = false,
) {
    val completed: Boolean get() = failedEndpointIds.isEmpty()
}

/** 持久端点配置逐字段转换，不改变 URL、凭据或本地启用策略。 */
internal fun SyncEndpointEntity.toConfig(): AndroidSyncEndpointConfig = AndroidSyncEndpointConfig(
    endpointId = endpointId,
    syncSpaceId = syncSpaceId,
    baseUrl = baseUrl,
    accessToken = accessToken,
    transport = transport,
    enabled = enabled,
)
