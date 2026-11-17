package com.lifeboard.shared.panel

import com.lifeboard.core.model.TaskConfig
import com.lifeboard.shared.dashboard.BuiltinSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 一条时间序列里某一天的值。
 *
 * @property date `yyyy-MM-dd`
 * @property value 数值
 */
data class SeriesPoint(val date: String, val value: Double)

/**
 * 一条可画的时间序列。
 *
 * ## 为什么缺失日期不补 0
 *
 * 「那天没采到」和「那天是 0 步」是两件完全不同的事。补 0 会让折线在断源期间
 * 直接贴地走平，用户看到的是「我那几天没走路」，而事实是「那几天没数据」。
 * 所以 [points] 只包含真实存在的日期，断档由渲染层按「断开」处理。
 *
 * @property label 图例名
 * @property unit 单位，不同单位不能共用一根 Y 轴
 * @property points 有数据的日期，按日期升序
 * @property status 整条序列的可用性
 */
data class ChartSeries(
    val label: String,
    val unit: String,
    val points: List<SeriesPoint>,
    val status: SeriesStatus,
)

/**
 * 序列的可用性。
 *
 * 分开表达是为了让界面说对话：「源不见了」要引导用户改配置，
 * 「窗口内没数据」只是让他等等或换个时间窗。
 */
sealed interface SeriesStatus {
    /** 正常，可能部分日期缺失 */
    data object Ok : SeriesStatus

    /** 源或指标已不存在（插件卸载、自建源删除、指标被新版移除） */
    data class SourceUnavailable(val reason: String) : SeriesStatus

    /** 源在，但窗口内没有任何数据 */
    data object NoData : SeriesStatus

    /**
     * 数据存在但含义不完整。
     *
     * 目前唯一的用途是手动打卡：迁移前的记录来源不可考，
     * 统计出来的「手动打卡次数」会偏低，必须如实说明而不是当成准确值。
     */
    data class Partial(val reason: String) : SeriesStatus
}

/**
 * 把天数换成闭区间日期。
 *
 * 用 `LocalDate.minusDays` 而不是自己拼日期字符串：跨月、闰年都会错。
 *
 * @param days 窗口天数，含今天
 * @param today 今天，`yyyy-MM-dd`
 */
fun windowRange(today: String, days: Int): Pair<String, String> {
    val end = LocalDate.parse(today)
    val start = end.minusDays(days.toLong() - 1)
    return start.toString() to end.toString()
}

/**
 * 自定义图表的数据入口。
 *
 * ## 与 [MetricQueryService] 的分工
 *
 * 后者读「某一天的单个值」，是给指标卡用的。图表要的是「一段时间的多个值」，
 * 而且要一次拿一批——逐天逐指标调用会变成 N×M 次数据库往返。
 * 所以这里按序列一次性取整窗数据，在内存里按日期分组。
 */
class ChartQueryService(private val scope: PanelScope) {

    private val metrics = MetricQueryService(scope)
    private val resolver = SourceResolver(scope)

    /**
     * 按配置读一条序列，自动区分普通采集序列与内置业务序列。
     *
     * 这是图表卡唯一该调的入口：调用方只把配置扔进来，不需要知道这条序列
     * 来自 `metric` 表还是从任务记录里算出来的。判据只有 [BuiltinSeries.fromMetricKey] 一处。
     *
     * @param sourceKey 数据源键
     * @param metricKey 指标键；命中内置序列的固定键时走内置路径
     * @param taskId 单条任务类内置序列要看的任务
     */
    suspend fun readConfiguredSeries(
        sourceKey: String,
        metricKey: String?,
        label: String,
        unit: String,
        days: Int,
        today: String,
        taskId: Long? = null,
    ): ChartSeries {
        val builtin = BuiltinSeries.fromMetricKey(metricKey)
            ?: return readSeries(sourceKey, metricKey, unit, label, days, today)
        return readBuiltinSeries(builtin, days, today, taskId).let { series ->
            // 图例名以配置为准：同一条「单条规则完成率」可能配了两条不同任务，
            // 全用内置名会让两条线在图上分不清
            if (label.isBlank()) series else series.copy(label = label)
        }
    }

    /**
     * 读一条指标序列。
     *
     * @param sourceKey 数据源键，与指标卡同一个含义
     * @param metricKey 指标键；内置源可留空
     * @param unit 单位，由调用方按数据源声明传入
     * @param label 图例名
     * @param days 时间窗天数
     * @param today `yyyy-MM-dd`
     */
    suspend fun readSeries(
        sourceKey: String,
        metricKey: String?,
        unit: String,
        label: String,
        days: Int,
        today: String,
    ): ChartSeries = withContext(Dispatchers.IO) {
        val (from, to) = windowRange(today, days)
        try {
            val reading = resolver.resolveReading(sourceKey, metricKey)
            val rows = scope.metricStore.getSourceRange(sourceKey, reading.bucket.name, from, to)
            if (rows.isEmpty()) {
                return@withContext ChartSeries(label, unit, emptyList(), SeriesStatus.NoData)
            }
            ChartSeries(
                label = label,
                unit = unit,
                points = rows.map { SeriesPoint(it.date, it.value) },
                status = SeriesStatus.Ok,
            )
        } catch (e: SourceUnresolvable) {
            ChartSeries(
                label = label,
                unit = unit,
                points = emptyList(),
                status = SeriesStatus.SourceUnavailable("数据源或指标已不存在"),
            )
        }
    }

    /**
     * 读一条内置业务序列。
     *
     * 内置序列不是采集来的，而是从已有的任务与记录里算出来的。
     * 这条路径**只读**：不写 `metric` 表，否则趋势图和图表会互相污染。
     */
    suspend fun readBuiltinSeries(
        builtin: BuiltinSeries,
        days: Int,
        today: String,
        taskId: Long? = null,
    ): ChartSeries = withContext(Dispatchers.IO) {
        val (from, to) = windowRange(today, days)
        val tasks = scope.taskStore
        val records = tasks.getRecordsInRange(from, to)
        val allTasks = tasks.getAll()

        when (builtin) {
            BuiltinSeries.DAILY_SCORE -> {
                val byDate = records.groupBy { it.date }
                seriesOf(
                    label = "每日得分",
                    unit = "分",
                    values = byDate.mapValues { (_, rows) -> rows.sumOf { it.score } },
                )
            }

            BuiltinSeries.COMPLETION_RATE -> {
                // 分母只算「当天应参与计分」的任务：用该日有记录的任务权重之和。
                // 没有任何记录的日子直接缺失，不写成 0%——0% 意味着「一条没完成」，
                // 而实际是「那天没有可计分的任务」。
                val byDate = records.groupBy { it.date }
                val values = byDate.mapNotNull { (date, rows) ->
                    val totalWeight = rows.sumOf { it.weightOf(allTasks) }
                    if (totalWeight <= 0.0) {
                        null
                    } else {
                        val done = rows.filter { it.completed }.sumOf { it.weightOf(allTasks) }
                        date to (done / totalWeight * 100.0)
                    }
                }.toMap()
                seriesOf("每日完成率", "%", values)
            }

            BuiltinSeries.DONE_TASK_COUNT -> {
                val byDate = records.groupBy { it.date }
                seriesOf(
                    label = "完成待办数",
                    unit = "条",
                    values = byDate.mapValues { (_, rows) -> rows.count { it.completed }.toDouble() },
                )
            }

            BuiltinSeries.STREAK -> readStreakSeries(records, taskId, from, to, allTasks)

            BuiltinSeries.RULE_COMPLETION -> readRuleCompletionSeries(records, taskId, allTasks)

            BuiltinSeries.MANUAL_MARK_COUNT -> {
                // origin 不在 DailyRecord 里（它是写入语境，不是记录属性），
                // 所以单独向 Store 要一份「日期 → 来源 → 条数」的分布
                val counts = tasks.countRecordsByOrigin(from, to)
                val series = seriesOf(
                    label = "手动打卡次数",
                    unit = "次",
                    values = counts.mapValues { (_, byOrigin) ->
                        (byOrigin[RECORD_ORIGIN_MANUAL] ?: 0).toDouble()
                    },
                )
                // 迁移前的记录来源不可考，统计会偏低。必须如实说明，
                // 而不是让用户以为「那段时间一次都没手动打卡」。
                // 只要窗口里出现过 UNKNOWN 就标 Partial——哪怕当前值为 0，
                // 「0 次」也依然是不可信的结论
                if (counts.values.any { (it[RECORD_ORIGIN_UNKNOWN] ?: 0) > 0 }) {
                    series.copy(
                        status = SeriesStatus.Partial("部分历史记录来源不可考，实际次数可能更多"),
                    )
                } else {
                    series
                }
            }
        }
    }

    /**
     * 读一次分类占比。
     *
     * ## 为什么用「Top N + 其他」而不是全部列出
     *
     * 手机上装几十上百个应用，全画进饼图会变成一堆细到看不见的扇区。
     * 只保留前 N 项、其余合成「其他」，既看得出主要构成，又不至于色块糊成一片。
     *
     * @param sourceKey 分类源，目前只支持 [CategorySource.APP_USAGE]
     * @param topN 保留前多少项，其余归入「其他」
     * @param days 时间窗天数
     */
    suspend fun readCategoryBreakdown(
        sourceKey: String,
        topN: Int,
        days: Int,
        today: String,
    ): CategoryBreakdown = withContext(Dispatchers.IO) {
        if (sourceKey != CategorySource.APP_USAGE) {
            return@withContext CategoryBreakdown(
                slices = emptyList(),
                unit = "分钟",
                status = SeriesStatus.SourceUnavailable("不支持的分类数据源"),
            )
        }

        val (from, to) = windowRange(today, days)
        val rows = scope.metricStore.sumAppUsageInRange(from, to)

        // 负值或全零都不该画成占比：没有可分配的整体，画出来是误导
        val slices = buildCategorySlices(
            rows.map { Triple(it.packageName, it.label, it.minutes) },
            topN,
        )
        if (slices.isEmpty()) {
            return@withContext CategoryBreakdown(
                slices = emptyList(),
                unit = "分钟",
                status = SeriesStatus.NoData,
            )
        }

        CategoryBreakdown(slices = slices, unit = "分钟", status = SeriesStatus.Ok)
    }

    /** 记录来源取值。与 `daily_record.origin` 列的写入方约定一致 */
    private companion object {
        const val RECORD_ORIGIN_MANUAL = "MANUAL"
        const val RECORD_ORIGIN_UNKNOWN = "UNKNOWN"
    }
}

/**
 * 分类占比里的一个扇区。
 *
 * @property key 稳定标识（包名等），用于配色与去重
 * @property label 展示名
 * @property value 数值，单位与 [CategoryBreakdown.unit] 一致
 */
data class CategorySlice(val key: String, val label: String, val value: Double)

/**
 * 一次分类占比查询的结果。
 *
 * @property slices 各分类的数值，按值降序，「其他」排在最后
 * @property unit 单位。所有 slice 必须是同一单位，否则占比没有意义
 * @property status 可用性
 */
data class CategoryBreakdown(
    val slices: List<CategorySlice>,
    val unit: String,
    val status: SeriesStatus,
)

/**
 * 内置的分类数据源。
 *
 * 目前只有应用使用时长：`app_usage` 表里本来就有按应用分天的数据，
 * 直接按区间求和即可，不需要新的采集。
 *
 * 桌面端没有 `app_usage` 数据，按 [SeriesStatus.NoData] 处理即可。
 */
object CategorySource {
    /** 应用使用时长。配置里 `categorySourceKey` 存的就是这个值 */
    const val APP_USAGE = "app_usage"
}

/**
 * 把原始分类数据整形为饼图扇区：过滤非正值、按值降序、取前 N、其余归入「其他」。
 *
 * 抽成独立函数是为了能用 JVM 单测直接验证。这段规则错了不会报错，
 * 只会画出一张比例错误的饼——而「总额必须守恒」这种事只能靠测试保证。
 *
 * @param raw 每项为 `稳定标识、展示名、数值`
 * @param topN 保留前多少项，其余合成「其他」
 * @return 扇区列表；没有正值时返回空
 */
fun buildCategorySlices(
    raw: List<Triple<String, String, Double>>,
    topN: Int,
): List<CategorySlice> {
    val positive = raw.filter { it.third > 0.0 }.sortedByDescending { it.third }
    if (positive.isEmpty()) return emptyList()

    val limit = topN.coerceAtLeast(1)
    val head = positive.take(limit).map { (key, label, value) ->
        // label 可能为空（采集时没取到），退回包名至少能认出来源
        CategorySlice(key = key, label = label.takeIf { l -> l.isNotBlank() } ?: key, value = value)
    }
    val tail = positive.drop(limit)
    return if (tail.isEmpty()) {
        head
    } else {
        head + CategorySlice(
            key = "__other__",
            label = "其他",
            value = tail.sumOf { it.third },
        )
    }
}

/**
 * 把一个指标对应的目标值找出来。
 *
 * ## 目标值从哪来
 *
 * 图表配置里没有目标字段，硬加一个会让用户在两处维护同一个数字
 * （规则里写阈值，图表里再写一遍），迟早不一致。所以目标值直接复用
 * **任务规则里的阈值**：`task.sourceType` 存的正是 `SourceRef.key`，
 * 与图表配置的 `sourceKey` 同一种编码，能直接对上。
 *
 * ## 多条任务绑同一指标时怎么办
 *
 * 有可能存在多条任务看同一个指标，但阈值不同（「走 5000 步」和「走 10000 步」）。
 * 这时**不取最大值也不取平均值**——两者都会凭空造出一个用户没设过的目标。
 * 返回 null，让界面显示当前值并说明「目标不唯一」，由用户自己判断。
 *
 * @param sourceKey 图表序列的数据源键
 * @param metricKey 图表序列的指标键
 * @param tasks 全部任务（含已停用的：停用只影响是否判定，不改变用户设过的目标）
 * @return 唯一且为正的目标值；没有对应任务或有多个不同阈值时返回 null
 */
fun resolveRingTarget(
    sourceKey: String,
    metricKey: String?,
    tasks: List<TaskConfig>,
): Double? {
    if (sourceKey.isBlank()) return null

    val matched = tasks.filter { task ->
        // 插件源的两种键要对上：任务的 sourceType 是 `PLUGIN_x:y`，
        // 图表的 sourceKey 也是同一个；但任务还单独存了 metricBucket，
        // 所以两边都要比一次，避免不同指标误配
        task.sourceKey == sourceKey &&
            (metricKey.isNullOrBlank() || task.metricBucket.name == metricKey)
    }

    if (matched.isEmpty()) return null
    // 多个任务但阈值相同是正常情况（例如两条任务都要求 8000 步），当作唯一目标
    val thresholds = matched.map { it.threshold }.filter { it > 0.0 }.distinct()
    if (thresholds.size != 1) return null
    return thresholds.single()
}

/**
 * 把「日期 → 值」摊成只含有值日期的序列。
 *
 * 不补缺失日期：那等于替用户编造「那天是 0」的结论。断档由渲染层按断开处理。
 */
private fun seriesOf(
    label: String,
    unit: String,
    values: Map<String, Double>,
): ChartSeries {
    val points = values.entries
        .sortedBy { it.key }
        .map { SeriesPoint(it.key, it.value) }
    return ChartSeries(
        label = label,
        unit = unit,
        points = points,
        status = if (points.isEmpty()) SeriesStatus.NoData else SeriesStatus.Ok,
    )
}
