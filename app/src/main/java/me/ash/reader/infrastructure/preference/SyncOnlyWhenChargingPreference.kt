package me.ash.reader.infrastructure.preference

import android.content.Context
import me.ash.reader.R
import me.ash.reader.ui.page.settings.accounts.AccountViewModel

sealed class SyncOnlyWhenChargingPreference(
    val value: Boolean,
) {

    object On : SyncOnlyWhenChargingPreference(true)
    object Off : SyncOnlyWhenChargingPreference(false)

    fun put(accountId: Int, viewModel: AccountViewModel) {
        viewModel.update(accountId) { copy(syncOnlyWhenCharging = this@SyncOnlyWhenChargingPreference) }
    }

    fun toDesc(context: Context): String =
        when (this) {
            On -> context.getString(R.string.on)
            Off -> context.getString(R.string.off)
        }

    companion object {
        // Getter 避免首次直接访问 On/Off 子类时，父类初始化把尚未就绪的 object INSTANCE 固化为 null。
        val default: SyncOnlyWhenChargingPreference
            get() = Off

        val values: List<SyncOnlyWhenChargingPreference>
            get() = listOf(On, Off)
    }
}

operator fun SyncOnlyWhenChargingPreference.not(): SyncOnlyWhenChargingPreference =
    when (value) {
        true -> SyncOnlyWhenChargingPreference.Off
        false -> SyncOnlyWhenChargingPreference.On
    }
