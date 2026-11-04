package com.lifeboard.server.remind

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.CaptureRuleEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.TaskEntity
import com.lifeboard.server.notice.toRuleSchedule
import com.lifeboard.server.panel.toScheduleRule
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 提醒调度器（M2.2）。
 *
 * 调度模型：每个任务在其「检测时段结束前 CHECK_LEAD_MINUTES 分钟」设一个精确闹钟。
 * 之所以卡在时段末尾而不是开始，是因为用户的需求是"到点了还没做就提醒"——
 * 时段开始时数据本来就不该达标，那时候提醒没有意义。
 *
 * 用 `setExactAndAllowWhileIdle` 而非 `setExact`：Doze 模式下后者会被推迟到维护窗口，
 * 而提醒是有时间窗口的（过了检测时段提醒就没用了）。
 */
object RemindScheduler {

    private const val TAG = "LifeBoardScheduler"
    private const val ACTION_REMIND = "com.lifeboard.action.REMIND_TASK"
    private const val EXTRA_TASK_ID = "task_id"
    private const val EXTRA_WINDOW_INDEX = "window_index"

    /**
     * 提前量：在检测时段结束前几分钟判定。
     *
     * @deprecated 判定时刻已改为「时段结束 + 任务配置的偏移」，
     * 见 [TaskConfig.windowOffsetMinutes]。保留常量仅为兼容旧引用。
     */
    @Deprecated("使用 TaskConfig.windowOffsetMinutes")
    const val CHECK_LEAD_MINUTES = 5

    /** 单个任务最多注册的时段数，与界面上的上限保持一致（防止闹钟数量爆炸） */
    const val MAX_WINDOWS = 8

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * 每个时段一个闹钟，所以 PendingIntent 必须按时段序号区分。
     *
     * requestCode 用 `taskId * 10 + index`（时段上限 [MAX_WINDOWS] = 8）。
     * 只带 taskId 会让第二个时段的 PendingIntent 与第一个"相等"，
     * `FLAG_UPDATE_CURRENT` 直接覆盖掉前一个，表现为"配了两个时段却只响一次"。
     */
    private fun pendingIntent(context: Context, taskId: Long, index: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (taskId * 10 + index).toInt(),
            Intent(context, RemindReceiver::class.java).apply {
                action = ACTION_REMIND
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_WINDOW_INDEX, index)
                data = android.net.Uri.parse("lifeboard://remind/$taskId/$index")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 为单个任务注册当日全部时段的闹钟。
     *
     * 判定时刻 = 今天 + 该时段结束 + 偏移分钟。
     * 偏移来自任务配置（-60..60），正数延后、负数提前；
     * 默认 +5 是因为规则时段同时是读屏窗口，准点判定会「提醒了但已经读不到」。
     * 已过去的时刻不注册（避免立刻响一次）；日期或周期不匹配时一个都不注册。
     */
    fun scheduleTask(
        context: Context,
        taskId: Long,
        schedule: ScheduleRule,
        enabled: Boolean,
        offsetMinutes: Int = 5,
    ) {
        cancelTask(context, taskId)
        if (!enabled) return
        if (!schedule.isDateActive(LocalDate.now())) {
            Log.d(TAG, "task $taskId: date not active today, skip")
            return
        }
        schedule.effectiveWindows.take(MAX_WINDOWS).forEachIndexed { index, window ->
            scheduleSingle(context, taskId, index, window.endMinute, offsetMinutes)
        }
    }

    /** 注册单个时段闹钟 */
    private fun scheduleSingle(
        context: Context,
        taskId: Long,
        index: Int,
        endMinute: Int,
        offsetMinutes: Int,
    ) {
        val am = alarmManager(context) ?: return
        val triggerAt = triggerTimeFor(endMinute, offsetMinutes) ?: run {
            Log.d(TAG, "task $taskId window#$index already passed today, skip")
            return
        }
        val pi = pendingIntent(context, taskId, index)
        val ok = runCatching {
            // Android 12+ 无精确闹钟权限时 setExactAndAllowWhileIdle 会抛 SecurityException
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                Log.w(TAG, "exact alarm not permitted, fallback to inexact for task $taskId#$index")
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            true
        }.getOrElse {
            Log.e(TAG, "schedule task $taskId#$index failed", it)
            false
        }
        if (ok) Log.i(TAG, "task $taskId#$index scheduled at ${java.util.Date(triggerAt)}")
    }

    /** 取消单个任务的全部时段闹钟 */
    fun cancelTask(context: Context, taskId: Long) {
        val am = alarmManager(context) ?: return
        repeat(MAX_WINDOWS) { index ->
            am.cancel(pendingIntent(context, taskId, index))
        }
    }

    /**
     * 重排所有启用任务。
     * 触发时机：应用启动、任务增删改、开机重启、日期切换。
     */
    fun rescheduleAll(context: Context) {
        runBlocking {
            val db = LifeBoardDatabase.get(context)
            val tasks = db.taskDao().getEnabled()
            val rules = db.captureRuleDao().getAll().associateBy { it.id }
            tasks.forEach { t ->
                scheduleTask(
                    context,
                    t.id,
                    t.resolveSchedule(rules[t.ruleId]),
                    t.enabled,
                    t.windowOffsetMinutes,
                )
            }
            Log.i(TAG, "rescheduled ${tasks.size} tasks")
        }
    }

    /**
     * 任务的实际生效规则。
     *
     * 关联了采集规则的任务，时段与周期完全取自那条规则——
     * 用户在规则里配了 09:00-09:30 和 18:00-18:30，任务就该在这两段之外才提醒，
     * 不需要再去任务页重复设一遍检测时段。
     * 未关联或规则已被删除时，回落到任务自身的 schedule，老数据行为不变。
     */
    private fun TaskEntity.resolveSchedule(rule: CaptureRuleEntity?): ScheduleRule =
        rule?.toRuleSchedule() ?: toScheduleRule()

    /**
     * 计算今天的提醒触发时刻（毫秒），时刻已过或时间非法时返回 null。
     *
     * @param offsetMinutes 相对时段结束的偏移，-60..60；负数提前、正数延后
     */
    private fun triggerTimeFor(endMinute: Int, offsetMinutes: Int): Long? {
        if (endMinute !in 0..(24 * 60 - 1)) return null
        val triggerMinuteOfDay = endMinute + offsetMinutes
        // 偏移可能把时刻推到昨天或明天，跨天时按「不在今天」处理，不注册
        if (triggerMinuteOfDay !in 0..(24 * 60 - 1)) return null
        val trigger = LocalDateTime.of(
            LocalDate.now(),
            LocalTime.of(triggerMinuteOfDay / 60, triggerMinuteOfDay % 60),
        )
        val millis = trigger.atZone(Dates.zone).toInstant().toEpochMilli()
        return if (millis <= System.currentTimeMillis()) null else millis
    }

    /**
     * 是否具备精确闹钟能力。
     *
     * USE_EXACT_ALARM（Android 13+）在侧载自用场景安装即授予且不可撤销，
     * 因此绝大多数情况返回 true；仍需处理被厂商收紧的极端情况。
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }
}

/**
 * 提醒触发接收器（M2.2 + M2.3 + M2.4 + M2.5 的合流点）。
 *
 * 收到闹钟后：重新判定 → 未达成则按档位提醒 → 无论是否提醒都落库。
 * 判定放在这里而不是闹钟注册时，因为注册到触发之间用户可能已经完成了任务。
 */
class RemindReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra("task_id", 0L)
        if (taskId <= 0L) return
        val pending = goAsync()
        try {
            runBlocking {
                handle(context.applicationContext, taskId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "remind handling failed for $taskId", e)
        } finally {
            pending.finish()
        }
    }

    private suspend fun handle(context: Context, taskId: Long) {
        val evaluator = ConditionEvaluator(context)
        val date = Dates.todayString()
        val result = evaluator.evaluate(taskId, date) ?: run {
            Log.w(TAG, "task $taskId not found, skip")
            return
        }

        if (result.completed) {
            // 到点时已完成：收掉未完成通知即可。
            // 完成提醒由命中链路（读屏服务）即时投递，这里不补发——
            // 补发会在「早就完成、闹钟才响」时凭空冒出一条完成提示。
            Log.i(TAG, "task $taskId already completed, no remind")
            WeakNotifier.cancel(context, taskId)
            return
        }
        if (result.task.remindLevel == RemindLevel.NONE) {
            // 不提醒档位：只落库，不做任何前台动作
            Log.i(TAG, "task $taskId remind=NONE, recorded only")
            return
        }

        Reminder.dispatch(context, result).let { actual ->
            Reminder.logDelivery(context, result, actualLevel = actual, delivered = true)
        }
    }

    companion object {
        private const val TAG = "LifeBoardRemind"
    }
}

/**
 * 按档位把判定结果分发给弱/强两条提醒通路（M2.3 / M2.4 / M2.5）。
 */
object Reminder {

    /**
     * 投递提醒。
     *
     * 弱提醒投不出去时按原因分流，不能一律升级为响铃：
     * - 用户主动关掉了「打卡提醒」渠道 → 尊重意图，只落库不响铃
     * - 缺通知权限 / 系统总开关关闭 → 配置没做完，升级强提醒兜底
     *   （宁可吵到用户，也不能让"该提醒却没提醒"发生）
     *
     * @return 实际生效的档位
     */
    fun dispatch(context: Context, evaluation: Evaluation): RemindLevel {
        return when (evaluation.task.remindLevel) {
            RemindLevel.NONE -> RemindLevel.NONE

            RemindLevel.WEAK -> {
                if (WeakNotifier.notify(context, evaluation)) {
                    RemindLevel.WEAK
                } else if (WeakNotifier.isChannelBlocked(context)) {
                    Log.i(TAG, "weak channel disabled by user, respect and skip")
                    RemindLevel.NONE
                } else {
                    Log.w(TAG, "weak notify blocked by permission, escalate to strong")
                    StrongRemindService.start(context, evaluation)
                    RemindLevel.STRONG
                }
            }

            RemindLevel.STRONG -> {
                StrongRemindService.start(context, evaluation)
                RemindLevel.STRONG
            }
        }
    }

    /** 记录投递日志，用于排查"到点没响" */
    suspend fun logDelivery(
        context: Context,
        evaluation: Evaluation,
        actualLevel: RemindLevel,
        delivered: Boolean,
    ) {
        runCatching {
            LifeBoardDatabase.get(context).notificationLogDao().insert(
                com.lifeboard.server.db.NotificationLogEntity(
                    taskId = evaluation.task.id,
                    remindLevel = actualLevel.code,
                    triggeredAt = System.currentTimeMillis(),
                    delivered = delivered,
                    note = "value=${evaluation.value}, threshold=${evaluation.task.threshold}",
                ),
            )
        }.onFailure { Log.w(TAG, "log delivery failed", it) }
    }

    private const val TAG = "LifeBoardReminder"
}
