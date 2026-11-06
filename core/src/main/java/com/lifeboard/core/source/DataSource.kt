package com.lifeboard.core.source

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.model.SourceRef
import java.time.LocalDate

/**
 * 数据源采集器统一接口。
 *
 * 实现约束（源自 I 阶段结论）：
 * - 需要读取目标应用私有数据的实现，必须运行在**目标进程内**，通过 RemotePreferences
 *   或 ContentProvider 回传，不得在模块进程内按文件路径直接打开其他应用的数据库。
 * - 采集失败必须返回 [SourceHealth.healthy] = false，不得抛出异常中断其他数据源。
 */
interface DataSource {
    /**
     * 该采集器对应的数据源标识。
     *
     * 用 [SourceRef] 而不是 `SourceType` 枚举：插件源是运行时才装进来的，
     * 标识不可能追加到编译期枚举里。落库统一用 [SourceRef.key]。
     */
    val source: SourceRef

    /** 该采集器是否需要注入目标应用进程 */
    val requiresInjection: Boolean

    /**
     * 采集指定日期的指标数据。
     *
     * @param date 日期，格式 yyyy-MM-dd
     * @return 采集到的指标列表，可能为空（表示当日无数据）
     */
    suspend fun collect(date: String): List<MetricRecord>

    /**
     * 查询当前采集健康度，用于面板展示断源提示。
     */
    suspend fun health(): SourceHealth
}

/**
 * 屏幕时长的专属能力。
 *
 * 从 [DataSource] 里拆出来，是因为「授权检查」与「分应用明细」只有屏幕时长有，
 * 不属于通用采集契约：塞进 [DataSource] 会让每个源都被迫实现两个空方法。
 *
 * 聚合 Worker 只依赖本接口，因此不需要知道具体采集器的类名，
 * 换实现（如将来做成插件）时 Worker 一行都不用改。
 */
interface ScreenTimeSource : DataSource {

    /** 是否已授予「使用情况访问权」 */
    fun hasPermission(): Boolean

    /** 指定日期各应用的前台时长（分钟），按用量降序 */
    fun queryPerAppMinutes(day: LocalDate): List<Pair<String, Double>>
}
