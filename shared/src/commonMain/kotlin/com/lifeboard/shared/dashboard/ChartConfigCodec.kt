package com.lifeboard.shared.dashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 自定义图表的配置编解码。
 *
 * ## 为什么单独一份配置而不是复用 sourceKey + metricKey
 *
 * 旧指标卡只能绑「一个源 + 一个指标」，而自定义图表要能叠多条线。
 * 硬塞进那两个字段会让「一条序列」和「一组序列」共用同一套语义，
 * 后面再加图例、双轴、时间窗就没地方放了。
 *
 * 所以 CHART 卡的绑定全部存在 `DashboardCard.configJson` 里，
 * 旧字段对 CHART 卡保持为空，两种卡互不影响。
 *
 * ## 为什么用 kotlinx-serialization 而不是 org.json
 *
 * 配置要在 Android 与桌面读同一份：Android 框架自带的 `org.json` 在桌面 JVM 上
 * 并不存在，继续用它就得给两端各写一套编解码，两份实现迟早漂移，
 * 而漂移的后果是「同一张卡在桌面打开正常、在手机上显示配置损坏」。
 *
 * ## 为什么不用 `@Serializable` 注解反射生成
 *
 * 这里的字段名与 JSON 键刻意不同（`timeWindowDays` ↔ `timeWindow`），
 * 且对「高版本配置」「空字符串等同缺失」有专门的宽松处理。
 * 手写读法把兼容规则集中在一处，比一堆 `@SerialName` + 自定义 Serializer 更好读。
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
     *
     * v3 增加 `title`（用户自定义标题）与 `density`（卡片密度）。
     * 两者缺失时分别回退到「空标题」与 [ChartDensity.COMFORTABLE]，
     * 与 v2 数据的行为完全一致，因此 v2 配置照常读得进来。
     */
    const val VERSION = 3

    /**
     * 把配置编码成 JSON。
     *
     * 不抛异常：编码失败说明调用方传了不可序列化的值，属于编程错误，
     * 但让保存流程崩掉不如返回 null 由上层提示。
     */
    fun encode(config: ChartCardConfig): String? = runCatching {
        buildJsonObject {
            put(KEY_VERSION, JsonPrimitive(VERSION))
            put(KEY_TYPE, JsonPrimitive(config.chartType.name))
            put(KEY_TIME_WINDOW, JsonPrimitive(config.timeWindowDays))
            put(KEY_SHOW_LEGEND, JsonPrimitive(config.showLegend))
            // 空标题不写：默认标题是算出来的，写进去反而会把「用户没命名」
            // 变成一个「命名成空串」的历史值，将来改默认标题时旧卡不跟随
            if (config.title.isNotBlank()) put(KEY_TITLE, JsonPrimitive(config.title))
            put(KEY_DENSITY, JsonPrimitive(config.density.name))

            put(
                KEY_SERIES,
                buildJsonArray {
                    config.series.forEach { series ->
                        add(
                            buildJsonObject {
                                put(KEY_SOURCE, JsonPrimitive(series.sourceKey))
                                put(KEY_METRIC, JsonPrimitive(series.metricKey))
                                put(KEY_LABEL, JsonPrimitive(series.label))
                                put(KEY_UNIT, JsonPrimitive(series.unit))
                                put(KEY_AXIS, JsonPrimitive(series.axis.name))
                                series.colorArgb?.let { put(KEY_COLOR, JsonPrimitive(it)) }
                                series.taskId?.let { put(KEY_TASK_ID, JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )

            config.categorySourceKey?.let { put(KEY_CATEGORY_SOURCE, JsonPrimitive(it)) }
            config.topN?.let { put(KEY_TOP_N, JsonPrimitive(it)) }
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
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return@decode null
            // 高版本配置按未知处理：字段含义可能已经变了，不能当同版本来读
            if (obj.int(KEY_VERSION) > VERSION) return@decode null

            val typeName = obj.str(KEY_TYPE) ?: return@decode null
            val chartType = ChartType.entries.firstOrNull { it.name == typeName }
                ?: return@decode null

            val seriesArray = obj[KEY_SERIES] as? JsonArray ?: JsonArray(emptyList())
            val series = buildList {
                seriesArray.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    val source = item.str(KEY_SOURCE) ?: return@forEach
                    val metric = item.str(KEY_METRIC) ?: return@forEach
                    add(
                        ChartSeriesConfig(
                            sourceKey = source,
                            metricKey = metric,
                            label = item.str(KEY_LABEL) ?: metric,
                            unit = item.str(KEY_UNIT).orEmpty(),
                            axis = item.str(KEY_AXIS)
                                ?.let { name -> ChartAxis.entries.firstOrNull { it.name == name } }
                                ?: ChartAxis.LEFT,
                            colorArgb = item.int(KEY_COLOR).takeIf { it != 0 },
                            taskId = item.long(KEY_TASK_ID).takeIf { it > 0L },
                        ),
                    )
                }
            }

            ChartCardConfig(
                title = obj.str(KEY_TITLE).orEmpty(),
                chartType = chartType,
                series = series,
                timeWindowDays = item(obj, KEY_TIME_WINDOW, DEFAULT_TIME_WINDOW),
                showLegend = obj[KEY_SHOW_LEGEND]?.jsonPrimitive?.booleanOrNull ?: true,
                categorySourceKey = obj.str(KEY_CATEGORY_SOURCE),
                topN = obj.int(KEY_TOP_N).takeIf { it > 0 },
                // 不认识的密度名按大卡处理：v2 及更早的配置里没有这个键，
                // 它们的观感就是大卡，不能因为读不到就变成方块
                density = obj.str(KEY_DENSITY)
                    ?.let { name -> ChartDensity.entries.firstOrNull { it.name == name } }
                    ?: ChartDensity.COMFORTABLE,
            )
        }.getOrNull()
    }

    /**
     * 宽松读整数：缺失、非数字、或不是 JSON 数字时都给 [fallback]。
     *
     * `org.json` 的 `optInt` 会把字符串 `"30"` 也解析成 30，这里刻意保持一致，
     * 否则同一份历史数据在两个实现下会读出不同结果。
     */
    private fun item(obj: JsonObject, key: String, fallback: Int): Int =
        obj[key]?.jsonPrimitive?.let { it.intOrNull ?: it.content.toIntOrNull() } ?: fallback

    private fun JsonObject.int(key: String): Int = item(this, key, 0)

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.let { it.longOrNull ?: it.content.toLongOrNull() } ?: 0L

    /**
     * 宽松读字符串：空串视同缺失。
     *
     * 空串在配置里没有意义（没有哪个字段的合法值是空），
     * 当作缺失处理才能让 `?:` 兜底逻辑生效，而不是把一个空标签画到图例上。
     */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private const val DEFAULT_TIME_WINDOW = 30

    /**
     * 解析器配置：`isLenient` 关掉。
     *
     * 历史数据是本应用自己写出的严格 JSON，放开宽松模式只会让
     * 「配置被手工改坏」这类问题更晚才被发现。
     */
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = true
    }

    private const val KEY_VERSION = "v"
    private const val KEY_TYPE = "type"
    private const val KEY_TITLE = "title"
    private const val KEY_DENSITY = "density"
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
