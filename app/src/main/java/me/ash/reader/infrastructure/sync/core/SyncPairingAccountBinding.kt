package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 配对始终绑定发起时的本地账户，禁止任取其他账户的 ACTIVE 空间。 */
@Singleton
class SyncPairingAccountBinding @Inject constructor(
    private val database: AndroidDatabase,
    private val accountService: AccountService,
) {
    /** 读取当前账户的有效绑定；远端账户和未初始化账户必须明确拒绝配对。 */
    suspend fun current(): SyncLocalSpaceBindingEntity {
        val accountId = accountService.getCurrentAccountId()
        val account = requireNotNull(database.accountDao().queryById(accountId)) {
            "PAIRING_ACCOUNT_MISSING: Current account does not exist"
        }
        // Room 类型转换会创建新对象，账户类型必须比较稳定 ID。
        check(account.type.id == AccountType.Local.id) { "PAIRING_LOCAL_ACCOUNT_REQUIRED" }
        return requireNotNull(database.syncRuntimeDao().findBinding(accountId)) {
            "PAIRING_SPACE_NOT_INITIALIZED: Initialize Sync for the current account first"
        }.also {
            check(it.lifecycleState == SyncSpaceLifecycleState.ACTIVE.name) {
                "PAIRING_SPACE_NOT_ACTIVE: Current account Sync Space is not active"
            }
        }
    }

    /** 异步握手完成后重查账户，防止切换账户把配对结果写入另一份资料库。 */
    suspend fun forSession(localAccountId: Int): SyncLocalSpaceBindingEntity {
        check(accountService.getCurrentAccountId() == localAccountId) { "PAIRING_ACCOUNT_CHANGED" }
        return current().also { check(it.localAccountId == localAccountId) { "PAIRING_ACCOUNT_CHANGED" } }
    }

    /** 空间在本机只属于一份账户资料，不能由 REPLACE 隐式抢占其他账户的绑定。 */
    suspend fun validateTarget(localAccountId: Int, syncSpaceId: String) {
        val existing = database.syncRuntimeDao().findBindingBySpace(syncSpaceId)
        check(existing == null || existing.localAccountId == localAccountId) {
            "PAIRING_SPACE_ALREADY_BOUND: Target Sync Space belongs to another local account"
        }
    }
}
