package com.lifeboard.server.remind

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lifeboard.core.model.TaskConfig

/**
 * 强提醒前台服务（M2.4）。
 *
 * 为什么不用系统闹钟：澎湃OS 3 的「后台弹出页面」权限默认拒绝，
 * `AlarmClock.ACTION_SET_ALARM + EXTRA_SKIP_UI` 大概率被静默拦截或弹到后台看不到。
 * 改为自建前台 Service 播放 STREAM_ALARM 铃声，权限暴露面小，且铃声走闹钟通道，
 * 静音模式下依然响——这正是"强提醒"需要的效果。
 *
 * 存活策略：
 * - 前台 Service + 常驻通知，避免被系统直接回收
 * - 申请 AudioFocus，抢占其他音频（如正在放的音乐），保证能听到
 * - 音量渐变：从 0 升到目标音量，避免突然炸响
 */
class StrongRemindService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 「关闭铃声」是另一条路径：不响铃，只做收尾
        if (intent?.action == ACTION_STOP) {
            stopAlarm()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 重复触发时先清理上一轮声音，避免叠加成噪音
        stopAlarm()

        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "有任务未完成" }
        val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, 0L) ?: 0L

        startForeground(FOREGROUND_ID, buildForegroundNotification(title, body, taskId, "正在响铃…"))
        startAlarm(taskId)
        startVibration()

        // START_STICKY 会在被杀后重启，但重启时 intent 为 null，此处选择 NOT_STICKY：
        // 响铃是一次性动作，重启后应重新判定而不是凭空再响一次。
        return START_NOT_STICKY
    }

    private fun startAlarm(taskId: Long) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am

        // 请求音频焦点：允许短暂降低其他音源（duck），响铃结束后自动恢复
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = focus
        runCatching { am.requestAudioFocus(focus) }
            .onFailure { Log.w(TAG, "requestAudioFocus failed", it) }

        // 优先用系统默认闹钟铃声，没有就退回通知音
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(this@StrongRemindService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrElse {
            Log.e(TAG, "play alarm failed", it)
            null
        }

        // 音量渐变：避免在安静环境突然最大声吓到人
        player?.let { p -> fadeIn(p) }
    }

    private fun fadeIn(player: MediaPlayer) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = (max * TARGET_VOLUME_RATIO).toInt().coerceIn(1, max)
        runCatching {
            player.setVolume(0f, 0f)
            // 每 STEP_INTERVAL_MS 提升一档，共 STEPS 档
            for (step in 1..STEPS) {
                val ratio = step.toFloat() / STEPS
                player.setVolume(ratio, ratio)
                Thread.sleep(STEP_INTERVAL_MS)
            }
            // 渐变到目标音量（不改系统音量，只改播放器自身音量）
            player.setVolume(1f, 1f)
            Log.i(TAG, "fade-in done, system alarm max=$max target=$target")
        }.onFailure { Log.w(TAG, "fadeIn interrupted", it) }
    }

    private fun startVibration() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator = vib
        runCatching {
            val pattern = longArrayOf(0, 600, 400, 600, 400)
            vib.vibrate(VibrationEffect.createWaveform(pattern, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w(TAG, "vibrate failed", it) }
    }

    private fun buildForegroundNotification(
        title: String,
        body: String,
        taskId: Long,
        statusText: String,
    ): Notification {
        ensureChannel()
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, StrongRemindService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            taskId.toInt(),
            Intent().apply {
                setClassName(packageName, MAIN_ACTIVITY)
                putExtra(EXTRA_TASK_ID, taskId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setSmallIcon(com.lifeboard.server.R.drawable.ic_notify_remind)
            .setContentTitle(title)
            .setContentText(body.ifBlank { statusText })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openIntent)
            .addAction(0, "关闭铃声", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID_ALARM,
            "强提醒闹钟",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "任务到检测时段仍未完成时的强制响铃"
            setSound(null, null) // 声音由 MediaPlayer 控制，不走渠道音
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopAlarm() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        audioFocusRequest?.let { focus ->
            runCatching { audioManager?.abandonAudioFocusRequest(focus) }
        }
        audioFocusRequest = null
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LifeBoardStrong"
        private const val CHANNEL_ID_ALARM = "lifeboard_remind_strong"
        private const val FOREGROUND_ID = 1001
        private const val STOP_REQUEST_CODE = 1002
        private const val MAIN_ACTIVITY = "com.lifeboard.app.MainActivity"

        const val ACTION_STOP = "com.lifeboard.action.STOP_STRONG_REMIND"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
        const val EXTRA_TASK_ID = "extra_task_id"

        private const val STEPS = 10
        private const val STEP_INTERVAL_MS = 400L
        /** 目标音量占系统闹钟最大音量的比例，留出余量避免刺耳 */
        private const val TARGET_VOLUME_RATIO = 0.8f

        /**
         * 触发强提醒。
         *
         * 用 [ContextCompat.startForegroundService] 而不是裸 startService：
         * Android 8+ 后台启动普通 Service 会直接抛异常。
         */
        fun start(context: Context, evaluation: Evaluation) {
            start(context, evaluation.task, "「${evaluation.task.title}」还没完成", bodyOf(evaluation))
        }

        /**
         * 通用入口：未完成提醒与完成提醒共用同一套响铃。
         *
         * 两者只是文案不同——响铃、震动、停止按钮的实现完全一样。
         */
        fun start(context: Context, task: TaskConfig, title: String, body: String) {
            val intent = Intent(context, StrongRemindService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_TASK_ID, task.id)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "start strong remind failed", it) }
        }

        private fun bodyOf(evaluation: Evaluation): String {
            val unit = evaluation.task.metricBucket.unit
            return if (unit.isEmpty()) {
                "目标 ${evaluation.task.threshold.toInt()}"
            } else {
                "当前 ${evaluation.value.toInt()} / 目标 ${evaluation.task.threshold.toInt()} $unit"
            }
        }
    }
}
