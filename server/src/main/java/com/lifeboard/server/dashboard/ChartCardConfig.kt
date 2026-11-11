package com.lifeboard.server.dashboard

import com.lifeboard.server.chart.BuiltinSeries
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义图表的配置模型。
 *
 * ## 为什么单独一份配置而不是复用 sourceKey + metricKey
 *
 * 旧指标卡只能绑「一个源 + 一个指标」，而自定义图表要能叠多条线。
 * 硬塞进那两个字段会让「一条序列」和「一组序列」共用同一套语义，
 * 后面再加图例、双轴、时间窗就没地方放了。
 *
 * 所以 CHART 卡的绑定全部存在 `DashboardCardEntity.configJson` 里，
 * 旧字段对 CHART 卡保持为空，两种卡互不影响。
 *
 * ## 为什么要版本号
 *
 * 配置是用户数据，写入后可能跨多个版本长期存在。字段增删时必须有办法判断
 * 「这条配置是按哪一版写下的」，否则改一次结构就要猜老数据怎么读。
 * 解析不认识的高版本时返回 null，界面显示「配置需要修复」，
 * 而不是把不认识的字段当默认值静默画错。
 */
object ChartConfigCodec {

    /**
     * 当前写出的配置版本。改动结构时递增，并在 [decode] 里兼容旧版本。
     *
     * v2 增加序列的 `taskId`（单条规则类内置序列要指明看哪条任务），
     * 旧配置读进来时该字段为 null，含义与 v1 完全一致，所以版本号只用于写出。
     */
    const val VERSION = 2

    /**
     * 把配置编码成 JSON。
     *
     * 不抛异常：编码失败说明调用方传了不可序列化的值，属于编程错误，
     * 但让保存流程崩掉不如返回 null 由上层提示。
     */
    fun encode(config: ChartCardConfig): String? = runCatching {
        JSONObject().apply {
            put(KEY_VERSION, VERSION)
            put(KEY_TYPE, config.chartType.name)
            put(KEY_TIME_WINDOW, config.timeWindowDays)
            put(KEY_SHOW_LEGEND, config.showLegend)

            val seriesArray = JSONArray()
            config.series.forEach { series ->
                seriesArray.put(
                    JSONObject().apply {
                        put(KEY_SOURCE, series.sourceKey)
                        put(KEY_METRIC, series.metricKey)
                        put(KEY_LABEL, series.label)
                        put(KEY_UNIT, series.unit)
                        put(KEY_AXIS, series.axis.name)
                        series.colorArgb?.let { put(KEY_COLOR, it) }
                        series.taskId?.let { put(KEY_TASK_ID, it) }
                    },
                )
            }
            put(KEY_SERIES, seriesArray)

            config.categorySourceKey?.let { put(KEY_CATEGORY_SOURCE, it) }
            config.topN?.let { put(KEY_TOP_N, it) }
        }.toString()
    }.getOrNull()

    /**
     * 解析配置。
     *
     * 返回 null 的三种情况，界面都按「配置损坏」处理，不要让面板整体崩掉：
     * - JSON 本身坏了
     * - 版本号高于当前代码能理解的版本（降级安装、或数据被手工改过）
     * - 必填项缺失或图表类型不认识
     */
    fun decode(raw: String?): ChartCardConfig? {
        if (raw.isNullOrBlank()) return null
        return runCatching decode@{
            val obj = JSONObject(raw)
            // 高版本配置按未知处理：字段含义可能已经变了，不能当同版本来读
            if (obj.optInt(KEY_VERSION, 0) > VERSION) return@decode null

            val typeName = obj.optString(KEY_TYPE).takeIf { it.isNotBlank() } ?: return@decode null
            val chartType = runCatching { ChartType.valueOf(typeName) }.getOrNull()
                ?: return@decode null

            val seriesArray = obj.optJSONArray(KEY_SERIES) ?: JSONArray()
            val series = buildList {
                for (i in 0 until seriesArray.length()) {
                    val item = seriesArray.optJSONObject(i) ?: continue
                    val source = item.optString(KEY_SOURCE).takeIf { it.isNotBlank() } ?: continue
                    val metric = item.optString(KEY_METRIC).takeIf { it.isNotBlank() } ?: continue
                    add(
                        ChartSeriesConfig(
                            sourceKey = source,
                            metricKey = metric,
                            label = item.optString(KEY_LABEL).takeIf { it.isNotBlank() } ?: metric,
                            unit = item.optString(KEY_UNIT),
                            axis = parseAxis(item.optString(KEY_AXIS)),
                            colorArgb = item.optInt(KEY_COLOR, 0).takeIf { it != 0 },
                            taskId = item.optLong(KEY_TASK_ID, 0L).takeIf { it > 0L },
                        ),
                    )
                }
            }

            ChartCardConfig(
                chartType = chartType,
                series = series,
                timeWindowDays = obj.optInt(KEY_TIME_WINDOW, DEFAULT_TIME_WINDOW),
                showLegend = obj.optBoolean(KEY_SHOW_LEGEND, true),
                categorySourceKey = obj.optString(KEY_CATEGORY_SOURCE).takeIf { it.isNotBlank() },
                topN = obj.optInt(KEY_TOP_N, 0).takeIf { it > 0 },
            )
        }.getOrNull()
    }

    private fun parseAxis(raw: String): ChartAxis =
        runCatching { ChartAxis.valueOf(raw) }.getOrDefault(ChartAxis.LEFT)

    private const val DEFAULT_TIME_WINDOW = 30

    private const val KEY_VERSION = "v"
    private const val KEY_TYPE = "type"
    private const val KEY_TIME_WINDOW = "timeWindow"
    private const val KEY_SHOW_LEGEND = "showLegend"
    private const val KEY_SERIES = "series"
    private const val KEY_SOURCE = "source"
    private const val KEY_METRIC = "metric"
    private const val KEY_LABEL = "label"
    private const val KEY_UNIT = "unit"
    private const val KEY_AXIS = "axis"
    private const val KEY_COLOR = "color"
    private const val KEY_CATEGORY_SOURCE = "categorySource"
    private const val KEY_TOP_N = "topN"
    private const val KEY_TASK_ID = "taskId"
}

/**
 * 一张自定义图表的完整配置。
 *
 * @param chartType 图表形态，决定用哪套渲染与数据约束
 * @param series 指标序列。折线/柱状/面积/散点可多条；饼图与环形进度只用第一条
 * @param timeWindowDays 时间窗，固定 7/30/90
 * @param showLegend 是否显示图例
 * @param categorySourceKey 分类数据源（仅饼图使用）
 * @param topN 饼图只展示前 N 项，其余归入「其他」
 */
data class ChartCardConfig(
    val chartType: ChartType,
    val series: List<ChartSeriesConfig>,
    val timeWindowDays: Int = 30,
    val showLegend: Boolean = true,
    val categorySourceKey: String? = null,
    val topN: Int? = null,
) {
    /**
     * 这份配置是否足以画出一张有意义的图。
     *
     * 各种形态的最低要求不同，不能让一张「选了折线但没选指标」的卡进库——
     * 它打开后只会显示空图，用户以为是坏了。
     *
     * 检查项刻意保守，只挡「必然画不出来」，不挡「可能没数据」：
     * 时间窗内没数据是正常的，不该因此禁止保存。
     */
    fun isRenderable(): Boolean {
        if (timeWindowDays !in CHART_TIME_WINDOWS_DAYS) return false
        return when (chartType) {
            // 时间序列类至少要一条有效序列
            ChartType.LINE, ChartType.COLUMN, ChartType.AREA, ChartType.SCATTER ->
                series.isNotEmpty() && series.all { it.isUsable() }

            // 饼图读分类数据，不用 series
            ChartType.PIE -> !categorySourceKey.isNullOrBlank()

            // 环形进度要一个指标作为当前值
            ChartType.RING -> series.isNotEmpty() && series.all { it.isUsable() }
        }
    }
}

/**
 * 这条序列是否足以查到数据。
 *
 * 除了源与指标，还要检查「需要任务」的内置序列有没有绑定任务：
 * 连击、单条规则完成率没有任务就没有可统计的对象，放它入库只会得到一张空图，
 * 而用户看不出是自己少选了一步。这条规则以前只写在配置弹窗里，
 * 绕过界面的调用（导入、迁移、将来别的入口）就挡不住。
 */
private fun ChartSeriesConfig.isUsable(): Boolean {
    if (sourceKey.isBlank() || metricKey.isBlank()) return false
    if (BuiltinSeries.fromMetricKey(metricKey)?.needsTask == true && taskId == null) return false
    return true
}

/** 允许的时间窗天数。与 UI 上的三个选项保持一致 */
val CHART_TIME_WINDOWS_DAYS = listOf(7, 30, 90)

/**
 * 一条指标序列。
 *
 * @param label 图例展示名；为空时回退到 metricKey
 * @param unit 单位（步、小时、次……）。同单位的序列才能共用一根 Y 轴
 * @param axis 分配到的 Y 轴。不同单位最多用左右两轴
 * @param colorArgb 用户指定颜色；为空时按主题自动分配
 * @param taskId 单条任务类内置序列要看的任务；普通采集序列为 null。
 *   有值即表示这条序列走「内置业务序列」而不是查 `metric` 表，
 *   判据统一见 `BuiltinSeries.fromMetricKey`
 */
data class ChartSeriesConfig(
    val sourceKey: String,
    val metricKey: String,
    val label: String,
    val unit: String,
    val axis: ChartAxis = ChartAxis.LEFT,
    val colorArgb: Int? = null,
    val taskId: Long? = null,
)

/** Y 轴归属。Vico 最多支持两根 Y 轴，与这里的取值一一对应 */
enum class ChartAxis {
    LEFT,
    RIGHT,
}

/**
 * 图表形态。
 *
 * 存进配置的是枚举名，改名字等于改数据格式。
 */
enum class ChartType {
    /** 折线：多指标时间序列 */
    LINE,

    /** 柱状：多指标分组柱 */
    COLUMN,

    /** 面积：折线的填充变体 */
    AREA,

    /** 散点：折线仅显示点、不连线 */
    SCATTER,

    /** 饼图：单分类源的占比，使用 Top N + 其他 */
    PIE,

    /** 环形进度：单指标当前值 / 目标值 */
    RING,
}
