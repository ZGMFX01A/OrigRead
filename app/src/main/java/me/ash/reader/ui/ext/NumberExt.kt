package me.ash.reader.ui.ext

infix fun Int.spacerDollar(str: Any): String = "$this$$str"

fun String.dollarLast(): String = split("$").last()

/**
 * OrigRead 默认分组稳定 ID。
 *
 * DB v10 及更早版本使用的旧值已由 MIGRATION_10_11 原位迁移，
 * 新建账户从 DB v11 起统一使用当前标识。
 */
fun Int.getDefaultGroupId() = this.spacerDollar("origread_app_default_group")

fun Int.toBoolean(): Boolean = this != 0
