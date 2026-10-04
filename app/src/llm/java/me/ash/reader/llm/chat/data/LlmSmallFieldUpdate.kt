package me.ash.reader.llm.chat.data

import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.ash.reader.infrastructure.sync.core.SyncApplyDeferredException

/** 小字段更新只写目标列，消息和会话的巨大内容不会被完整读回。 */
internal class LlmSmallFieldUpdate @Inject constructor(private val database: LlmChatDatabase) {
    data class Options(val type: String, val localId: String, val payload: JsonObject)

    /** 字段名来自产品白名单，值与身份使用绑定参数；不存在的父行明确延迟。 */
    fun apply(options: Options) {
        val field = options.payload.getValue("field").jsonPrimitive.content
        val table = if (options.type == "message") "llm_messages" else "llm_conversations"
        val column = if (options.type == "message") {
            require(field == "historyActive") { "Unsupported Message FIELD_SET" }
            "history_active"
        } else checkNotNull(CONVERSATION_COLUMNS[field]) { "Unsupported Conversation FIELD_SET" }
        val value = options.payload.getValue("value").jsonPrimitive
        val bound: Any? = if (options.type == "message") {
            if (checkNotNull(value.booleanOrNull) { "Invalid historyActive value" }) 1 else 0
        } else value.contentOrNull
        val sql = database.openHelper.writableDatabase
        val exists = sql.query("SELECT 1 FROM $table WHERE id=?", arrayOf(options.localId)).use { it.moveToFirst() }
        if (!exists) throw SyncApplyDeferredException("Missing ${options.type} row for FIELD_SET")
        sql.execSQL("UPDATE $table SET $column=?,updated_at=? WHERE id=?", arrayOf(bound, System.currentTimeMillis(), options.localId))
    }

    companion object {
        /** 会话允许修改的正式字段及持久化列，外部输入不能变成 SQL 标识符。 */
        private val CONVERSATION_COLUMNS = mapOf("title" to "title", "providerId" to "provider_id", "model" to "model", "skillId" to "skill_id")
    }
}
