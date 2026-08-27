package me.ash.reader.infrastructure.editionsync

import me.ash.reader.BuildConfig

/** Standard / LLM 同机通信常量与对端包名解析。 */
object EditionSyncContract {
    const val MIME_TYPE = "application/vnd.origread.edition-sync"
    const val IMPORT_ACTIVITY_CLASS =
        "me.ash.reader.infrastructure.editionsync.EditionSyncImportActivity"

    const val EXTRA_SOURCE_PACKAGE = "me.ash.reader.extra.EDITION_SYNC_SOURCE_PACKAGE"
    const val EXTRA_AES_KEY = "me.ash.reader.extra.EDITION_SYNC_AES_KEY"
    const val EXTRA_AES_IV = "me.ash.reader.extra.EDITION_SYNC_AES_IV"
    const val EXTRA_RESULT_MESSAGE = "me.ash.reader.extra.EDITION_SYNC_RESULT_MESSAGE"

    const val TEMP_DIRECTORY = "edition-sync"
    const val TEMP_FILE_SUFFIX = ".origsync"

    /** 当前包对应的另一 Edition 包名。渠道后缀保持不变。 */
    fun peerPackageName(
        edition: String = BuildConfig.EDITION,
        channel: String = BuildConfig.CHANNEL,
    ): String {
        val currentEdition = EditionSyncEdition.fromBuildConfig(edition)
        val channelSuffix = if (channel == "googlePlay") ".google.play" else ""
        return when (currentEdition.opposite()) {
            EditionSyncEdition.STANDARD -> "me.ash.reader$channelSuffix"
            EditionSyncEdition.LLM -> "me.ash.reader.llm$channelSuffix"
        }
    }
}
