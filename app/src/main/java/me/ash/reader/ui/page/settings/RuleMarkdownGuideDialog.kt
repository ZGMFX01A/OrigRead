package me.ash.reader.ui.page.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.ash.reader.ui.page.home.reading.AiMarkdown
import java.util.Locale

/**
 * 在应用内展示规则说明和用户手册等 Markdown 长文档。
 *
 * 复用现有轻量 Markdown 渲染器，避免为了两个离线帮助页引入 WebView 或第二套 Markdown 依赖。
 */
@Composable
internal fun RuleMarkdownGuideDialog(
    title: String,
    assetName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentLanguage = LocalConfiguration.current.locales[0].language
    val assetPath =
        remember(assetName, currentLanguage) {
            // 当前仅维护中英文文档；简繁中文共用中文文档，其他语言统一回退英文。
            val localeSuffix =
                if (currentLanguage == Locale.CHINESE.language) "zh-CN" else "en"
            "rule-guides/$assetName-$localeSuffix.md"
        }
    val markdown =
        remember(assetPath) {
            runCatching {
                context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse {
                val message =
                    if (currentLanguage == Locale.CHINESE.language) "文档读取失败" else "Failed to load document"
                "# $title\n\n$message: ${it.message.orEmpty()}"
            }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Outlined.Close, contentDescription = null)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    AiMarkdown(markdown)
                }
            }
        }
    }
}
