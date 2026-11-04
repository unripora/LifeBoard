package com.lifeboard.server.remind

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.TaskConfig

/**
 * 弱提醒投递器（M2.3）。
 *
 * 用普通通知渠道提示未达成。澎湃OS 对「后台发送本地通知」有独立开关（默认拒绝），
 * 因此 [canNotify] 必须在投递前真实检查，不能假设 POST_NOTIFICATIONS 授予就够了。
 */
object WeakNotifier {

    const val CHANNEL_ID = "lifeboard_remind_weak"
    private const val CHANNEL_NAME = "打卡提醒"

    /** 点击通知跳转的动作，MainActivity 侧据此定位任务 */
    const val EXTRA_TASK_ID = "extra_task_id"

    /** 主界面类名。server 模块不能反向依赖 app，故用字符串约定 */
    private const val MAIN_ACTIVITY = "com.lifeboard.app.MainActivity"

    /**
     * 确保通知渠道已创建。重复调用安全。
     *
     * 渠道重要性用 HIGH 才能在横幅上露出来，否则容易只在通知栏静静地躺着，
     * 对"弱提醒"来说就失去了意义。
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "任务到检测时段仍未完成时提示"
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 判断当前是否具备发通知的条件。
     *
     * 三步都过才算可用：
     * 1. POST_NOTIFICATIONS 已授予（Android 13+）
     * 2. 系统通知总开关未关闭
     * 3. 本应用的提醒渠道未被用户关闭
     */
    fun canNotify(context: Context): Boolean {
        if (!hasNotificationPermission(context)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return !isChannelBlocked(context)
    }

    /**
     * 用户是否主动关闭了「打卡提醒」这个渠道。
     *
     * 和 [canNotify] 的区别决定了失败后怎么办：
     * - 关渠道是明确的用户意图（"别用这种方式吵我"），不该硬改成响铃
     * - 缺权限 / 关总开关是配置没做完，升级成强提醒是兜底
     *
     * 渠道尚未创建时返回 false——首次投递顺带建，不算被关闭。
     */
    fun isChannelBlocked(context: Context): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 发出一条弱提醒。
     *
     * @return true 表示系统接受了这次投递
     */
    fun notify(context: Context, evaluation: Evaluation): Boolean {
        ensureChannel(context)
        if (!canNotify(context)) return false

        val task = evaluation.task
        val title = "「${task.title}」还没完成"
        val body = buildBody(task, evaluation.value)

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
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(task.id.toInt(), notification)
            true
        }.getOrDefault(false)
    }

    private fun buildBody(task: TaskConfig, value: Double): String {
        val unit = task.metricBucket.unit
        val progress = if (task.metricBucket.unit.isEmpty()) {
            "目标 ${task.threshold.toInt()}"
        } else {
            "当前 ${value.toInt()} / 目标 ${task.threshold.toInt()} $unit"
        }
        val window = "检测时段 ${formatMinute(task.windowStartMinute)} - ${formatMinute(task.windowEndMinute)}"
        return "$progress · $window"
    }

    private fun formatMinute(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** 取消某任务的通知（任务完成或被删除时调用） */
    fun cancel(context: Context, taskId: Long) {
        NotificationManagerCompat.from(context).cancel(taskId.toInt())
    }

    /** 该档位是否会走弱提醒 */
    fun handles(level: RemindLevel): Boolean = level == RemindLevel.WEAK
}
