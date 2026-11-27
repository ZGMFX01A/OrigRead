package me.ash.reader.llm.quickmessage

sealed interface SaveResult {
    data object Success : SaveResult
    data class Failure(val message: String) : SaveResult
}

internal fun quickMessageSaveResult(save: () -> Unit): SaveResult =
    try {
        save()
        SaveResult.Success
    } catch (error: Exception) {
        if (error is java.util.concurrent.CancellationException) throw error
        SaveResult.Failure(error.message?.takeIf(String::isNotBlank) ?: "保存快捷消息失败")
    }
