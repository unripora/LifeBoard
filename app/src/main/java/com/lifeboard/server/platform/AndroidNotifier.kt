package com.lifeboard.server.platform

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lifeboard.shared.platform.DeliveryLevel
import com.lifeboard.shared.platform.NotificationRequest
import com.lifeboard.shared.platform.Notifier

/**
 * [Notifier] 的 Android 实现。
 *
 * 这里**只做「发一条普通通知」这层薄封装**，不重写三档提醒的分流：
 * 「弱提醒投不出去该不该升级成强提醒」那套判断在 `Reminder.dispatch` 里，
 * 依赖 `WeakNotifier` 的渠道状态与 `StrongRemindService` 的全屏通知。
 * 把它搬进本类会把那条已经调好的链路拆成两半，
 * 而「该提醒却没提醒」正是最不该出错的路径。
 *
 * 因此本类的定位是：给**没有现成专用通知**的调用方（如任务完成横幅、
 * 插件安装结果）提供一个平台无关的发通知入口。
 */
class AndroidNotifier(private val context: Context) : Notifier {

    private val appContext = context.applicationContext

    private val manager get() = NotificationManagerCompat.from(appContext)

    /**
     * 用户点击通知时的回调（M12）。
     *
     * 点击的落地方式与桌面端不同：这里把 payload 塞进 `PendingIntent` 的 extra，
     * 由 `MainActivity` 启动时读出来再转交给这个回调。
     * 之所以不在类内部直接发 Intent，是因为 `MainActivity` 要在自己的
     * ViewModel 装配完成后才能处理「打开哪个任务」，太早派发会丢。
     */
    @Volatile
    private var onClick: ((payload: String?) -> Unit)? = null

    override fun setOnNotificationClick(handler: (payload: String?) -> Unit) {
        onClick = handler
    }

    /** 供 `MainActivity` 收到启动 Intent 时调用，把 payload 派发回业务层 */
    fun dispatchClick(payload: String?) {
        onClick?.invoke(payload)
    }

    override suspend fun notify(request: NotificationRequest): DeliveryLevel {
        // 没权限时 `notify` 既不抛异常也不显示，只是静默丢弃。
        // 必须自己先查，否则日志会把「被系统丢掉」记成「已送达」
        if (!isPermitted()) return DeliveryLevel.NONE

        val channelBlocked = isChannelBlocked(request.channelId)
        val accepted = runCatching {
            manager.notify(request.id, buildNotification(request))
            true
        }.getOrDefault(false)

        if (!accepted) return DeliveryLevel.NONE
        return when {
            // 渠道被用户关掉：通知确实发出去了，但不会响。
            // 这里报 NONE 而不是 WEAK/STRONG，是为了让调用方能区分
            // 「配置的强度生效了」和「被用户静音了」
            channelBlocked -> DeliveryLevel.NONE
            request.important -> DeliveryLevel.STRONG
            else -> DeliveryLevel.WEAK
        }
    }

    override suspend fun cancel(id: Int) {
        runCatching { manager.cancel(id) }
    }

    override suspend fun isPermitted(): Boolean = manager.areNotificationsEnabled()

    override suspend fun openPermissionSettings() {
        // 跳应用通知设置页而不是权限申请页：用户被关掉的常常是「通知总开关」，
        // 那个开关在应用详情页里，跳权限页会让他找不到地方
        runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", appContext.packageName, null))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }
    }

    private fun isChannelBlocked(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val channel = appContext.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun buildNotification(request: NotificationRequest) =
        NotificationCompat.Builder(appContext, request.channelId)
            // 复用提醒体系已有的小图标，避免为一个通用入口再塞一个 drawable
            .setSmallIcon(com.lifeboard.app.R.drawable.ic_notify_remind)
            .setContentTitle(request.title)
            .setContentText(request.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.body))
            .setPriority(
                if (request.important) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setContentIntent(contentIntentOf(request))
            .setAutoCancel(true)
            .build()

    /**
     * 点击通知要跳的 Intent（M12）。
     *
     * `requestCode` 必须用 `request.id` 而不是常量 0：`PendingIntent` 是按
     * 「requestCode + Intent 的 action/data」判重的，全用 0 会让后一条通知
     * 覆盖前一条的 extra，点第二条却打开第一条的任务。
     *
     * `FLAG_IMMUTABLE` 是 Android 12+ 的强制要求（不写会抛异常），
     * 这里没有可变需求——payload 在创建时就定了。
     */
    private fun contentIntentOf(request: NotificationRequest): PendingIntent {
        val intent = Intent(appContext, com.lifeboard.app.MainActivity::class.java).apply {
            // 让重复点击复用同一个 Activity 实例，而不是叠一摞新窗口
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PAYLOAD, request.payload)
        }
        return PendingIntent.getActivity(
            appContext,
            request.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** `MainActivity` 从这里读回点击的是哪条通知 */
        const val EXTRA_PAYLOAD = "com.lifeboard.extra.NOTIFICATION_PAYLOAD"
    }
}
