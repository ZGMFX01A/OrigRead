package me.ash.reader.llm.quickmessage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ash.reader.R
import org.json.JSONArray
import org.json.JSONObject

/** 一条可管理的阅读快捷消息；[id] 是持久稳定标识，[order] 只负责展示顺序。 */
data class LlmQuickMessage(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    val order: Int = 0,
)

/** Quick Message 模板展开所需的当前阅读快照。 */
data class LlmQuickMessageContext(
    val articleTitle: String,
    val articleUrl: String?,
    val selection: String?,
    val summary: String?,
)

/** 模板展开结果；有缺失/未知变量时 [content] 为 null，禁止把原占位符直接发给模型。 */
data class LlmQuickMessageResolution(
    val content: String?,
    val unavailableVariables: List<String> = emptyList(),
    val unsupportedVariables: List<String> = emptyList(),
) {
    val ready: Boolean
        get() = content != null && unavailableVariables.isEmpty() && unsupportedVariables.isEmpty()
}

private val QUICK_MESSAGE_VARIABLE = Regex("\\{\\{([a-zA-Z0-9_]+)\\}\\}")

/**
 * 发送前解析阅读变量。
 *
 * 已声明但当前为空的变量直接阻止发送，并把变量名交给 UI 提示；未知变量同样阻止发送。
 * 这样不会把 `{{selection}}` 一类模板占位符原样泄漏给模型，也不会偷偷改成语义不明的空字符串。
 */
internal fun resolveQuickMessageTemplate(
    template: String,
    context: LlmQuickMessageContext,
): LlmQuickMessageResolution {
    val values =
        mapOf(
            "article_title" to context.articleTitle.trim(),
            "article_url" to context.articleUrl?.trim().orEmpty(),
            "selection" to context.selection?.trim().orEmpty(),
            "summary" to context.summary?.trim().orEmpty(),
        )
    val requested = QUICK_MESSAGE_VARIABLE.findAll(template).map { it.groupValues[1] }.distinct().toList()
    val unsupported = requested.filterNot(values::containsKey)
    val unavailable = requested.filter { variable -> values[variable]?.isBlank() == true }
    if (unsupported.isNotEmpty() || unavailable.isNotEmpty()) {
        return LlmQuickMessageResolution(
            content = null,
            unavailableVariables = unavailable,
            unsupportedVariables = unsupported,
        )
    }
    var resolved = template
    requested.forEach { variable ->
        resolved = resolved.replace("{{$variable}}", values.getValue(variable))
    }
    val normalized = resolved.trim()
    return LlmQuickMessageResolution(content = normalized.takeIf(String::isNotBlank))
}

@Singleton
class LlmQuickMessageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _messages = MutableStateFlow(readMessages())
    val messages: StateFlow<List<LlmQuickMessage>> = _messages.asStateFlow()

    fun current(): List<LlmQuickMessage> = _messages.value

    fun enabledMessages(): List<LlmQuickMessage> = current().filter(LlmQuickMessage::enabled)

    @Synchronized
    fun create(
        title: String,
        content: String,
    ): LlmQuickMessage {
        validateDraft(title, content)
        require(current().size < MAX_MESSAGES) { "Quick Messages 已达到上限 $MAX_MESSAGES" }
        val message =
            LlmQuickMessage(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                content = content.trim(),
                enabled = true,
                order = current().size,
            )
        updateState(current() + message)
        return message
    }

    @Synchronized
    fun update(
        id: String,
        title: String,
        content: String,
    ) {
        validateDraft(title, content)
        if (current().none { it.id == id }) return
        updateState(
            current().map { message ->
                if (message.id == id) {
                    message.copy(title = title.trim(), content = content.trim())
                } else {
                    message
                }
            }
        )
    }

    @Synchronized
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        if (current().none { it.id == id }) return
        updateState(current().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun delete(id: String) {
        updateState(current().filterNot { it.id == id })
    }

    /** direction=-1 上移，direction=1 下移；越界时保持原顺序。 */
    @Synchronized
    fun move(
        id: String,
        direction: Int,
    ) {
        require(direction == -1 || direction == 1) { "Quick Message move direction 只能是 -1 或 1" }
        val ordered = current().sortedBy(LlmQuickMessage::order).toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in ordered.indices) return
        val item = ordered.removeAt(index)
        ordered.add(target, item)
        updateState(ordered)
    }

    private fun readMessages(): List<LlmQuickMessage> {
        val encoded = preferences.getString(KEY_MESSAGES, null)
        if (encoded == null) {
            val defaults = defaultMessages()
            persist(defaults)
            return defaults
        }
        return runCatching {
                val array = JSONArray(encoded)
                buildList {
                    repeat(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        val id = item.optString("id").trim()
                        val title = item.optString("title").trim()
                        val content = item.optString("content").trim()
                        if (id.isNotBlank() && title.isNotBlank() && content.isNotBlank()) {
                            add(
                                LlmQuickMessage(
                                    id = id,
                                    title = title.take(MAX_TITLE_LENGTH),
                                    content = content.take(MAX_CONTENT_LENGTH),
                                    enabled = item.optBoolean("enabled", true),
                                    order = item.optInt("order", index),
                                )
                            )
                        }
                    }
                }
            }
            .getOrElse { defaultMessages() }
            .let(::normalizeOrder)
    }

    private fun defaultMessages(): List<LlmQuickMessage> =
        listOf(
            LlmQuickMessage(
                id = BUILTIN_EXPLAIN_ID,
                title = context.getString(R.string.llm_suggestion_explain),
                content = context.getString(R.string.llm_prompt_explain),
                enabled = true,
                order = 0,
            ),
            LlmQuickMessage(
                id = BUILTIN_EVIDENCE_ID,
                title = context.getString(R.string.llm_suggestion_evidence),
                content = context.getString(R.string.llm_prompt_evidence),
                enabled = true,
                order = 1,
            ),
        )

    private fun validateDraft(
        title: String,
        content: String,
    ) {
        require(title.trim().isNotBlank()) { "Quick Message 标题不能为空" }
        require(content.trim().isNotBlank()) { "Quick Message 内容不能为空" }
        require(title.trim().length <= MAX_TITLE_LENGTH) { "Quick Message 标题最多 $MAX_TITLE_LENGTH 个字符" }
        require(content.trim().length <= MAX_CONTENT_LENGTH) { "Quick Message 内容最多 $MAX_CONTENT_LENGTH 个字符" }
    }

    private fun updateState(messages: List<LlmQuickMessage>) {
        val normalized = normalizeOrder(messages)
        persist(normalized)
        _messages.value = normalized
    }

    private fun persist(messages: List<LlmQuickMessage>) {
        val array = JSONArray()
        normalizeOrder(messages).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("title", message.title)
                    .put("content", message.content)
                    .put("enabled", message.enabled)
                    .put("order", message.order)
            )
        }
        preferences.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    companion object {
        internal const val MAX_MESSAGES = 100
        internal const val MAX_TITLE_LENGTH = 80
        internal const val MAX_CONTENT_LENGTH = 4_000
        private const val PREFERENCES_NAME = "origread_llm_quick_messages"
        private const val KEY_MESSAGES = "messages"
        private const val BUILTIN_EXPLAIN_ID = "builtin:explain"
        private const val BUILTIN_EVIDENCE_ID = "builtin:evidence"

        internal fun normalizeOrder(messages: List<LlmQuickMessage>): List<LlmQuickMessage> =
            messages
                .distinctBy(LlmQuickMessage::id)
                .sortedWith(compareBy<LlmQuickMessage> { it.order }.thenBy { it.id })
                .mapIndexed { index, item -> item.copy(order = index) }
    }
}
