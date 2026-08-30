package me.ash.reader.llm.runtime

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class LlmToolSource {
    NATIVE_PROVIDER,
    MCP,
    ORIGREAD_INTERNAL,
}

enum class LlmToolRisk {
    READ_ONLY,
    SENSITIVE,
    WRITE,
}

data class LlmToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val source: LlmToolSource,
    val sourceId: String? = null,
    val risk: LlmToolRisk = LlmToolRisk.READ_ONLY,
    val enabled: Boolean = true,
    /** Provider/MCP Tool 的 JSON Schema；后续标准 Function Calling 直接消费，不再二次猜参数。 */
    val inputSchemaJson: String = "{\"type\":\"object\",\"properties\":{}}",
    val outputSchemaJson: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Tool id 不能为空" }
        if (source == LlmToolSource.MCP) {
            require(!sourceId.isNullOrBlank()) { "MCP Tool 必须记录来源 Server" }
        }
    }

    val requiresConfirmation: Boolean
        // MCP annotations 来自远端 Server，只能作为风险提示，不能作为本地授权依据。
        // 因此远端 MCP Tool 默认都需要用户确认；应用内受信任的只读 Tool 仍可自动执行。
        get() = source == LlmToolSource.MCP || risk != LlmToolRisk.READ_ONLY
}

data class LlmToolCall(
    val id: String,
    val toolId: String,
    val argumentsJson: String,
)

sealed interface LlmToolResult {
    data class Success(val content: String) : LlmToolResult
    data class Failure(val message: String) : LlmToolResult
    data class ConfirmationRequired(val descriptor: LlmToolDescriptor) : LlmToolResult
}

interface LlmTool {
    val descriptor: LlmToolDescriptor

    suspend fun execute(argumentsJson: String): LlmToolResult
}

@Singleton
class LlmToolRuntime @Inject constructor() {
    private val tools = ConcurrentHashMap<String, LlmTool>()

    fun register(tool: LlmTool) {
        val previous = tools.putIfAbsent(tool.descriptor.id, tool)
        require(previous == null || previous === tool) {
            "Tool id 已被占用：${tool.descriptor.id}"
        }
    }

    fun unregister(toolId: String) {
        tools.remove(toolId)
    }

    fun descriptors(): List<LlmToolDescriptor> =
        tools.values
            .map(LlmTool::descriptor)
            .sortedBy(LlmToolDescriptor::id)

    fun resolveAllowed(toolIds: Set<String>): List<LlmToolDescriptor> =
        toolIds
            .mapNotNull { tools[it]?.descriptor }
            .filter(LlmToolDescriptor::enabled)
            .sortedBy(LlmToolDescriptor::id)

    suspend fun execute(
        call: LlmToolCall,
        profile: LlmExecutionProfile,
        confirmed: Boolean = false,
    ): LlmToolResult {
        if (call.toolId !in profile.enabledToolIds) {
            return LlmToolResult.Failure("当前执行配置未授权 Tool：${call.toolId}")
        }
        val tool = tools[call.toolId]
            ?: return LlmToolResult.Failure("Tool 不存在或尚未加载：${call.toolId}")
        if (!tool.descriptor.enabled) {
            return LlmToolResult.Failure("Tool 已停用：${call.toolId}")
        }
        if (tool.descriptor.requiresConfirmation && !confirmed) {
            return LlmToolResult.ConfirmationRequired(tool.descriptor)
        }
        return runCatching { tool.execute(call.argumentsJson) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                LlmToolResult.Failure(error.message ?: "Tool 执行失败")
            }
    }
}
