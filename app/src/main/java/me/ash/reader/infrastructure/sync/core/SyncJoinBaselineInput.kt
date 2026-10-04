package me.ash.reader.infrastructure.sync.core

/** Reader 已关闭的 coverage 和附件 DTO，Chat 提交不再反向访问 Reader。 */
data class SyncJoinBaselineInput(
    val context: SyncWritableActorContext,
    val drafts: List<SyncOutboxDraft>,
    val baselineId: String,
    val observed: List<SyncAppliedFrontierEntity>,
)
