package me.ash.reader.infrastructure.sync.core

/** LAN 连接兼容版本独立于应用展示版本和历史 Operation 版本；双端不兼容变更必须共同递增。 */
const val SYNC_COMPATIBILITY_VERSION: Int = 3

/** 每个业务请求声明兼容版本，防止旧客户端绕过握手直接读写。 */
const val SYNC_COMPATIBILITY_HEADER: String = "x-sync-compatibility-version"

/** 缺失版本属于旧客户端，明确拒绝，不能默认成本机版本。 */
internal fun requireSyncCompatibility(remoteVersion: Int?) {
    check(remoteVersion == SYNC_COMPATIBILITY_VERSION) {
        "SYNC_VERSION_MISMATCH: 同步协议不兼容，请升级两台设备后重试（本机 $SYNC_COMPATIBILITY_VERSION，对端 ${remoteVersion ?: "未声明"}）"
    }
}

/** 请求头必须使用规范整数表示，不接受重复头、空值或其他格式。 */
internal fun requireSyncCompatibilityHeader(value: String?) {
    // 先区分规范版本和非法头，再校验兼容性，避免将已声明的旧版本误报为“未声明”。
    val remoteVersion = value?.toIntOrNull()?.takeIf { it >= 0 && it.toString() == value }
    requireSyncCompatibility(remoteVersion)
}
