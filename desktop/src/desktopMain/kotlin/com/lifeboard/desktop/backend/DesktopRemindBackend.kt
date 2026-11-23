package com.lifeboard.desktop.backend

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.db.DatabaseStores
import com.lifeboard.shared.panel.ConditionEvaluator
import com.lifeboard.shared.panel.Evaluation
import com.lifeboard.shared.panel.RemindPlanner
import com.lifeboard.shared.platform.DeliveryLevel
import com.lifeboard.shared.platform.NotificationRequest
import com.lifeboard.shared.platform.Notifier
import com.lifeboard.shared.platform.RemindBackend
import com.lifeboard.shared.platform.RulesBackend
import com.lifeboard.shared.platform.remindLevelName
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.model.NotificationLogRecord
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate

/**
 * 桌面端提醒后端（M4-4.5c）。
 *
 * ## 与 Android 的结构对齐
 *
 * Android 的链路是：`RemindScheduler`（排闹钟）→ `RemindReceiver`（被系统叫醒）
 * → `ConditionEvaluator`（判定）→ `Reminder.dispatch`（按档位分发）→ 落库。
 *
 * 桌面端缺的只有首尾两端：
 *
 * | 环节 | Android | 桌面 |
 * |---|---|---|
 * | 何时响 | `AlarmManager` 精确闹钟 | [RemindPlanner] 算时刻 + `DesktopScheduler` 协程计时 |
 * | 到点判定 | `ConditionEvaluator` | **同一份**（`shared`） |
 * | 送达 | 通知 / 全屏响铃服务 | [Notifier]（托盘气泡） |
 * | 落库 | `logStore` | **同一份**（`shared`） |
 *
 * 所以这个类只是把中间几环串起来，不含任何新的业务规则。
 *
 * ## 强提醒在桌面端怎么降级
 *
 * Android 的强提醒是**全屏响铃页**，桌面端没有等价物也不该有——
 * 抢焦点是最讨人厌的桌面交互之一，用户正在打字时弹全屏窗等于打断他。
 * 所以 `STRONG` 在桌面端落到「托盘气泡 + 标记重要」，
 * 系统的勿扰/专注模式会据此决定是否放行。日志里记的就是降级后的**事实**。
 *
 * @param appScope 应用主 scope，用于把同步的定时器回调转成协程
 */
class DesktopRemindBackend(
    private val stores: DatabaseStores,
    private val scheduler: com.lifeboard.desktop.schedule.DesktopScheduler,
    private val notifier: Notifier,
    private val appScope: CoroutineScope,
    private val clock: Clock = Clock.system(Dates.zone),
) : RemindBackend {

    private val lifecycleMutex = Mutex()
    private val ownedTags = mutableSetOf<String>()
    private val reminderTokens = mutableMapOf<String, Any>()
    private var scheduledDate: LocalDate? = null

    private val tasks get() = stores.taskStore
    private val rules get() = stores.ruleStore

    private val evaluator get() = ConditionEvaluator(tasks, stores.metricStore)

    /**
     * 提醒能力自检。
     *
     * 桌面端只有一项真正会失败：**系统托盘不可用**。
     * 不照抄 Android 那五项（通知权限、精确闹钟、电池优化白名单、无障碍、读屏服务）——
     * 桌面端一个都不存在，逐条列出来只会让用户以为自己的电脑缺了什么。
     *
     * `required = false`：托盘不可用是环境限制（如以无交互方式启动），
     * 不是用户能去设置里打开的开关，界面不该把它画成待办。
     */
    override suspend fun checkPermissions(): List<RemindBackend.PermissionItem> {
        val trayOk = notifier.isPermitted()
        return listOf(
            RemindBackend.PermissionItem(
                key = "tray",
                title = "系统托盘",
                description = if (trayOk) {
                    "可弹出提醒气泡"
                } else {
                    "当前环境没有可用的系统托盘（例如以无交互方式启动），提醒无法送达"
                },
                granted = trayOk,
                required = false,
            ),
        )
    }

    override suspend fun listTasks(): List<TaskConfig> =
        withContext(Dispatchers.IO) { tasks.getEnabled() }

    override suspend fun createTask(task: TaskConfig): Long {
        return lifecycleMutex.withLock {
            val id = withContext(Dispatchers.IO) { tasks.save(task) }
            // 新建也可能发生在跨天恢复后，统一重排，避免只更新新任务却漏掉其他任务。
            rescheduleAllLocked()
            id
        }
    }

    override suspend fun deleteTask(id: Long) {
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) { tasks.deleteById(id) }
            cancelTaskLocked(id)
        }
    }

    /**
     * 重排全部任务。
     *
     * 两个时机必须调它：
     * 1. **应用启动**——桌面端定时器活在进程内，上次退出时全丢了
     * 2. 规则或任务批量变更
     */
    override suspend fun rescheduleAll() = lifecycleMutex.withLock {
        rescheduleAllLocked()
    }

    private suspend fun rescheduleAllLocked() {
        scheduledDate = null
        cancelOwnedTagsLocked()
        val now = clock.millis()
        try {
            withContext(Dispatchers.IO) {
                val all = tasks.getAll()
                val ruleMap = rules.getAllRules().associateBy { it.id }
                all.forEach { task ->
                    registerPlan(
                        RemindPlanner.planTask(task, ruleMap[task.ruleId]?.schedule, nowMillis = now),
                    )
                }
            }
            scheduledDate = dateAt(now)
        } finally {
            // 数据库瞬时失败也保留日期检查，不能连后续恢复机会一起丢掉。
            registerMidnightWatchdog()
        }
    }

    private fun dateAt(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(Dates.zone).toLocalDate()

    /** 午夜和周期检查共用；在锁内复查，避免并发回调重复重排。 */
    internal suspend fun refreshForDateChange(): Boolean = lifecycleMutex.withLock {
        if (dateAt(clock.millis()) == scheduledDate) return@withLock false
        rescheduleAllLocked()
        true
    }

    override suspend fun cancelTask(id: Long) = lifecycleMutex.withLock {
        cancelTaskLocked(id)
    }

    private suspend fun cancelTaskLocked(id: Long) {
        repeat(RemindPlanner.MAX_WINDOWS) { index ->
            cancelOwnedTag("remind:$id:$index")
        }
    }

    private suspend fun cancelOwnedTagsLocked() {
        ownedTags.toList().forEach { scheduler.cancel(it); scheduler.unregister(it) }
        ownedTags.clear()
        reminderTokens.clear()
    }

    private suspend fun cancelOwnedTag(tag: String) {
        scheduler.cancel(tag)
        scheduler.unregister(tag)
        ownedTags.remove(tag)
        reminderTokens.remove(tag)
    }

    /**
     * 立即判定并提醒一次（界面上的「试一下」按钮）。
     *
     * 与到点触发走**同一条**判定与分发路径。手动测试也写记录，
     * 因为用户就是在验证「记录能不能正确落下」。
     */
    override suspend fun evaluateAndRemind(taskId: Long): String? {
        val result = withContext(Dispatchers.IO) {
            evaluator.evaluate(taskId, Dates.todayString())
        } ?: return null

        if (result.completed) {
            // 已达成：收掉可能还挂着的气泡，与到点触发时的处理一致
            notifier.cancel(taskId.toInt())
            return "任务 #$taskId 判定 已达成（值 ${fmt(result.value)} / 阈值 " +
                "${fmt(result.task.threshold)}），无需提醒"
        }

        val level = result.task.remindLevel
        if (level == RemindLevel.NONE) {
            // 不提醒档位：只落库，不做任何前台动作
            logDelivery(result, RemindLevel.NONE)
            return "任务 #$taskId 判定 未达成（值 ${fmt(result.value)} / 阈值 " +
                "${fmt(result.task.threshold)}），档位为「不提醒」，仅记录"
        }

        val actual = deliver(result)
        logDelivery(result, actual)
        return "任务 #$taskId 判定 未达成（值 ${fmt(result.value)} / 阈值 " +
            "${fmt(result.task.threshold)}），实际提醒：${remindLevelName(actual)}"
    }

    // ───────────────────────── 内部 ─────────────────────────

    /**
     * 把 [RemindPlanner.Plan] 登记到定时器。
     *
     * ## 回调里为什么还要重新判定
     *
     * 登记到触发之间用户可能已经完成了任务。沿用登记时的结论会凭空冒出
     * 「你还没做」。Android 的 `RemindReceiver` 收到广播后也是重新判定的，
     * 两端行为一致。
     *
     * ## 为什么是 `appScope.launch` 而不是 `runBlocking`
     *
     * 定时器的回调签名是同步的 `(payload) -> Unit`。在里面 `runBlocking`
     * 会占住调度线程，且判定路径里再调 `scheduleAt` 就有死锁风险。
     * 起一个协程让判定跑在正常的调度上下文中，是唯一安全的做法。
     */
    private suspend fun registerPlan(plan: RemindPlanner.Plan) {
        if (plan.isEmpty) return
        val taskId = plan.taskId

        plan.entries.forEach { entry ->
            val plannedDate = dateAt(entry.triggerAtMillis)
            val token = Any()
            reminderTokens[entry.tag] = token
            scheduler.register(entry.tag) {
                appScope.launch {
                    lifecycleMutex.withLock {
                        // 已经排队的旧回调也必须失效，不能穿过取消或重排继续投递。
                        if (reminderTokens[entry.tag] === token) onTriggeredLocked(taskId, plannedDate)
                    }
                }
            }
            ownedTags += entry.tag
            scheduler.scheduleAt(entry.tag, entry.triggerAtMillis, payload = taskId.toString())
        }
    }

    private suspend fun registerMidnightWatchdog() {
        val tag = MIDNIGHT_TAG
        scheduler.register(tag) {
            appScope.launch { refreshForDateChange() }
        }
        ownedTags += tag
        val now = clock.millis()
        val nextMidnight = dateAt(now)
            .plusDays(1)
            .atStartOfDay(Dates.zone)
            .toInstant()
            .toEpochMilli()
        scheduler.scheduleAt(tag, nextMidnight)

        val driftTag = DATE_WATCHDOG_TAG
        scheduler.register(driftTag) {
            appScope.launch {
                refreshForDateChange()
            }
        }
        ownedTags += driftTag
        scheduler.scheduleRepeating(driftTag, DATE_WATCHDOG_MINUTES)
    }

    /** 到点后的完整处理，与 Android `RemindReceiver.handle` 一一对应 */
    internal suspend fun onTriggered(taskId: Long, plannedDate: LocalDate) = lifecycleMutex.withLock {
        onTriggeredLocked(taskId, plannedDate)
    }

    private suspend fun onTriggeredLocked(taskId: Long, plannedDate: LocalDate) {
        val currentDate = dateAt(clock.millis())
        if (currentDate != plannedDate) {
            if (currentDate != scheduledDate) rescheduleAllLocked()
            return
        }
        val result = withContext(Dispatchers.IO) {
            val task = tasks.getById(taskId)?.takeIf { it.enabled } ?: return@withContext null
            evaluator.evaluate(task, currentDate.toString())
        } ?: return

        if (result.completed) {
            // 已达成：只收掉旧气泡，不重复投递。
            notifier.cancel(taskId.toInt())
            return
        }
        if (result.task.remindLevel == RemindLevel.NONE) {
            logDelivery(result, RemindLevel.NONE)
            return
        }
        val actual = deliver(result)
        logDelivery(result, actual)
    }

    /** 按档位投递，返回实际送达级别 */
    private suspend fun deliver(result: Evaluation): RemindLevel {
        val level = result.task.remindLevel
        if (level == RemindLevel.NONE) return RemindLevel.NONE

        val delivery = notifier.notify(
            NotificationRequest(
                id = result.task.id.toInt(),
                title = result.task.title.ifBlank { "LifeBoard 提醒" },
                body = buildBody(result),
                // 桌面端托盘气泡只有一个通道，不需要分渠道管理
                channelId = CHANNEL_ID,
                // 强提醒在桌面端唯一的差异化手段就是「标记为重要」：
                // 系统的勿扰/专注模式会据此决定是否放行
                important = level == RemindLevel.STRONG,
                // 点击通知时要知道点的是哪个任务。格式与 Android 侧
                // `WeakNotifier` 放在 Intent extra 里的一致，都是任务 id
                payload = result.task.id.toString(),
            ),
        )

        // 托盘不可用时如实回报 NONE。桌面端没有「升级成响铃」这条兜底通路，
        // 日志里要留下「这条没送到」的事实
        return if (delivery == DeliveryLevel.NONE) RemindLevel.NONE else level
    }

    /** 通知正文：说清「差多少」比只说「没完成」有用得多 */
    private fun buildBody(result: Evaluation): String {
        val threshold = result.task.threshold
        return if (threshold > 0) {
            val gap = threshold - result.value
            "当前 ${fmt(result.value)} / 目标 ${fmt(threshold)}，还差 ${fmt(gap)}"
        } else {
            "当前 ${fmt(result.value)}"
        }
    }

    /** 去掉无意义的小数尾巴：12.0 显示成 12，12.5 保留 */
    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    private companion object {
        /**
         * 通知渠道标识。
         *
         * 桌面端没有真正的「渠道」概念（托盘气泡不分渠道），
         * 但契约里 `channelId` 是必填。给一个稳定的常量而不是随任务变化，
         * 是为了将来若换成 Windows 原生 Toast，同一个渠道的历史能聚在一起。
         */
        const val CHANNEL_ID = "lifeboard_remind"
        const val MIDNIGHT_TAG = "remind:midnight-watchdog"
        const val DATE_WATCHDOG_TAG = "remind:date-watchdog"
        const val DATE_WATCHDOG_MINUTES = 1L
    }

    /** 投递日志，用于排查「到点没响」 */
    private suspend fun logDelivery(result: Evaluation, actualLevel: RemindLevel) {
        withContext(Dispatchers.IO) {
            runCatching {
                stores.logStore.insertNotificationLog(
                    NotificationLogRecord(
                        taskId = result.task.id,
                        remindLevel = actualLevel.code,
                        triggeredAt = System.currentTimeMillis(),
                        // 「送达」按实际级别判定：NONE 就是没送到
                        delivered = actualLevel != RemindLevel.NONE,
                        note = "value=${result.value}, threshold=${result.task.threshold}",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
}

/**
 * 桌面端规则后端（M4-4.5c）。
 *
 * ## 三个「平台状态」在桌面端是什么
 *
 * | 问题 | Android 答案 | 桌面答案 |
 * |---|---|---|
 * | 通知监听开没开 | `NotificationListenerService` 授权状态 | **不存在**，恒 false |
 * | 无障碍开没开 | `AccessibilityService` 是否启用 | **不存在**，恒 false |
 * | 今天记了多少 | 读 `metric` 表 | **同一份查询**（`shared`） |
 *
 * 前两项在桌面端不是「还没做」而是「本来就没有」：桌面端不采集屏幕与通知，
 * 数据来自插件与手动录入。所以返回 false 是**正确答案**，
 * 与 `Unsupported*` 那种「调了就抛未实现」有本质区别——
 * 抛异常会让规则页一点就崩，返回 false 只是让提示条不显示。
 */
class DesktopRulesBackend(
    private val stores: DatabaseStores,
    private val remindBackend: DesktopRemindBackend,
) : RulesBackend {

    override suspend fun isNotificationListenerEnabled(): Boolean = false

    override suspend fun isCaptureServiceEnabled(): Boolean = false

    override suspend fun todayCount(sourceKey: String, bucket: String): Double =
        withContext(Dispatchers.IO) {
            // 与 Android 上是同一个口径：按 sourceKey + 桶名 + 今天
            stores.metricStore.getMetric(sourceKey, Dates.todayString(), bucket)?.value ?: 0.0
        }

    override suspend fun clearTodayCount(sourceKey: String, bucket: String) {
        withContext(Dispatchers.IO) {
            // 先把 sourceKey 还原成 SourceRef（自建源要回查名称与量纲），
            // 这段解析由 shared 的 SourceKeys 负责，不在这里重写一遍
            val lookup = SourceKeys.CustomSourceLookup { id ->
                stores.ruleStore.getCustomSource(id)?.toRef()
            }
            val ref: SourceRef = when (val resolved = SourceKeys.resolve(sourceKey, lookup)) {
                is SourceKeys.Resolved.Ok -> resolved.ref
                is SourceKeys.Resolved.Fallback -> resolved.ref
            }
            // 清零的做法是**写一条 0 值记录**而不是 DELETE，与 Android 端一致。
            // 两者语义不同：删掉后「今天这条源从没被读过」，
            // 写 0 表示「今天读过，结果是 0」——后者才是用户点「清零」想要的
            stores.metricStore.saveMetric(
                MetricRecord(
                    source = SourceRef.Builtin(
                        key = ref.key,
                        displayName = ref.displayName,
                        bucket = MetricSpec.bucketOf(bucket) ?: MetricBucket.COUNT,
                    ),
                    date = Dates.todayString(),
                    value = 0.0,
                ),
            )
        }
    }

    /**
     * 规则变更后让采集端重新订阅。
     *
     * 桌面端没有常驻采集端（不读屏、不听通知），所以**什么都不用做**。
     * 留着空实现而不是抛异常：规则页保存后会无条件调它，
     * 抛异常会让「保存规则」这个动作整体失败。
     */
    override suspend fun notifyRulesChanged() = Unit

    /** 规则变更后重排提醒——这条在桌面端必须真做 */
    override suspend fun rescheduleReminders() {
        remindBackend.rescheduleAll()
    }

    /**
     * 可被规则引用的数据源：内置源 + 用户自建源。
     *
     * 插件源在安装后写入 `sources` 表，与自建源走同一个出口，
     * 所以这里只取自定义源再交给 `SourceKeys.selectable` 拼接——
     * 判定顺序（内置在前）由 shared 决定，两端一致。
     */
    override suspend fun selectableSources(): List<SourceRef> = withContext(Dispatchers.IO) {
        SourceKeys.selectable(stores.ruleStore.getCustomSources().map { it.toRef() })
    }

    override suspend fun referenceCount(sourceKey: String): Int = withContext(Dispatchers.IO) {
        stores.ruleStore.getAllRules().count { it.sourceName == sourceKey }
    }
}
