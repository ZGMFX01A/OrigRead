package me.ash.reader.llm.selection

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * LLM edition 的系统 `PROCESS_TEXT` 接收器。
 *
 * 该 Activity 不提供通用聊天页面：只有当前进程存在活跃 OrigRead 阅读页时才接受文本，并立即把选区转交给
 * 阅读页的文章助手；否则直接结束。组件本身也只在阅读页 RESUMED 期间动态启用，避免常驻其他 App 的选择菜单。
 */
class LlmProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeProcessTextIntent(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun consumeProcessTextIntent(intent: Intent?) {
        if (!LlmSelectedTextBridge.hasActiveReader()) {
            // 如果进程曾异常结束导致 PackageManager 保留了 enabled 状态，首次误触时立即自愈。
            setLlmProcessTextComponentEnabled(this, false)
            return
        }
        if (intent?.action != Intent.ACTION_PROCESS_TEXT || intent.type != "text/plain") return
        val selectedText =
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?.toString()
                ?.trim()
                ?.take(MAX_SELECTED_TEXT_CHARS)
                ?.takeIf(String::isNotBlank)
                ?: return
        LlmSelectedTextBridge.emit(selectedText)
    }
}

/** 当前阅读页与透明 PROCESS_TEXT Activity 之间的同进程事件桥；不持久化选区。 */
internal object LlmSelectedTextBridge {
    private val activeReaders = AtomicInteger(0)
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = mutableEvents.asSharedFlow()

    fun registerReader() {
        activeReaders.incrementAndGet()
    }

    fun unregisterReader() {
        activeReaders.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    fun hasActiveReader(): Boolean = activeReaders.get() > 0

    fun emit(text: String) {
        mutableEvents.tryEmit(text)
    }
}

/** 动态启停系统文本处理入口；DONT_KILL_APP 保证切换状态不会重建当前阅读进程。 */
internal fun setLlmProcessTextComponentEnabled(
    context: Context,
    enabled: Boolean,
) {
    context.packageManager.setComponentEnabledSetting(
        ComponentName(context, LlmProcessTextActivity::class.java),
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
}

/** 防止异常应用向当前阅读页一次性注入超大文本；正文完整上下文仍由 OrigRead 自己提供。 */
private const val MAX_SELECTED_TEXT_CHARS = 20_000
