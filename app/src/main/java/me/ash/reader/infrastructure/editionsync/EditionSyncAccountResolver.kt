package me.ash.reader.infrastructure.editionsync

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.service.AccountService

/** Edition Sync 账户映射结果，同时保留失败补偿所需的原始账户状态。 */
data class EditionSyncAccountResolution(
    val account: Account,
    val previousAccountId: Int,
    val originalAccount: Account?,
    val created: Boolean,
)

/** 将源 Edition 当前账户映射到目标 Edition 的同一业务账户。 */
@Singleton
class EditionSyncAccountResolver @Inject constructor(
    private val accountService: AccountService,
    private val accountDao: AccountDao,
) {
    /**
     * Local 账户优先复用目标已有 Local；远端账户用 type + securityKey 识别同一登录。
     * 找不到时创建新账户并切换，使 FreshRSS / Google Reader 等可以从一个 Edition 直接迁到另一个 Edition。
     */
    suspend fun resolveOrCreate(source: EditionSyncAccountSnapshot): EditionSyncAccountResolution {
        val previousAccountId = accountService.getCurrentAccountId()
        val accounts = accountDao.queryAll()
        val current = accountDao.queryById(previousAccountId)
        val existing =
            if (source.typeId == AccountType.Local.id) {
                current?.takeIf { it.type.id == AccountType.Local.id }
                    ?: accounts.firstOrNull { it.type.id == AccountType.Local.id }
            } else {
                current?.takeIf { it.type.id == source.typeId && it.securityKey == source.securityKey }
                    ?: accounts.firstOrNull { it.type.id == source.typeId && it.securityKey == source.securityKey }
            }

        if (existing != null) {
            val updated =
                existing.copy(
                    name = source.name,
                    // 远端账户凭据是 P7 “迁完即可用”的必要数据；外层 Edition 直传包为一次性 AES-GCM 密文。
                    securityKey = source.securityKey,
                )
            accountService.update(updated)
            accountService.switch(updated)
            return EditionSyncAccountResolution(
                account = updated,
                previousAccountId = previousAccountId,
                originalAccount = existing,
                created = false,
            )
        }

        val created =
            accountService.addAccount(
                Account(
                    name = source.name,
                    type = accountTypeFromId(source.typeId),
                    securityKey = source.securityKey,
                )
            )
        return EditionSyncAccountResolution(
            account = created,
            previousAccountId = previousAccountId,
            originalAccount = null,
            created = true,
        )
    }

    /** 切换回指定既有账户；失败补偿必须显式恢复同步前的 current account。 */
    suspend fun switchTo(accountId: Int): Account {
        val account = requireNotNull(accountDao.queryById(accountId)) { "Edition sync 回滚账户不存在：$accountId" }
        accountService.switch(account)
        return account
    }

    /** 还原被复用账户在 Edition Sync 前的完整 Account 记录。 */
    suspend fun restoreOriginalAccount(resolution: EditionSyncAccountResolution) {
        resolution.originalAccount?.let { accountService.update(it) }
    }

    /** 删除本次同步临时创建、但因后续失败不应保留的账户。 */
    suspend fun deleteCreatedAccount(resolution: EditionSyncAccountResolution) {
        if (!resolution.created) return
        val createdId = resolution.account.id ?: return
        if (createdId == resolution.previousAccountId) return
        accountService.delete(createdId)
    }

    /** AccountService 的 Local 分支使用 companion object 识别，因此创建时必须复用 canonical AccountType。 */
    private fun accountTypeFromId(id: Int): AccountType =
        when (id) {
            1 -> AccountType.Local
            2 -> AccountType.Fever
            3 -> AccountType.GoogleReader
            4 -> AccountType.FreshRSS
            5 -> AccountType.Feedly
            6 -> AccountType.Inoreader
            else -> error("不支持的账户类型：$id")
        }
}
