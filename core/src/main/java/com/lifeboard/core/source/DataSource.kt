package com.lifeboard.core.source

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth

/**
 * 数据源采集器统一接口。
 *
 * 实现约束（源自 I 阶段结论）：
 * - 需要读取目标应用私有数据的实现，必须运行在**目标进程内**，通过 RemotePreferences
 *   或 ContentProvider 回传，不得在模块进程内按文件路径直接打开其他应用的数据库。
 * - 采集失败必须返回 [SourceHealth.healthy] = false，不得抛出异常中断其他数据源。
 */
interface DataSource {
    /** 该采集器对应的数据源标识 */
    val sourceType: com.lifeboard.core.model.SourceType

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
