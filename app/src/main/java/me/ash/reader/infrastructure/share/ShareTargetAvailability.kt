package me.ash.reader.infrastructure.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** 外部分享目标的探测结果：detected 表示应用/协议可被系统发现，available 表示目标 Intent 当前可执行。 */
data class ShareTargetAvailability(
    val detected: Boolean,
    val available: Boolean,
)

/** 通过 PackageManager 的真实 Intent 解析结果判断外部分享入口是否可执行。 */
internal fun Context.canResolveShareIntent(intent: Intent): Boolean =
    packageManager.run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ) != null
        } else {
            @Suppress("DEPRECATION")
            resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }

/** 检查已在 Manifest queries 中声明的包是否对当前进程可见。 */
internal fun Context.isVisiblePackage(packageName: String): Boolean =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)
