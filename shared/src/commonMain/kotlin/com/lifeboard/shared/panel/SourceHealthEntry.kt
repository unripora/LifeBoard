package com.lifeboard.shared.panel

import com.lifeboard.core.model.SourceHealth
import com.lifeboard.plugin.api.SourceRef

/**
 * 数据源健康度汇总的**数据模型**（M4.8）。
 *
 * 每个源有三种状态，界面用不同颜色区分：
 * 1. **可用**：权限齐全
 * 2. **缺权限**：权限没开，数据一定为 0，需要引导用户去设置
 * 3. **二期未接入**：代码还没写，明确告知而不是让它默默为 0
 *
 * 第 3 种最容易被忽略——用户建了小米健康的任务，看着永远是 0，
 * 会以为是采集出错，来回折腾。所以这里显式标出来。
 *
 * ## 为什么只有模型住在这里
 *
 * 「怎么判断有没有权限」深度依赖平台：Android 要问通知监听器与使用情况访问权，
 * 桌面端根本没有这些权限。而「判断结果长什么样、界面怎么展示」两端是一致的。
 * 所以探测留在各平台的实现里，模型与展示口径放这里。
 * 桌面端提供一个把全部源报成 [State.NOT_IMPLEMENTED] 或 [State.OK] 的实现即可。
 */
data class SourceHealthEntry(
    val source: SourceRef,
    val state: SourceHealthState,
    val message: String,
    /** 点击后可跳转的设置页；null 表示无可用跳转 */
    val actionKey: String?,
    /** 自建源/插件源没有对应的 SourceType 枚举，这里存它的展示名 */
    val customName: String = "",
) {
    val healthy: Boolean get() = state == SourceHealthState.OK

    val displayName: String
        get() = customName.ifBlank { source.displayName }

    /** 写进 `source_health.sourceType` 的键 */
    val key: String get() = source.key
}

/**
 * 健康度的展示态。
 *
 * 比裸 boolean 多了「未接入」与「只靠规则」两档：
 * 前者要告诉用户「不是你的问题，功能还没做」，
 * 后者要告诉用户「这里没有授权开关可点，数据靠规则命中」。
 */
enum class SourceHealthState {
    /** 权限齐全，可采集 */
    OK,

    /** 权限没开，数据一定为 0 */
    NO_PERMISSION,

    /** 功能尚未接入 */
    NOT_IMPLEMENTED,

    /**
     * 只能由采集规则命中写入，没有主动采集器。
     *
     * 自建源属于这一类：给它标「缺权限」会让用户去找一个根本不存在的授权开关。
     */
    RULE_ONLY,
}

/**
 * 把一组健康度条目折成写库用的 [SourceHealth]。
 *
 * 抽出来是因为「上次成功时间要不要刷新」这条规则很容易写错：
 * 本次不健康时**必须沿用旧值**，否则每次检查都会把「最后一次成功是什么时候」
 * 抹成 0，而这个信息正是排障时唯一有用的东西。
 *
 * @param previous 上一次写库的结果，按 key 索引
 * @param now 本次检查的时刻
 */
fun List<SourceHealthEntry>.toSourceHealth(
    previous: Map<String, SourceHealth>,
    now: Long,
): List<SourceHealth> = map { entry ->
    SourceHealth(
        source = entry.source,
        healthy = entry.healthy,
        lastSuccessAt = if (entry.healthy) now else (previous[entry.key]?.lastSuccessAt ?: 0L),
        message = entry.message,
    )
}

/** 健康度条目的展示文案。两端一致，避免同一个状态在两处写出不同说法 */
fun SourceHealthEntry.statusLabel(): String = when (state) {
    SourceHealthState.OK -> "正常"
    SourceHealthState.NO_PERMISSION -> "缺权限"
    SourceHealthState.NOT_IMPLEMENTED -> "未接入"
    SourceHealthState.RULE_ONLY -> "规则触发"
}
