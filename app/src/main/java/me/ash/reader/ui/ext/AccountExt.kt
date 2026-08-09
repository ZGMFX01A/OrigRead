package me.ash.reader.ui.ext

import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType

/**
 * 识别 OrigRead / Read You 历史版本创建的默认本地账户。
 *
 * 旧版本会把创建账户时已经本地化的品牌名称直接写入数据库，因此切换语言后可能仍显示
 * “原读”或“原讀”。这里仅把明确的历史默认品牌名视为内置账户，用户自定义的其他名称保持原样。
 */
fun Account.isOrigReadDefaultAccount(): Boolean =
    type.id == AccountType.Local.id &&
        name in setOf("OrigRead", "Read You", "原读", "原讀")
