package me.ash.reader.llm.quickmessage

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ash.reader.R
import org.json.JSONArray
import org.json.JSONObject

/** 内置 Quick Message 的稳定类型；本地化文案不进入持久化数据。 */
enum class LlmQuickMessageBuiltin(
    val id: String,
    val storageValue: String,
    val titleRes: Int,
    val contentRes: Int,
) {
    EXPLAIN(
        id = "builtin:explain",
        storageValue = "explain",
        titleRes = R.string.llm_suggestion_explain,
        contentRes = R.string.llm_prompt_explain,
    ),
    EVIDENCE(
        id = "builtin:evidence",
        storageValue = "evidence",
        titleRes = R.string.llm_suggestion_evidence,
        contentRes = R.string.llm_prompt_evidence,
    ),
    ;

    companion object {
        fun fromId(id: String): LlmQuickMessageBuiltin? = entries.firstOrNull { it.id == id }

        fun fromStorageValue(value: String): LlmQuickMessageBuiltin? =
            entries.firstOrNull { it.storageValue == value }
    }
}

data class LlmQuickMessageText(
    val title: String,
    val content: String,
)

/** 一条可管理的阅读快捷消息；[builtin] 非空时 title/content 只作为空占位，不持久化本地化文本。 */
data class LlmQuickMessage(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    val builtin: LlmQuickMessageBuiltin? = null,
)

/** 根据当前 App locale 解析内置项；自定义项原样返回用户保存的文本。 */
fun resolveQuickMessageText(
    context: Context,
    message: LlmQuickMessage,
): LlmQuickMessageText =
    message.builtin?.let { builtin ->
        LlmQuickMessageText(
            title = context.getString(builtin.titleRes),
            content = context.getString(builtin.contentRes),
        )
    } ?: LlmQuickMessageText(message.title, message.content)

/** 旧版内置文案只有命中受支持语言的原始资源时才认作内置；用户改过任一字段就按自定义项保护。 */
internal fun inferLegacyQuickMessageBuiltin(
    id: String,
    title: String,
    content: String,
    localizedCandidates: Map<LlmQuickMessageBuiltin, Set<LlmQuickMessageText>>,
): LlmQuickMessageBuiltin? {
    val builtin = LlmQuickMessageBuiltin.fromId(id) ?: return null
    val stored = LlmQuickMessageText(title.trim(), content.trim())
    return builtin.takeIf { stored in localizedCandidates[builtin].orEmpty() }
}

/** 用户保存编辑后，内置项立即脱离本地化资源，成为普通自定义 Quick Message。 */
internal fun customizeQuickMessage(
    message: LlmQuickMessage,
    title: String,
    content: String,
): LlmQuickMessage =
    message.copy(
        title = title.trim(),
        content = content.trim(),
        builtin = null,
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
    private val legacyBuiltinTextCandidates by lazy(::buildLegacyBuiltinTextCandidates)
    private val _messages = MutableStateFlow(readMessages())
    val messages: StateFlow<List<LlmQuickMessage>> = _messages.asStateFlow()

    fun current(): List<LlmQuickMessage> = _messages.value

    fun enabledMessages(): List<LlmQuickMessage> = current().filter(LlmQuickMessage::enabled)

    /** 发送时显式按当前 App locale 解析资源，避免仓储单例持有切换语言前的内置 Prompt 文本。 */
    fun resolveText(message: LlmQuickMessage): LlmQuickMessageText {
        val appLocale = AppCompatDelegate.getApplicationLocales()[0]
        if (appLocale == null) return resolveQuickMessageText(context, message)
        val configuration = Configuration(context.resources.configuration).apply { setLocale(appLocale) }
        return resolveQuickMessageText(context.createConfigurationContext(configuration), message)
    }

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
                    // 用户显式编辑内置项后即转为自定义语义，后续切换语言不得覆盖用户修改。
                    customizeQuickMessage(message, title, content)
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
        val restored =
            runCatching {
                val array = JSONArray(encoded)
                buildList {
                    repeat(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        val id = item.optString("id").trim()
                        val title = item.optString("title").trim()
                        val content = item.optString("content").trim()
                        val source = item.optString(KEY_SOURCE).trim()
                        val builtin =
                            when {
                                source.startsWith(BUILTIN_SOURCE_PREFIX) ->
                                    LlmQuickMessageBuiltin.fromStorageValue(
                                        source.removePrefix(BUILTIN_SOURCE_PREFIX)
                                    )
                                source == CUSTOM_SOURCE -> null
                                else ->
                                    inferLegacyQuickMessageBuiltin(
                                        id = id,
                                        title = title,
                                        content = content,
                                        localizedCandidates = legacyBuiltinTextCandidates,
                                    )
                            }
                        if (
                            id.isNotBlank() &&
                                (builtin != null || (title.isNotBlank() && content.isNotBlank()))
                        ) {
                            add(
                                LlmQuickMessage(
                                    id = id,
                                    title = if (builtin == null) title.take(MAX_TITLE_LENGTH) else "",
                                    content = if (builtin == null) content.take(MAX_CONTENT_LENGTH) else "",
                                    enabled = item.optBoolean("enabled", true),
                                    order = item.optInt("order", index),
                                    builtin = builtin,
                                )
                            )
                        }
                    }
                }
            }
                .getOrElse { defaultMessages() }
        val normalized = normalizeOrder(restored)
        // 每次成功读取都重写为带 source 的新格式；旧版本地化文本只迁移一次。
        persist(normalized)
        return normalized
    }

    private fun defaultMessages(): List<LlmQuickMessage> =
        listOf(
            LlmQuickMessage(
                id = LlmQuickMessageBuiltin.EXPLAIN.id,
                title = "",
                content = "",
                enabled = true,
                order = 0,
                builtin = LlmQuickMessageBuiltin.EXPLAIN,
            ),
            LlmQuickMessage(
                id = LlmQuickMessageBuiltin.EVIDENCE.id,
                title = "",
                content = "",
                enabled = true,
                order = 1,
                builtin = LlmQuickMessageBuiltin.EVIDENCE,
            ),
        )

    /** 枚举当前公开支持的三种 UI 语言，用于识别旧版未编辑的内置 Quick Message。 */
    private fun buildLegacyBuiltinTextCandidates(): Map<LlmQuickMessageBuiltin, Set<LlmQuickMessageText>> {
        val locales =
            listOf(
                Locale.ENGLISH,
                Locale.forLanguageTag("zh-Hans"),
                Locale.forLanguageTag("zh-Hant"),
            )
        return LlmQuickMessageBuiltin.entries.associateWith { builtin ->
            locales
                .map { locale ->
                    val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
                    val localizedContext = context.createConfigurationContext(configuration)
                    LlmQuickMessageText(
                        title = localizedContext.getString(builtin.titleRes),
                        content = localizedContext.getString(builtin.contentRes),
                    )
                }
                .toSet()
        }
    }

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
            val source =
                message.builtin?.let { "$BUILTIN_SOURCE_PREFIX${it.storageValue}" } ?: CUSTOM_SOURCE
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put(KEY_SOURCE, source)
                    .put("title", if (message.builtin == null) message.title else "")
                    .put("content", if (message.builtin == null) message.content else "")
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
        private const val KEY_SOURCE = "source"
        private const val CUSTOM_SOURCE = "custom"
        private const val BUILTIN_SOURCE_PREFIX = "builtin:"

        internal fun normalizeOrder(messages: List<LlmQuickMessage>): List<LlmQuickMessage> =
            messages
                .distinctBy(LlmQuickMessage::id)
                .sortedWith(compareBy<LlmQuickMessage> { it.order }.thenBy { it.id })
                .mapIndexed { index, item -> item.copy(order = index) }
    }
}
