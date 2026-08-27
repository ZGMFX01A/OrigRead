package me.ash.reader.infrastructure.preference

import android.content.Context
import me.ash.reader.R
import me.ash.reader.ui.page.settings.accounts.AccountViewModel

sealed class SyncOnlyOnWiFiPreference(
    val value: Boolean,
) {

    object On : SyncOnlyOnWiFiPreference(true)
    object Off : SyncOnlyOnWiFiPreference(false)

    fun put(accountId: Int, viewModel: AccountViewModel) {
        viewModel.update(accountId) { copy(syncOnlyOnWiFi = this@SyncOnlyOnWiFiPreference) }
    }

    fun toDesc(context: Context): String =
        when (this) {
            On -> context.getString(R.string.on)
            Off -> context.getString(R.string.off)
        }

    companion object {
        // Getter 避免首次直接访问 On/Off 子类时，父类初始化把尚未就绪的 object INSTANCE 固化为 null。
        val default: SyncOnlyOnWiFiPreference
            get() = Off

        val values: List<SyncOnlyOnWiFiPreference>
            get() = listOf(On, Off)
    }
}

operator fun SyncOnlyOnWiFiPreference.not(): SyncOnlyOnWiFiPreference =
    when (value) {
        true -> SyncOnlyOnWiFiPreference.Off
        false -> SyncOnlyOnWiFiPreference.On
    }
