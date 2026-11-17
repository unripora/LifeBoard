package com.lifeboard.server.platform

import android.content.Context
import android.os.Build
import com.lifeboard.shared.platform.AppInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [AppInfo] 的 Android 实现，取自 `Build` 与 `PackageManager`。
 *
 * 关于页（M3-3.2d）要显示版本、构建号、系统要求与更新时间，
 * 全部走 PackageManager 而不是 `BuildConfig`：本工程没开 buildConfig 生成，
 * 而 PackageManager 拿到的还是安装包的真值。
 */
class AndroidAppInfo(context: Context) : AppInfo {

    private val appContext = context.applicationContext

    private val packageInfo = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }.getOrNull()

    private val applicationInfo = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getApplicationInfo(appContext.packageName, 0)
    }.getOrNull()

    override val packageId: String = appContext.packageName

    override val versionName: String = packageInfo?.versionName.orEmpty()

    override val osDescription: String =
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    override val deviceDescription: String = "${Build.BRAND} ${Build.MODEL}"

    override fun nowForExport(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    override val appLabel: String = applicationInfo
        ?.let { runCatching { appContext.packageManager.getApplicationLabel(it).toString() }.getOrNull() }
        .orEmpty()
        .ifBlank { "LifeBoard" }

    override val buildNumber: Long = when {
        packageInfo == null -> 0L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> packageInfo.longVersionCode
        else -> versionCodeCompat()
    }

    override val minPlatform: String = "Android ${apiToVersion(applicationInfo?.minSdkVersion ?: 0)}"

    override val targetPlatform: String = "Android ${apiToVersion(applicationInfo?.targetSdkVersion ?: 0)}"

    override val updatedAt: String = packageInfo?.lastUpdateTime
        ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(it)) }
        ?: "未知"

    /**
     * Android 9 以下的旧字段。
     *
     * 抽成方法是为了放 `@Suppress`——`when` 的分支上不能挂注解。
     */
    @Suppress("DEPRECATION")
    private fun versionCodeCompat(): Long = packageInfo?.versionCode?.toLong() ?: 0L
}

/**
 * API 级别转成用户认得的 Android 版本号，只覆盖项目实际会遇到的区间。
 *
 * 放在实现方而不是界面层：界面不该知道什么是 API 级别，
 * 桌面实现也用不上这张表。
 */
private fun apiToVersion(api: Int): String = when (api) {
    29 -> "10"
    30 -> "11"
    31 -> "12"
    32 -> "12L"
    33 -> "13"
    34 -> "14"
    35 -> "15"
    36 -> "16"
    else -> if (api <= 0) "未知" else "API $api"
}
