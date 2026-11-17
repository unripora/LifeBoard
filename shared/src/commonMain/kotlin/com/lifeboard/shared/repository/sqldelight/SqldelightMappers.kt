package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.TimeSource
import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.db.App_log
import com.lifeboard.shared.db.App_usage
import com.lifeboard.shared.db.Capture_rule
import com.lifeboard.shared.db.Custom_source
import com.lifeboard.shared.db.Daily_record
import com.lifeboard.shared.db.Dashboard_card
import com.lifeboard.shared.db.Dashboard_tab
import com.lifeboard.shared.db.Metric
import com.lifeboard.shared.db.Notification_log
import com.lifeboard.shared.db.Plugin_raw_record
import com.lifeboard.shared.db.Raw_notification
import com.lifeboard.shared.db.Source_health
import com.lifeboard.shared.db.Streak
import com.lifeboard.shared.db.Task
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.model.AppLogRecord
import com.lifeboard.shared.repository.model.AppUsageRecord
import com.lifeboard.shared.repository.model.AppUsageSum
import com.lifeboard.shared.repository.model.CaptureRuleRecord
import com.lifeboard.shared.repository.model.CustomSourceRecord
import com.lifeboard.shared.repository.model.DashboardCard
import com.lifeboard.shared.repository.model.DashboardTab
import com.lifeboard.shared.repository.model.NotificationLogRecord
import com.lifeboard.shared.repository.model.PluginRawRecord
import com.lifeboard.shared.repository.model.RawNotificationRecord

/**
 * SQLDelight 生成行 ↔ 领域模型的映射。
 *
 * ## 为什么映射写在 shared 而不是各端自带
 *
 * 两端读同一份库，若各写一份映射，「哪一端漏了一个字段」就会变成运行时差异：
 * 表现是同一张任务在手机上设了提醒、在桌面上不响。放在这里之后，
 * 少映射一个字段是编译错误，跑都跑不起来。
 *
 * ## 为什么不是纯函数
 *
 * [SourceRef] 的还原要查 `custom_source`（自建源）或按 key 反解（插件源）。
 * 所以凡是「行 → 领域」涉及数据源的，都是 `suspend` 且要传 [SourceKeys.CustomSourceLookup]。
 * **绝不在这里自行拼 key 或降级成 MANUAL**——历史上「解析失败静默降级」正是
 * 「数字恒为 0 又查不出来」的根源。
 *
 * 「领域 → 行」方向不需要查库，保持普通函数。
 */
object SqldelightMappers {

    // ---- 任务 ----

    suspend fun Task.toModel(lookup: SourceKeys.CustomSourceLookup): TaskConfig {
        val ref = resolveOrThrow(sourceType, lookup, where = "task.sourceType")
        return TaskConfig(
            id = id,
            title = title,
            source = ref,
            metricBucket = MetricSpec.bucketOf(metricBucket) ?: MetricBucket.COUNT,
            threshold = threshold,
            weight = weight,
            remindLevel = RemindLevel.fromCode(remindLevel.toInt()),
            windowStartMinute = windowStartMinute.toInt(),
            windowEndMinute = windowEndMinute.toInt(),
            schedule = ScheduleRule(
                // 老数据没有 windows 列（或写的是空串），回落到旧的单时段字段。
                // 不回落会让升级上来的任务突然「没有生效时段」，判定直接失效
                windows = ScheduleRule.decodeWindows(windows).ifEmpty {
                    listOf(
                        TimeWindow(
                            windowStartMinute.toInt(),
                            windowEndMinute.toInt().coerceAtLeast(windowStartMinute.toInt()),
                        ),
                    )
                },
                startDate = startDate,
                endDate = endDate,
                repeatType = RepeatType.fromName(repeatType),
                weekdays = ScheduleRule.decodeWeekdays(weekdayMask),
                anchorDay = anchorDay.toInt(),
                anchorMonth = anchorMonth.toInt(),
            ),
            ruleId = ruleId,
            enabled = enabled != 0L,
            timeSource = TimeSource.fromName(timeSource),
            doneRemindLevel = RemindLevel.fromCode(doneRemindLevel.toInt()),
            doneToastEnabled = doneToastEnabled != 0L,
            windowOffsetMinutes = windowOffsetMinutes.toInt(),
        )
    }

    /**
     * 任务 → 行。
     *
     * 与 `toModel` 方向相反，不需要查库，所以是普通函数。
     * `sourceType` 直接用 `source.key`，**不做任何降级**：
     * 写进去什么 key，将来就得能按它解析回来。
     */
    fun TaskConfig.toRow(): Task = Task(
        id = id,
        title = title,
        sourceType = source.key,
        metricBucket = metricBucket.name,
        threshold = threshold,
        weight = weight,
        remindLevel = remindLevel.code.toLong(),
        windowStartMinute = windowStartMinute.toLong(),
        windowEndMinute = windowEndMinute.toLong(),
        enabled = if (enabled) 1L else 0L,
        windows = ScheduleRule.encodeWindows(schedule.windows),
        startDate = schedule.startDate,
        endDate = schedule.endDate,
        repeatType = schedule.repeatType.name,
        weekdayMask = ScheduleRule.encodeWeekdays(schedule.weekdays),
        anchorDay = schedule.anchorDay.toLong(),
        anchorMonth = schedule.anchorMonth.toLong(),
        ruleId = ruleId,
        timeSource = timeSource.name,
        doneRemindLevel = doneRemindLevel.code.toLong(),
        doneToastEnabled = if (doneToastEnabled) 1L else 0L,
        windowOffsetMinutes = windowOffsetMinutes.toLong(),
    )

    // ---- 每日记录 ----

    fun Daily_record.toModel(): DailyRecord = DailyRecord(
        taskId = taskId,
        date = date,
        value = value_,
        completed = completed != 0L,
        score = score,
    )

    /** [origin] 必须由调用方传入，它是写入语境（自动判定 / 手动打卡），不是记录的属性 */
    fun DailyRecord.toRow(origin: String): Daily_record = Daily_record(
        taskId = taskId,
        date = date,
        value_ = value,
        completed = if (completed) 1L else 0L,
        score = score,
        origin = origin,
    )

    // ---- 连击 ----

    fun Streak.toModel(): StreakInfo = StreakInfo(
        taskId = taskId,
        currentStreak = currentStreak.toInt(),
        bestStreak = bestStreak.toInt(),
        lastDate = lastDate,
    )

    fun StreakInfo.toRow(): Streak = Streak(
        taskId = taskId,
        currentStreak = currentStreak.toLong(),
        bestStreak = bestStreak.toLong(),
        lastDate = lastDate,
    )

    // ---- 指标 ----

    /**
     * 指标行 → 领域模型。
     *
     * `bucket` 列在库里存着，但 [MetricRecord] 不带这个维度（它由 `source.bucket` 表达），
     * 所以这里只用 `sourceType` 解析源。**不能用 bucket 列反推 SourceRef**：
     * 一个源可以同时有多个量纲，bucket 只说明这一行的口径。
     */
    suspend fun Metric.toModel(lookup: SourceKeys.CustomSourceLookup): MetricRecord {
        val ref = resolveOrThrow(sourceType, lookup, where = "metric.sourceType")
        return MetricRecord(
            source = ref,
            date = date,
            value = value_,
        )
    }

    fun Source_health.toModel(source: SourceRef): SourceHealth = SourceHealth(
        source = source,
        healthy = healthy != 0L,
        lastSuccessAt = lastSuccessAt,
        message = message,
    )

    fun SourceHealth.toRow(source: SourceRef): Source_health = Source_health(
        sourceType = source.key,
        healthy = if (healthy) 1L else 0L,
        lastSuccessAt = lastSuccessAt,
        message = message,
    )

    // ---- 分应用用量 ----

    fun App_usage.toModel(): AppUsageRecord = AppUsageRecord(
        date = date,
        packageName = packageName,
        minutes = minutes,
        label = label,
    )

    fun AppUsageRecord.toRow(): App_usage = App_usage(
        date = date,
        packageName = packageName,
        minutes = minutes,
        label = label,
    )
    /** `sumAppUsageInRange` 的投影：SQL 里已按包名聚合，这里只是换个容器 */
    fun toAppUsageSum(packageName: String, label: String, minutes: Double): AppUsageSum =
        AppUsageSum(
            packageName = packageName,
            label = label,
            minutes = minutes,
        )

    // ---- 布局 ----

    fun Dashboard_tab.toModel(): DashboardTab = DashboardTab(
        tabId = tabId,
        name = name,
        order = order.toInt(),
        isBuiltin = isBuiltin != 0L,
        enabled = enabled != 0L,
    )

    fun DashboardTab.toRow(): Dashboard_tab = Dashboard_tab(
        tabId = tabId,
        name = name,
        order = order.toLong(),
        isBuiltin = if (isBuiltin) 1L else 0L,
        enabled = if (enabled) 1L else 0L,
    )

    fun Dashboard_card.toModel(): DashboardCard = DashboardCard(
        cardId = cardId,
        type = type,
        sourceKey = sourceKey,
        metricKey = metricKey,
        order = order.toInt(),
        tabId = tabId,
        configJson = configJson,
        enabled = enabled != 0L,
    )

    fun DashboardCard.toRow(): Dashboard_card = Dashboard_card(
        cardId = cardId,
        type = type,
        sourceKey = sourceKey,
        metricKey = metricKey,
        order = order.toLong(),
        tabId = tabId,
        configJson = configJson,
        enabled = if (enabled) 1L else 0L,
    )

    // ---- 规则与自建源 ----

    fun Capture_rule.toModel(): CaptureRuleRecord = CaptureRuleRecord(
        id = id,
        packageName = packageName,
        sourceName = sourceName,
        bucketName = bucketName,
        includeKeywords = includeKeywords,
        excludeKeywords = excludeKeywords,
        timeRegex = timeRegex,
        windowStartMinute = windowStartMinute.toInt(),
        windowEndMinute = windowEndMinute.toInt(),
        dailyOnce = dailyOnce != 0L,
        enabled = enabled != 0L,
        createdAt = createdAt,
        windows = windows,
        startDate = startDate,
        endDate = endDate,
        repeatType = repeatType,
        weekdayMask = weekdayMask,
        anchorDay = anchorDay.toInt(),
        anchorMonth = anchorMonth.toInt(),
    )

    fun CaptureRuleRecord.toRow(): Capture_rule = Capture_rule(
        id = id,
        packageName = packageName,
        sourceName = sourceName,
        bucketName = bucketName,
        includeKeywords = includeKeywords,
        excludeKeywords = excludeKeywords,
        timeRegex = timeRegex,
        windowStartMinute = windowStartMinute.toLong(),
        windowEndMinute = windowEndMinute.toLong(),
        dailyOnce = if (dailyOnce) 1L else 0L,
        enabled = if (enabled) 1L else 0L,
        createdAt = createdAt,
        windows = windows,
        startDate = startDate,
        endDate = endDate,
        repeatType = repeatType,
        weekdayMask = weekdayMask,
        anchorDay = anchorDay.toLong(),
        anchorMonth = anchorMonth.toLong(),
    )

    fun Custom_source.toModel(): CustomSourceRecord = CustomSourceRecord(
        id = id,
        displayName = displayName,
        bucket = bucket,
        createdAt = createdAt,
    )

    /**
     * 自建源记录 → 领域引用。
     *
     * 供业务层把 `CaptureRuleRecord.sourceName` 之类只带字符串的地方还原成
     * 可显示的 `SourceRef`。key 由 [SourceRef.customKey] 统一生成，不手拼前缀。
     */
    fun CustomSourceRecord.toRef(): SourceRef.Custom = SourceRef.Custom(
        id = id,
        displayName = displayName,
        bucket = MetricSpec.bucketOf(bucket) ?: MetricBucket.COUNT,
    )

    /** 自建源行 → 领域引用。key 由 [SourceRef.customKey] 统一生成，不手拼 `CUSTOM_` */
    fun Custom_source.toRef(): SourceRef.Custom = SourceRef.Custom(
        id = id,
        displayName = displayName,
        bucket = MetricSpec.bucketOf(bucket) ?: MetricBucket.COUNT,
    )

    fun Raw_notification.toModel(): RawNotificationRecord = RawNotificationRecord(
        id = id,
        packageName = packageName,
        title = title,
        text = text,
        matchedSources = matchedSources,
        postedAt = postedAt,
    )

    fun RawNotificationRecord.toRow(): Raw_notification = Raw_notification(
        id = id,
        packageName = packageName,
        title = title,
        text = text,
        matchedSources = matchedSources,
        postedAt = postedAt,
    )

    // ---- 日志 ----

    fun App_log.toModel(): AppLogRecord = AppLogRecord(
        id = id,
        level = level,
        tag = tag,
        location = location,
        message = message,
        time = time,
    )

    fun AppLogRecord.toRow(): App_log = App_log(
        id = id,
        level = level,
        tag = tag,
        location = location,
        message = message,
        time = time,
    )

    fun Notification_log.toModel(): NotificationLogRecord = NotificationLogRecord(
        id = id,
        taskId = taskId,
        remindLevel = remindLevel.toInt(),
        triggeredAt = triggeredAt,
        delivered = delivered != 0L,
        note = note,
        createdAt = createdAt,
    )

    fun NotificationLogRecord.toRow(): Notification_log = Notification_log(
        id = id,
        taskId = taskId,
        remindLevel = remindLevel.toLong(),
        triggeredAt = triggeredAt,
        delivered = if (delivered) 1L else 0L,
        note = note,
        createdAt = createdAt,
    )

    // ---- 插件 ----

    fun Plugin_raw_record.toModel(): PluginRawRecord = PluginRawRecord(
        id = id,
        pluginId = pluginId,
        date = date,
        metricKey = metricKey,
        timestamp = timestamp,
        value = value_,
        detail = detail,
        seq = seq.toInt(),
    )

    fun PluginRawRecord.toRow(): Plugin_raw_record = Plugin_raw_record(
        id = id,
        pluginId = pluginId,
        date = date,
        metricKey = metricKey,
        timestamp = timestamp,
        value_ = value,
        detail = detail,
        seq = seq.toLong(),
    )

    // ---- 内部 ----

    /**
     * 解析数据源，解析不到就抛。
     *
     * 读路径上抛异常看起来激进，但比静默返回 MANUAL 好：
     * 返回 MANUAL 会让「步数任务」显示成「手动打卡任务」，用户以为是配错了，
     * 实际是数据里存了个坏 key，永远查不出真正原因。
     * 抛出时带上 [where] 与原始 key，排障时一眼能定位。
     */
    private suspend fun resolveOrThrow(
        key: String,
        lookup: SourceKeys.CustomSourceLookup,
        where: String,
    ): SourceRef = when (val r = SourceKeys.resolve(key, lookup)) {
        is SourceKeys.Resolved.Ok -> r.ref
        is SourceKeys.Resolved.Fallback -> throw IllegalStateException("$where 解析失败：${r.reason}")
    }
}
