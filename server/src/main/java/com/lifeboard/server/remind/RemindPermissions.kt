package com.lifeboard.server.remind

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.lifeboard.server.collect.SettingsNavigator
import com.lifeboard.server.notice.NoticeListenerService

/**
 * 提醒相关权限自检（M2.6）。
 *
 * 澎湃OS 3 上「发通知」和「后台弹窗」默认是关的，且开关分散在多个设置页。
 * 这里把每一项做成可检测的状态 + 一键跳转，避免用户靠猜。
 */
object RemindPermissions {

    /** 单项权限的检测结果 */
    data class Item(
        val key: String,
        val title: String,
        val description: String,
        val granted: Boolean,
        val required: Boolean,
    )

    /**
     * 全面自检。
     *
     * 顺序按"缺了会彻底失效"到"缺了只是体验差"排列，
     * 让用户先处理最关键的一项，不要一次抛五个错误吓人。
     */
    fun checkAll(context: Context): List<Item> {
        // 渠道还没建时先建出来，否则读不到用户对它的关闭状态
        WeakNotifier.ensureChannel(context)
        return listOf(
            Item(
                key = KEY_EXACT_ALARM,
                title = "精确闹钟",
                description = "缺了会在 Doze 模式下延后触发，可能错过检测时段",
                granted = RemindScheduler.canScheduleExact(context),
                required = true,
            ),
            Item(
                key = KEY_NOTIFICATION,
                title = "通知权限",
                description = "弱提醒依赖通知；缺了会自动升级为强提醒响铃",
                granted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                required = true,
            ),
            Item(
                key = KEY_CHANNEL,
                title = "打卡提醒渠道",
                description = "关闭后弱提醒不再投递，也不会升级为响铃",
                granted = !WeakNotifier.isChannelBlocked(context),
                required = false,
            ),
            Item(
                key = KEY_BATTERY,
                title = "忽略电池优化",
                description = "避免后台被清理导致闹钟不触发",
                granted = isIgnoringBatteryOptimizations(context),
                required = false,
            ),
            Item(
                key = KEY_NOTICE_LISTENER,
                title = "通知使用权（M4）",
                description = "识别飞书打卡与多邻国目标达成，缺了这两个源永远没有数据",
                granted = NoticeListenerService.isEnabled(context),
                required = false,
            ),
        )
    }

    /**
     * 跳转到对应设置页。
     *
     * @return true 表示成功打开了设置页
     */
    fun open(context: Context, key: String): Boolean = when (key) {
        KEY_EXACT_ALARM -> openExactAlarmSettings(context)
        KEY_NOTIFICATION -> SettingsNavigator.openNotificationSettings(context)
        KEY_CHANNEL -> SettingsNavigator.openNotificationSettings(context)
        KEY_BATTERY -> openBatterySettings(context)
        KEY_NOTICE_LISTENER -> SettingsNavigator.openNotificationListenerSettings(context)
        else -> false
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return SettingsNavigator.openExactAlarmSettings(context)
    }

    private fun openBatterySettings(context: Context): Boolean =
        SettingsNavigator.openBatteryOptimizationSettings(context)

    const val KEY_EXACT_ALARM = "exact_alarm"
    const val KEY_NOTIFICATION = "notification"
    const val KEY_CHANNEL = "remind_channel"
    const val KEY_BATTERY = "battery"
    const val KEY_NOTICE_LISTENER = "notice_listener"
}
