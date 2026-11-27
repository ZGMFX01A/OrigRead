package me.ash.reader.infrastructure.editionsync

internal const val EDITION_SYNC_PERMISSION = "me.ash.reader.permission.EDITION_SYNC"

/** 旧接收端无法把可移植凭据转换成本机密文，必须在发送任何数据前要求升级。 */
internal fun validateEditionSyncTargetPermission(permission: String?) {
    require(permission == EDITION_SYNC_PERMISSION) { "请先更新目标 Edition，再同步 Keystore 凭据" }
}

/** 只接收系统 caller 身份；签名查询延迟到包名与 authority 校验之后。 */
internal fun validateEditionSyncCaller(
    actualCaller: String?,
    expectedPeer: String,
    sourcePackage: String,
    authority: String?,
    signaturesMatch: () -> Boolean,
) {
    require(actualCaller != null && actualCaller == expectedPeer && actualCaller == sourcePackage) {
        "同步调用方不是可信的对应 Edition"
    }
    require(authority == "$actualCaller.fileprovider") { "同步 URI authority 与调用方不匹配" }
    require(signaturesMatch()) { "来源 OrigRead Edition 与当前 App 签名不一致" }
}
