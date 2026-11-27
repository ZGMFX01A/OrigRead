package me.ash.reader.llm.chat.ui

/** Only the synchronous admission result owns clearing the editor. */
internal fun submitChatDraft(draft: String, send: (String) -> Boolean): String =
    if (draft.isNotBlank() && send(draft.trim())) "" else draft
