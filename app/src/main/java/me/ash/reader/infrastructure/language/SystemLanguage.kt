package me.ash.reader.infrastructure.language

import android.content.res.Resources
import java.util.Locale

/**
 * 返回操作系统当前主语言，而不是 AppCompat 应用内语言。
 * 用户手动把 OrigRead UI 切成其他语言时，新安装/恢复默认的 AI 与翻译目标语言仍应跟随系统。
 */
fun systemLanguageTag(): String =
    runCatching {
            Resources.getSystem().configuration.locales[0]
                ?.toLanguageTag()
                ?.takeIf(String::isNotBlank)
        }
        .getOrNull()
        ?: Locale.getDefault().toLanguageTag().takeIf(String::isNotBlank)
        ?: "zh-CN"
