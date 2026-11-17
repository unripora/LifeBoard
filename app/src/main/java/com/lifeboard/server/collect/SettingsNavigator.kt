package com.lifeboard.server.collect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 系统设置页跳转封装。
 *
 * 各厂商 ROM 对设置页 action 的支持不一致，因此每项都提供多个候选 Intent，
 * 逐个尝试 `resolveActivity`，第一个能打开的即使用。
 * 全部失败时回落到应用详情页，让用户自己去开权限，避免界面卡死。
 */
object SettingsNavigator {

    /**
     * 跳转到「使用情况访问权」授权页（M1）。
     *
     * @return true 表示成功打开了某个设置页
     */
    fun openUsageAccess(context: Context): Boolean {
        val candidates = listOf(
            // 标准页：应用列表，点进去开"允许访问使用记录"
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            // 直接定位到本应用
            Intent(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
            // 部分 ROM 把该权限挂在"特殊应用权限"下
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
        return startFirstResolvable(context, candidates)
    }

    /** 跳转到应用详情页，用于自启动、后台弹窗等厂商私有权限的兜底入口 */
    fun openAppDetails(context: Context): Boolean = startFirstResolvable(
        context,
        listOf(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        ),
    )

    /** 跳转到本应用的通知设置页（M2 弱提醒用） */
    fun openNotificationSettings(context: Context): Boolean = startFirstResolvable(
        context,
        listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        ),
    )

    /** 跳转到「闹钟和提醒」特殊权限页（Android 12+） */
    fun openExactAlarmSettings(context: Context): Boolean = startFirstResolvable(
        context,
        listOf(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        ),
    )

    /** 跳转到电池优化白名单页 */
    fun openBatteryOptimizationSettings(context: Context): Boolean = startFirstResolvable(
        context,
        listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        ),
    )

    /**
     * 跳转到「通知使用权」授权页（M4）。
     *
     * 这个权限没有 package 定向的 action，只能打开列表页让用户自己勾选。
     * 部分 ROM（含澎湃OS）把入口放在「特殊应用权限」下，故加一个候选。
     */
    fun openNotificationListenerSettings(context: Context): Boolean = startFirstResolvable(
        context,
        listOf(
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        ),
    )

    private fun startFirstResolvable(context: Context, candidates: List<Intent>): Boolean {
        candidates.forEach { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching {
                context.packageManager.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
            if (!resolvable) return@forEach
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            if (ok) return true
        }
        return false
    }
}
