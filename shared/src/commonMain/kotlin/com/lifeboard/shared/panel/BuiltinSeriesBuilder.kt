package com.lifeboard.shared.panel

import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.TaskConfig

/**
 * 从每日记录重建「某条任务的连击走势」。
 *
 * ## 为什么不直接用 `streak` 表
 *
 * `streak` 只存**当前**的连击与最佳值，是一个快照。把它当成历史曲线画出来，
 * 得到的是「今天连击 5 天」被重复画 30 次的一条水平线——看起来像数据，
 * 实际没有任何历史信息。所以这里按日记录重新推一遍。
 *
 * ## 三种日子要分开
 *
 * - **有记录且达成**：连击 +1，是真实的点
 * - **有记录但未达成**：连击归零，这是「断了」，0 是有效数据
 * - **完全没有记录**：不生成点，但连击清零
 *
 * 第三种为什么既不出点又要清零：
 *
 * 不出点，是因为任务只有在被判定过之后才留下记录，而判定只发生在
 * 「当天启用且被触发」的场景；任务当时还没建、被停用、或判定没跑过，
 * 都是「没有记录」而不是「没完成」。给它写 0 等于替用户编一句
 * 「我那几天都没做到」。
 *
 * 清零，是因为连击的定义要求**逐日连续**。中间空一天，后续的达成
 * 就不可能和前面接成一串，跨过缺口继续累加会把连击凭空拉长。
 *
 * 所以图上表现为：缺口处断开，缺口之后从 1 重新起算。
 *
 * 需要注意：窗口起点之前的历史无法得知，所以曲线开头会从 0 起算，
 * 这是「窗口内可见的连击」，不是「用户历史最长连击」。
 *
 * @param taskId 指定任务；为 null 或找不到时返回空序列
 */
fun readStreakSeries(
    records: List<DailyRecord>,
    taskId: Long?,
    from: String,
    to: String,
    tasks: List<TaskConfig>,
): ChartSeries {
    if (taskId == null) {
        return ChartSeries(
            label = "连击走势",
            unit = "天",
            points = emptyList(),
            status = SeriesStatus.SourceUnavailable("未指定任务"),
        )
    }
    val task = tasks.firstOrNull { it.id == taskId }
        ?: return ChartSeries(
            label = "连击走势",
            unit = "天",
            points = emptyList(),
            status = SeriesStatus.SourceUnavailable("任务已不存在"),
        )

    val byDate = records.filter { it.taskId == taskId }.associateBy { it.date }

    val points = mutableListOf<SeriesPoint>()
    var current = 0
    var cursor = java.time.LocalDate.parse(from)
    val end = java.time.LocalDate.parse(to)
    while (!cursor.isAfter(end)) {
        val date = cursor.toString()
        val record = byDate[date]
        when {
            // 没判定过：不出点，同时清零。下面是「未完成」而不是「连续」——
            // 连击要求逐日连续，中间空一天在数学上就已经断了，不能跨过去接着数
            record == null -> current = 0
            record.completed -> {
                current += 1
                points += SeriesPoint(date, current.toDouble())
            }
            else -> {
                current = 0
                points += SeriesPoint(date, 0.0)
            }
        }
        cursor = cursor.plusDays(1)
    }

    return ChartSeries(
        label = "${task.title} · 连击",
        unit = "天",
        points = points,
        status = if (points.isEmpty()) SeriesStatus.NoData else SeriesStatus.Ok,
    )
}

/**
 * 某条规则在每日记录里的完成率。
 *
 * 只统计**该任务当天有记录**的日子：没有记录说明那天不适用（停用、不在重复范围内），
 * 把它算进分母会把完成率无端拉低。
 */
fun readRuleCompletionSeries(
    records: List<DailyRecord>,
    taskId: Long?,
    tasks: List<TaskConfig>,
): ChartSeries {
    if (taskId == null) {
        return ChartSeries(
            label = "规则完成率",
            unit = "%",
            points = emptyList(),
            status = SeriesStatus.SourceUnavailable("未指定任务"),
        )
    }
    val task = tasks.firstOrNull { it.id == taskId }
        ?: return ChartSeries(
            label = "规则完成率",
            unit = "%",
            points = emptyList(),
            status = SeriesStatus.SourceUnavailable("任务已不存在"),
        )

    // 每条任务每天只有一条记录（主键 taskId+date），所以这里每天要么 100 要么 0。
    // 用记录自身表达，而不是重新跑一遍判定：判定依赖当时的采集值，现在重算
    // 会得到与历史不一致的结果。
    val points = records
        .filter { it.taskId == taskId }
        .sortedBy { it.date }
        .map { SeriesPoint(it.date, if (it.completed) 100.0 else 0.0) }

    return ChartSeries(
        label = "${task.title} · 完成率",
        unit = "%",
        points = points,
        status = if (points.isEmpty()) SeriesStatus.NoData else SeriesStatus.Ok,
    )
}

/**
 * 一条每日记录对应的任务权重。
 *
 * 记录本身不存权重，只能回查任务表。任务已被删除时按 0 处理：
 * 它的记录还留在库里，但不该继续影响今天的完成率分母。
 */
fun DailyRecord.weightOf(tasks: List<TaskConfig>): Double =
    tasks.firstOrNull { it.id == taskId }?.weight ?: 0.0
