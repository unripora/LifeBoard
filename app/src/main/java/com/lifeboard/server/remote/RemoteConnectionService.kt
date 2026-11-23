package com.lifeboard.server.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lifeboard.shared.log.AppLogger

/**
 * 会话常驻的前台服务（M7.1 / 任务 2.3）。
 *
 * ## 为什么需要前台服务
 *
 * 会话要维持一条长连接并持续接收流式回复。普通后台进程在息屏后
 * 会被系统回收，表现是「手机上聊到一半突然不回了」，所以必须用前台服务
 * 把进程优先级提上去，并用常驻通知告诉用户「连接还开着」。
 *
 * ## 谁来控制生命周期
 *
 * 由 `AndroidPluginInvoker` 在会话启动/停止时调用 [start] / [stop]，
 * 插件侧主动关闭会话（`ctx.session.emit({type:'closed'})`）也会撤掉通知。
 * 服务本身不持有连接：连接活在插件控制器里，服务只负责进程优先级与通知。
 *
 * ## 为什么不用 START_STICKY
 *
 * 进程被杀后重启时 intent 为 null，而连接已经没了。粘性重启只会留下
 * 一条「已连接」的通知却没有任何连接——比不重启更误导用户。
 */
class RemoteConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLogger.info("远程连接：用户主动断开")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_UPDATE -> {
                val status = intent.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "已连接" }
                AppLogger.debug("远程连接：状态更新 $status")
                startForeground(NOTIFICATION_ID, buildNotification(status))
                return START_NOT_STICKY
            }
        }

        val status = intent?.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "正在连接…" }
        AppLogger.info("远程连接：前台服务启动（$status）")
        startForeground(NOTIFICATION_ID, buildNotification(status))
        return START_NOT_STICKY
    }

    private fun buildNotification(status: String): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent().apply {
                setClassName(packageName, MAIN_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, RemoteConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("插件会话")
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, "断开", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "插件会话",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持与远程主机的连接，接收流式回复"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "lifeboard_remote_connection"
        private const val NOTIFICATION_ID = 2001
        private const val REQUEST_OPEN = 2002
        private const val REQUEST_STOP = 2003
        private const val MAIN_ACTIVITY = "com.lifeboard.app.MainActivity"

        const val ACTION_START = "com.lifeboard.action.START_REMOTE_CONNECTION"
        const val ACTION_STOP = "com.lifeboard.action.STOP_REMOTE_CONNECTION"
        const val ACTION_UPDATE = "com.lifeboard.action.UPDATE_REMOTE_CONNECTION"
        const val EXTRA_STATUS = "extra_status"

        /**
         * 启动前台服务。
         *
         * 用 [ContextCompat.startForegroundService]：Android 8+ 后台直接 startService
         * 会抛异常。返回 false 表示系统拒绝（后台限制常见），调用方应提示用户
         * 「连接需要保持应用在前台」而不是静默失败。
         */
        fun start(context: Context, status: String = "正在连接…"): Boolean {
            val intent = Intent(context, RemoteConnectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, status)
            }
            return runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLogger.error("远程连接：启动前台服务失败", it) }
                .isSuccess
        }

        /** 更新通知文案（连接状态变化时调用，不重启服务） */
        fun update(context: Context, status: String) {
            val intent = Intent(context, RemoteConnectionService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_STATUS, status)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLogger.error("远程连接：更新状态失败", it) }
        }

        /** 主动断开：停止前台服务并移除通知 */
        fun stop(context: Context) {
            // 用 stopService 而不是 startService(ACTION_STOP)：服务可能已在后台，
            // Android 8+ 对后台 startService 有限制，stopService 不受影响
            runCatching { context.stopService(Intent(context, RemoteConnectionService::class.java)) }
                .onFailure { AppLogger.error("远程连接：停止前台服务失败", it) }
        }
    }
}
