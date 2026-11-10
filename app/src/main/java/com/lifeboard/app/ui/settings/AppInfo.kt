package com.lifeboard.app.ui.settings

import android.content.Context
import android.os.Build

/**
 * 关于面板展示的应用信息。
 *
 * @property versionName 版本名，形如 `0.3.3`，由 build.gradle.kts 手工维护
 * @property versionCode 构建时自动生成的递增号，排查「装的是哪一次构建」靠它
 * @property minSdk 应用支持的最低系统版本
 * @property targetSdk 编译时对齐的目标版本
 * @property updatedAt 安装包的最后更新时间，用来判断手上是不是最新包
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val updatedAt: String,
)

/**
 * 读取应用自身的展示信息。
 *
 * 走 PackageManager 而不是 `BuildConfig`：本模块没有开启 buildConfig 生成，
 * 为了几个字段去改构建开关不值得，PackageManager 拿到的还是安装包的真值。
 * 任何一项读失败都退化成占位文本，关于面板不该因为取不到信息就崩。
 */
@Suppress("DEPRECATION")
fun loadAppInfo(context: Context): AppInfo {
    val pm = context.packageManager
    val pkg = context.packageName
    val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
    val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()

    val label = appInfo
        ?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
        ?: "LifeBoard"
    val versionName = info?.versionName ?: "未知"
    val versionCode = when {
        info == null -> 0L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
        else -> info.versionCode.toLong()
    }
    val updatedAt = info?.lastUpdateTime
        ?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(it)) }
        ?: "未知"

    return AppInfo(
        label = label,
        packageName = pkg,
        versionName = versionName,
        versionCode = versionCode,
        minSdk = appInfo?.minSdkVersion ?: 0,
        targetSdk = appInfo?.targetSdkVersion ?: 0,
        updatedAt = updatedAt,
    )
}
