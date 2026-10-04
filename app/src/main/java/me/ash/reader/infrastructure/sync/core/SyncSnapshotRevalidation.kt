package me.ash.reader.infrastructure.sync.core

/** P4 每轮允许一次锁外重新准备；仍发生修订竞争时保留输入并明确返回 MORE_WORK。 */
internal const val SNAPSHOT_PUBLICATION_ATTEMPTS = 2

/** 只处理修订竞争，签名、权限、取消及持久化异常继续暴露原错误。 */
internal fun snapshotRevisionConflict(error: IllegalStateException): Boolean =
    error.message?.startsWith("REVALIDATION_REQUIRED:") == true

/** 持续变化不触发无限全量验证，控制作业进入等待稳定输入的 PAUSED 状态。 */
internal fun snapshotNeedsStableInput(): Nothing =
    error("MORE_WORK: Snapshot authority or input keeps changing; fixed input is retained")
