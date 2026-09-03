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

private val languageTagPattern = Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*$")

/**
 * Settings keep the original language value for compatibility, but known language tags are shown
 * as readable names when the field is not being edited.
 */
fun displayLanguageValue(
    value: String,
    displayLocale: Locale,
): String {
    val trimmed = value.trim()
    if (!languageTagPattern.matches(trimmed)) return value

    val normalized = trimmed.replace('_', '-')
    val locale = Locale.forLanguageTag(normalized)
    if (locale.language.isBlank()) return value

    if (locale.language == "zh") {
        val traditional =
            locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.equals("TW", ignoreCase = true) ||
                locale.country.equals("HK", ignoreCase = true) ||
                locale.country.equals("MO", ignoreCase = true)
        return when {
            displayLocale.language != "zh" ->
                if (traditional) "Chinese (Traditional)" else "Chinese (Simplified)"
            displayLocale.script.equals("Hant", ignoreCase = true) ||
                displayLocale.country.equals("TW", ignoreCase = true) ||
                displayLocale.country.equals("HK", ignoreCase = true) ||
                displayLocale.country.equals("MO", ignoreCase = true) ->
                if (traditional) "繁體中文" else "簡體中文"
            else -> if (traditional) "繁体中文" else "简体中文"
        }
    }

    return locale.getDisplayName(displayLocale).takeIf(String::isNotBlank) ?: value
}
