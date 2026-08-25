package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.ash.reader.llm.selection.LlmSelectedTextBridge
import me.ash.reader.llm.selection.setLlmProcessTextComponentEnabled

/**
 * LLM edition 的正文选区宿主。
 *
 * 组件只在当前阅读页可处理文章且宿主 Activity 处于 RESUMED 时对系统暴露 PROCESS_TEXT，避免把“AI”
 * 长期挂到其他应用的文本选择菜单；收到选区后仍由公共 ReadingPage 决定打开哪一篇文章的助手。
 */
@Composable
internal fun EditionSelectedTextActionHost(
    enabled: Boolean,
    onSelectedText: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSelectedText = rememberUpdatedState(onSelectedText)

    DisposableEffect(enabled, lifecycleOwner, context) {
        var registered = false

        fun updateRegistration(active: Boolean) {
            if (active == registered) return
            registered = active
            if (active) {
                LlmSelectedTextBridge.registerReader()
            } else {
                LlmSelectedTextBridge.unregisterReader()
            }
            setLlmProcessTextComponentEnabled(context, active)
        }

        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> updateRegistration(enabled)
                    Lifecycle.Event.ON_STOP,
                    Lifecycle.Event.ON_DESTROY -> updateRegistration(false)
                    Lifecycle.Event.ON_PAUSE -> {
                        // PROCESS_TEXT 会启动本应用的透明 Activity，宿主阅读页会先收到 ON_PAUSE。
                        // 此处不能提前注销桥或禁用组件，否则接收 Activity onCreate 时会误判“无活跃阅读页”并丢掉选区。
                        // 真正离开前台会继续进入 ON_STOP；透明 Activity 结束后则直接回到 ON_RESUME。
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateRegistration(enabled && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            updateRegistration(false)
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        LlmSelectedTextBridge.events.collect { selectedText ->
            latestOnSelectedText.value(selectedText)
        }
    }
}
