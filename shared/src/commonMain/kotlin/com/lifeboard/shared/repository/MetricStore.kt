package com.lifeboard.shared.repository

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.shared.repository.model.AppUsageRecord
import com.lifeboard.shared.repository.model.AppUsageSum
import com.lifeboard.shared.repository.model.MetricMeta
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

    /**
     * 给某个源某天的值**加上** [delta]（没有该行则新建）。
     *
     * 事件型指标（「今天完成了几轮对话」）必须走累加：同一天多次上报是常态，
     * 覆盖式写入会让最后一次覆盖前面全部计数，表现为「明明完成了很多轮，
     * 图上只有 1」。
     *
     * 累加在 SQL 层完成，不是读-改-写：上报可能来自插件的后台线程，
     * 读改写之间会被另一次上报插进来而丢更新。
     */
    suspend fun incrementMetric(sourceKey: String, date: String, bucket: String, delta: Double)

    /** 删除某个数据源的全部指标，返回删除行数。源被移除后清理残留 */
    suspend fun deleteMetricsBySource(sourceKey: String): Int

    /** Delete every plugin metric whose source key starts with the exact `PLUGIN_{pluginId}:` prefix. */
    suspend fun deleteMetricsByPlugin(pluginId: String): Int

    // ---- 插件指标元数据 ----

    /**
     * 取某个源的元数据，没有返回 null。
     *
     * 只有插件源会有元数据：内置源的展示名与量纲编译期就定死了，
     * 由 `SourceKeys` 直接给出，不需要查库。
     */
    suspend fun getMetricMeta(sourceType: String): MetricMeta?

    /**
     * 批量取元数据，一次查库而不是逐条查。
     *
     * 面板每帧都可能重建卡片列表，逐条查会把主线程拖慢到肉眼可见。
     * 不存在的键不会出现在返回值里。
     */
    suspend fun getMetricMetas(sourceTypes: Collection<String>): Map<String, MetricMeta>

    /** 写入或覆盖一条元数据。插件重装、指标改名都靠 REPLACE 覆盖 */
    suspend fun saveMetricMeta(meta: MetricMeta)

    /**
     * 清掉某个插件的全部元数据，返回删除行数。
     *
     * **只删元数据，不动 `metric` 里的历史值**：用户可能只是想重装插件，
     * 顺手清掉历史数据是不可接受的副作用。
     */
    suspend fun deleteMetricMetaByPlugin(pluginId: String): Int

    fun observeSourceHealth(): Flow<List<SourceHealth>>

    suspend fun getAllSourceHealth(): List<SourceHealth>

    suspend fun saveSourceHealth(health: SourceHealth)

    /** Delete every plugin health row whose source key starts with the exact `PLUGIN_{pluginId}:` prefix. */
    suspend fun deleteSourceHealthByPlugin(pluginId: String): Int

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
