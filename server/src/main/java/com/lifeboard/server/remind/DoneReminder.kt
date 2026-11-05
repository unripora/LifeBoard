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

    /**
     * 完成提醒的通知 id 偏移量。
     *
     * 不能直接用 task.id：[WeakNotifier] 的未完成提醒也用 task.id 当通知 id，
     * 两者会互相覆盖——「还没完成」刚弹完就被「已完成」顶掉，
     * 或者反过来把声音一起吃掉了。
     */
    private const val DONE_ID_OFFSET = 100_000

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
            // 横幅通知需要在锁屏上也可见，否则息屏时只有声音没有内容
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
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
        val notified = notifyBanner(context, task, value, level)
        if (level == RemindLevel.STRONG) {
            // 后台启动前台服务在小米等 ROM 上常被拒。被拒时不能就这么算了，
            // 否则 STRONG 档表现和静音一样；下面 notifyBanner 已经带了声音，
            // 这里只是把「响铃失败」记下来
            val ringing = StrongRemindService.start(context, task, doneTitle(task), doneBody(task, value))
            if (!ringing) Log.w(TAG, "strong remind 服务未启动，已退回通知音")
        }
        return notified
    }

    /**
     * 发一条横幅通知。
     *
     * 应用处于前台时系统不会展示横幅，只会静默放进通知栏——
     * 这是平台行为，无法绕过；那种场景由小浮窗兜底。
     *
     * @param level 完成提醒档位。WEAK 只弹横幅不发声，STRONG 额外响铃 + 震动
     */
    private fun notifyBanner(
        context: Context,
        task: TaskConfig,
        value: Double,
        level: RemindLevel,
    ): Boolean {
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.lifeboard.server.R.drawable.ic_notify_remind)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // CATEGORY_STATUS 属于「状态类」通知（下载进度、电量这类），
            // 系统明确不会为它弹横幅；要弹横幅必须用 REMINDER。
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (level == RemindLevel.STRONG) {
            // 震动靠渠道已开启的 enableVibration；声音走默认通知音。
            // 这里不重复 setOnlyAlertOnce：它会让同 id 的后续通知彻底静默
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        } else {
            // WEAK：只要横幅，不发声。用渠道级静音会被用户改成有声音，
            // 所以显式把这一条设为 silent
            builder.setSilent(true)
        }

        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(task.id.toInt() + DONE_ID_OFFSET, builder.build())
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
        NotificationManagerCompat.from(context).cancel(taskId.toInt() + DONE_ID_OFFSET)
    }
}
