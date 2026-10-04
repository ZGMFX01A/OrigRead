package me.ash.reader.infrastructure.sync.core

/** 通过头部签名认证之后再按 Peer 限流，伪造设备 ID 不得消耗该设备的业务额度。 */
internal class SyncLanPeerAdmission {
    private val active = mutableMapOf<String, Int>()

    /** 为正文读取保留一个 Peer 槽位；达到上限时由路由明确拒绝。 */
    @Synchronized
    fun acquire(peer: String): Boolean {
        val count = active[peer] ?: 0
        if (count >= REQUESTS_PER_PEER) return false
        active[peer] = count + 1
        return true
    }

    /** 读取失败、断线和正常响应均释放槽位。 */
    @Synchronized
    fun release(peer: String) {
        val count = checkNotNull(active[peer]) - 1
        if (count == 0) active.remove(peer) else active[peer] = count
    }

    private companion object {
        // 单个已认证设备不能独占 Android 的所有业务处理线程。
        const val REQUESTS_PER_PEER = 4
    }
}
