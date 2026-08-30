package me.ash.reader.infrastructure.backup

import kotlinx.serialization.json.Json
import me.ash.reader.llm.mcp.McpServerRepository
import me.ash.reader.llm.mcp.McpToolRegistry
import me.ash.reader.llm.quickmessage.LlmQuickMessageRepository
import me.ash.reader.llm.search.WebSearchRepository
import me.ash.reader.llm.settings.LlmSettingsRepository
import me.ash.reader.llm.skill.LlmSkillRepository
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class EditionConfigurationBackupExtensionTest {
    private val llmSettingsRepository = mock<LlmSettingsRepository>()
    private val webSearchRepository = mock<WebSearchRepository>()
    private val mcpServerRepository = mock<McpServerRepository>()
    private val mcpToolRegistry = mock<McpToolRegistry>()
    private val skillRepository = mock<LlmSkillRepository>()
    private val quickMessageRepository = mock<LlmQuickMessageRepository>()

    private fun extension(): EditionConfigurationBackupExtensionImpl =
        EditionConfigurationBackupExtensionImpl(
            llmSettingsRepository = llmSettingsRepository,
            webSearchRepository = webSearchRepository,
            mcpServerRepository = mcpServerRepository,
            mcpToolRegistry = mcpToolRegistry,
            skillRepository = skillRepository,
            quickMessageRepository = quickMessageRepository,
        )

    @Test
    fun `restore forwards skill and quick message backup state`() {
        whenever(mcpServerRepository.currentServers()).thenReturn(emptyList())
        val configuration =
            Json.parseToJsonElement(
                """
                {
                  "schemaVersion": 2,
                  "skillState": "skill-backup-state",
                  "quickMessageState": "quick-message-backup-state"
                }
                """.trimIndent()
            )

        extension().restoreBackup(
            configuration = configuration,
            secrets = null,
            replaceSecrets = false,
        )

        verify(skillRepository).restoreBackupState("skill-backup-state")
        verify(quickMessageRepository).restoreBackupState("quick-message-backup-state")
    }

    @Test
    fun `legacy backup without skill states preserves current local data`() {
        whenever(mcpServerRepository.currentServers()).thenReturn(emptyList())
        val configuration = Json.parseToJsonElement("""{"schemaVersion":1}""")

        extension().restoreBackup(
            configuration = configuration,
            secrets = null,
            replaceSecrets = false,
        )

        verify(skillRepository, never()).restoreBackupState(org.mockito.kotlin.any())
        verify(quickMessageRepository, never()).restoreBackupState(org.mockito.kotlin.any())
    }

}
