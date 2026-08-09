package me.ash.reader.domain.service

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.ash.reader.infrastructure.net.Download

/** F-Droid 渠道由商店管理更新，不打包应用内 APK 自更新能力。 */
class AppUpdateDownloader @Inject constructor() {
    fun download(url: String, filename: String): Flow<Download> =
        flowOf(Download.Error("F-Droid 渠道不支持应用内更新"))
}
