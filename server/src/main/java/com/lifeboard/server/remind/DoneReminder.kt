package com.lifeboard.server.remind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.TaskConfig
import java.time.LocalTime

/**
 * 任务完成提醒投递器。
 *
 * 与未完成的提醒是两套独立配置：
 * - [RemindLevel.NONE]   完成时不提醒，只在面板上体现
 * - [RemindLevel.WEAK]   顶部横幅通知（用户要求的形式，即微信消息那种）
 * - [RemindLevel.STRONG] 横幅通知 + 响铃 + 震动
 *
 * 另有独立的小浮窗开关（[TaskConfig.doneToastEnabled]），
 * 因为应用处于前台时系统会抑制自己的横幅通知，那时只有浮窗能让人看见。
 */
object DoneReminder {

    private const val TAG = "LifeBoardDone"

    /** 完成提醒渠道。IMPORTANCE_HIGH 是横幅的硬性条件 */
    private const val CHANNEL_ID = "lifeboard_task_done"
    private const val CHANNEL_NAME = "任务完成提醒"

    private const val MAIN_ACTIVITY = "com.lifeboard.app.MainActivity"

    /** 点击通知跳转任务用的 extra，与 [WeakNotifier] 保持一致 */
    const val EXTRA_TASK_ID = "extra_task_id"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "任务达成时提示"
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 投递完成提醒。
     *
     * @return 是否发出了横幅通知（响铃是横幅的附加动作，不单独返回）
     */
    fun dispatch(context: Context, task: TaskConfig, value: Double): Boolean {
        val level = task.doneRemindLevel
        if (level == RemindLevel.NONE) return false

        ensureChannel(context)
        val notified = notifyBanner(context, task, value)
        if (level == RemindLevel.STRONG) {
            StrongRemindService.start(context, task, doneTitle(task), doneBody(task, value))
        }
        return notified
    }

    /**
     * 发一条横幅通知。
     *
     * 应用处于前台时系统不会展示横幅，只会静默放进通知栏——
     * 这是平台行为，无法绕过；那种场景由小浮窗兜底。
     */
    private fun notifyBanner(context: Context, task: TaskConfig, value: Double): Boolean {
        val title = doneTitle(task)
        val body = doneBody(task, value)

        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY)
            putExtra(EXTRA_TASK_ID, task.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.lifeboard.server.R.drawable.ic_notify_remind)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pending)
            .setAutoCancel(true)
            // 同一任务当天反复完成时覆盖旧通知，不堆叠
            .setOnlyAlertOnce(true)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(task.id.toInt(), notification)
            true
        }.onFailure { Log.w(TAG, "notify done failed", it) }
            .getOrDefault(false)
    }

    private fun doneTitle(task: TaskConfig): String = "「${task.title}」已完成"

    private fun doneBody(task: TaskConfig, value: Double): String {
        val unit = task.metricBucket.unit
        val progress = if (unit.isEmpty()) {
            "目标 ${task.threshold.toInt()} 已达成"
        } else {
            "${value.toInt()} / ${task.threshold.toInt()} $unit，目标已达成"
        }
        return "$progress · ${LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }}"
    }

    /** 取消某任务的完成通知（任务被改回未完成或删除时） */
    fun cancel(context: Context, taskId: Long) {
        NotificationManagerCompat.from(context).cancel(taskId.toInt())
    }
}
