package me.ash.reader.infrastructure.backup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement

/** Standard Edition 没有 LLM 私有配置；导入 X 备份时安全忽略其专属扩展块。 */
@Singleton
class EditionConfigurationBackupExtensionImpl @Inject constructor() : EditionConfigurationBackupExtension {
    override fun exportConfiguration(): JsonElement? = null

    override fun exportSecrets(): JsonElement? = null

    override fun validateBackup(configuration: JsonElement?, secrets: JsonElement?) = Unit

    override fun restoreBackup(
        configuration: JsonElement?,
        secrets: JsonElement?,
        replaceSecrets: Boolean,
    ) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EditionConfigurationBackupExtensionModule {
    @Binds
    abstract fun bindEditionConfigurationBackupExtension(
        implementation: EditionConfigurationBackupExtensionImpl,
    ): EditionConfigurationBackupExtension
}
