package me.ash.reader.ui.page.settings.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.EntryPointAccessors
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalSettings
import me.ash.reader.infrastructure.preference.OpenLinkPreference
import me.ash.reader.infrastructure.preference.ReadingShareTarget
import me.ash.reader.infrastructure.share.LogseqShare
import me.ash.reader.infrastructure.share.NotionShareEntryPoint
import me.ash.reader.infrastructure.share.NotionShareTarget
import me.ash.reader.infrastructure.share.ObsidianShare
import me.ash.reader.infrastructure.share.SiYuanShare
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.page.home.reading.ReadingShareConfigSheet
import me.ash.reader.ui.page.settings.SettingItem

/** 设置入口与阅读页复用同一份配置和编辑面板。 */
@Composable
internal fun ReadingShareSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preference = LocalSettings.current.readingShare
    var showConfig by remember { mutableStateOf(false) }
    val content = buildList {
        if (preference.includeTitle) add(stringResource(R.string.reading_share_title_option))
        if (preference.includeBody) add(stringResource(R.string.reading_share_body_option))
        if (preference.includeTranslation) add(stringResource(R.string.reading_share_translation_option))
        if (preference.includeSummary) add(stringResource(R.string.reading_share_summary_option))
        add(stringResource(R.string.reading_share_source_url))
    }
    val target = stringResource(
        when (preference.target) {
            ReadingShareTarget.SYSTEM -> R.string.reading_share_target_system
            ReadingShareTarget.OBSIDIAN -> R.string.reading_share_target_obsidian
            ReadingShareTarget.SIYUAN -> R.string.reading_share_target_siyuan
            ReadingShareTarget.LOGSEQ -> R.string.reading_share_target_logseq
            ReadingShareTarget.NOTION -> R.string.reading_share_target_notion
        },
    )
    SettingItem(
        title = stringResource(R.string.reading_share_config_title),
        desc = (content + target).joinToString(" · "),
        onClick = { showConfig = true },
    )

    if (showConfig) {
        val repository = remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                NotionShareEntryPoint::class.java,
            ).notionShareRepository()
        }
        val notionConfiguration = repository.configuration.collectAsStateValue()
        ReadingShareConfigSheet(
            initialPreference = preference,
            obsidianAvailability = remember { ObsidianShare.availability(context) },
            siYuanAvailability = remember { SiYuanShare.availability(context) },
            logseqAvailability = remember { LogseqShare.availability(context) },
            notionAvailability = remember { NotionShareTarget.availability(context) },
            notionConfiguration = notionConfiguration,
            onDismiss = { showConfig = false },
            onSave = { updatedPreference, notionToken ->
                updatedPreference.save(context, scope)
                repository.saveConfiguration(notionToken)
                showConfig = false
            },
            onOpenNotionSetup = {
                context.openURL(
                    "https://www.notion.so/developers/tokens",
                    OpenLinkPreference.AutoPreferDefaultBrowser,
                )
            },
        )
    }
}
