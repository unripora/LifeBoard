package com.lifeboard.server.remind

import android.content.Context
import com.lifeboard.server.platform.AndroidPanelScope
import com.lifeboard.shared.panel.ConditionEvaluator as SharedConditionEvaluator
import com.lifeboard.shared.panel.Evaluation

/**
 * 条件判定器的 Android 入口。
 *
 * 判定逻辑本身（读指标 → 比阈值 → 写记录 → 维护连击）已经整体迁到 `:shared`（M3.2）。
 * 它从头到尾没有平台相关的东西，只是构造函数要一个 `Context` 取默认 Store，
 * 却因此没法在桌面端复用——而判定口径一旦分叉，同一个任务在手机和电脑上
 * 会得出不同的达成结论，这正是本次重构要消除的问题。
 *
 * 这里只剩一层壳：把 `Context` 换成 [AndroidPanelScope] 再转调。
 * 提醒调度器、无障碍服务、提醒后端三处都还按 `ConditionEvaluator(context)` 调用，
 * 保持签名不变可以让它们不用同时改；3.3 注入化以后再收掉这层。
 */
class ConditionEvaluator(context: Context) {

    private val delegate = SharedConditionEvaluator(
        AndroidPanelScope(context).taskStore,
        AndroidPanelScope(context).metricStore,
    )

    /** 判定单个任务在指定日期的达成状态 */
    suspend fun evaluate(taskId: Long, date: String, persist: Boolean = true): Evaluation? =
        delegate.evaluate(taskId, date, persist)

    suspend fun evaluate(
        task: com.lifeboard.core.model.TaskConfig,
        date: String,
        persist: Boolean = true,
    ): Evaluation = delegate.evaluate(task, date, persist)

    /** 批量判定当日所有启用任务 */
    suspend fun evaluateAll(
        date: String = com.lifeboard.core.util.Dates.todayString(),
        persist: Boolean = true,
    ): List<Evaluation> = delegate.evaluateAll(date, persist)

    /** 读取某任务在指定日期的判定结果（不重新计算） */
    suspend fun loadRecord(taskId: Long, date: String) = delegate.loadRecord(taskId, date)

    /** 只按已有记录维护连击，不重算记录本身 */
    suspend fun refreshStreakFromRecord(taskId: Long, date: String) =
        delegate.refreshStreakFromRecord(taskId, date)
}

/**
 * 一次判定结果的转发。
 *
 * 真类型在 `:shared` 的 [com.lifeboard.shared.panel.Evaluation]，这里只是让
 * 引用 `com.lifeboard.server.remind.Evaluation` 的调用方（提醒后端、无障碍服务）
 * 不用改 import。
 */
typealias Evaluation = com.lifeboard.shared.panel.Evaluation
