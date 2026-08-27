package me.ash.reader.infrastructure.preference

import android.content.Context
import me.ash.reader.R
import me.ash.reader.ui.page.settings.accounts.AccountViewModel

sealed class SyncOnStartPreference(
    val value: Boolean,
) {

    object On : SyncOnStartPreference(true)
    object Off : SyncOnStartPreference(false)

    fun put(accountId: Int, viewModel: AccountViewModel) {
        viewModel.update(accountId) { copy(syncOnStart = this@SyncOnStartPreference) }
    }

    fun toDesc(context: Context): String =
        when (this) {
            On -> context.getString(R.string.on)
            Off -> context.getString(R.string.off)
        }

    companion object {
        // Getter 避免首次直接访问 On/Off 子类时，父类初始化把尚未就绪的 object INSTANCE 固化为 null。
        val default: SyncOnStartPreference
            get() = Off

        val values: List<SyncOnStartPreference>
            get() = listOf(On, Off)
    }
}

operator fun SyncOnStartPreference.not(): SyncOnStartPreference =
    when (value) {
        true -> SyncOnStartPreference.Off
        false -> SyncOnStartPreference.On
    }
