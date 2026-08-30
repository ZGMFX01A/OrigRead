package me.ash.reader.infrastructure.backup

import kotlinx.serialization.json.JsonElement

/**
 * Edition 专属配置的备份扩展点。
 *
 * main 只认识通用 JSON 容器：Standard 绑定 no-op，OrigRead X 在 llm source set 中负责解析自己的
 * Web Search、MCP 与高级 LLM 配置，避免公共备份链反向依赖 `src/llm`。
 */
interface EditionConfigurationBackupExtension {
    /** 导出非敏感 Edition 配置；没有专属配置的 Edition 返回 null。 */
    fun exportConfiguration(): JsonElement?

    /** 导出敏感 Edition 凭据；返回值只会进入外层 AES-GCM 加密凭据块。 */
    fun exportSecrets(): JsonElement?

    /** 在任何恢复写入发生前完成格式、枚举和边界校验。 */
    fun validateBackup(
        configuration: JsonElement?,
        secrets: JsonElement?,
    )

    /**
     * 恢复 Edition 配置。
     *
     * [replaceSecrets] 仅在备份确实包含并成功解密凭据块时为 true；无凭据备份不得清空本机 Secret。
     */
    fun restoreBackup(
        configuration: JsonElement?,
        secrets: JsonElement?,
        replaceSecrets: Boolean,
    )
}
