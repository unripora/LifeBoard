package com.lifeboard.shared.repository

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.shared.repository.model.AppUsageRecord
import com.lifeboard.shared.repository.model.AppUsageSum
import kotlinx.coroutines.flow.Flow

/**
 * 指标、分应用用量与数据源健康度的**数据访问**契约。
 *
 * 三者放在一起的原因：健康度描述的就是「这些指标是谁产出的、最近一次成没成功」，
 * 面板上它们总是同时被读。
 *
 * 这一层只做存取与必要的最小投影，不做补零、不做缺日填充——
 * 那是图表层的决定，把它下沉到数据层会让「今天还没采集」和「今天真的是 0」
 * 在源头就被抹成同一个值。
 */
interface MetricStore {

    suspend fun getMetricsByDate(date: String): List<MetricRecord>

    suspend fun getMetric(sourceKey: String, date: String, bucket: String): MetricRecord?

    /** 闭区间内的全部指标，按日期升序 */
    suspend fun getMetricsInRange(from: String, to: String): List<MetricRecord>

    /**
     * 某个源某个量纲在闭区间内**有数据的**全部行，按日期升序。
     *
     * 图表一次取整窗数据，而不是逐天调用：30 天 × 3 条序列就是 90 次往返，
     * 这里只需要 3 次。缺失的日期不会出现在结果里。
     */
    suspend fun getSourceRange(
        sourceKey: String,
        bucket: String,
        from: String,
        to: String,
    ): List<MetricRecord>

    /**
     * 某个源某个量纲在 [from]..[today] 里**有数据的**最晚一天，没有返回 null。
     *
     * 指标卡要往前找「最近一次」的值——今天还没采集到与今天真的是 0 是两回事。
     * 按量纲而不是整个源过滤是必要的：一个源同时有步数与睡眠时，
     * 读「睡眠」卡不该拿到只有步数的日期。
     */
    suspend fun latestDateWithBucket(
        sourceKey: String,
        bucket: String,
        from: String,
        today: String,
    ): String?

    suspend fun saveMetric(metric: MetricRecord)

    /** 删除某个数据源的全部指标，返回删除行数。源被移除后清理残留 */
    suspend fun deleteMetricsBySource(sourceKey: String): Int

    fun observeSourceHealth(): Flow<List<SourceHealth>>

    suspend fun getAllSourceHealth(): List<SourceHealth>

    suspend fun saveSourceHealth(health: SourceHealth)

    /** 删除某个源的健康度记录，与 [deleteMetricsBySource] 配套使用 */
    suspend fun deleteSourceHealth(sourceKey: String): Int

    /** 观察某天的分应用用量，按分钟降序 */
    fun observeAppUsageByDate(date: String): Flow<List<AppUsageRecord>>

    suspend fun getAppUsageByDate(date: String): List<AppUsageRecord>

    /** 同一天同一应用覆盖写入，重复采集不产生重复行 */
    suspend fun saveAppUsage(rows: List<AppUsageRecord>)

    suspend fun deleteAppUsageByDate(date: String)

    /** 只保留 [keepFrom] 及之后的记录，清理更早的分应用数据 */
    suspend fun trimAppUsageBefore(keepFrom: String)

    /** 闭区间内按应用汇总的用量，按时长降序 */
    suspend fun sumAppUsageInRange(from: String, to: String): List<AppUsageSum>
}
