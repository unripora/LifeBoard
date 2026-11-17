package com.lifeboard.shared.panel

import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.repository.model.DashboardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 指标卡读到的一个值。
 *
 * 比 `MetricRecord` 多带名称与单位，都是卡片要显示、而表里没有的：
 * 光有数字的卡没有意义。
 *
 * @property value 数值；FLAG 量纲是 1 / 0
 * @property title 卡片标题，形如「小米健康 · 步数」
 * @property unit 展示单位，FLAG 为空串
 * @property bucket 决定怎么渲染（FLAG 要显示成完成/未完成）
 * @property date 这个值是哪一天的。今天没数据时要靠它说明「这是最近一次的」
 */
data class MetricReading(
    val value: Double,
    val title: String,
    val unit: String,
    val bucket: MetricBucket,
    val date: String,
)

/**
 * 指标查询的结果。空状态分两种，界面要说的话不一样，所以不能在服务层合并成 null。
 */
sealed interface MetricResult {

    /** 读到了 */
    data class Value(val reading: MetricReading) : MetricResult

    /**
     * 源或指标解析不出来。通常是卡片绑的源被删了（自建源）或插件被卸了。
     * 界面应提示「这个数据源已经不存在」，让用户知道该改卡而不是等数据。
     */
    data object SourceUnavailable : MetricResult

    /** 源在，但没有数据。今天还没采集到、或者这个源从来没采集过 */
    data object NoData : MetricResult
}

/**
 * 指标卡的读取入口（M6b2b）。
 *
 * ## 为什么需要它
 *
 * 读某一天的指标值要求调用方**先知道 bucket**，
 * 而卡片行里只存了 `sourceKey` 与 `metricKey`。这三种源求 bucket 的方式各不相同：
 * 内置源按其默认量纲、自建源读它自己那行、插件源靠插件声明的
 * [PluginMetric.bucket]。这段推理在 [SourceResolver] 收口，
 * 免得每个调用点各写一遍、各错一次。
 *
 * ## 为什么要单独判「源是否存在」
 *
 * `SourceKeys.resolve` 解析失败时会回落到 MANUAL（这是为了让规则不崩），
 * 但对卡片来说那个回落是静默的：一张绑了已删除自建源的卡会去读 MANUAL 的数据，
 * 显示成 0，用户完全看不出问题出在卡上。所以 [SourceResolver] 在这里抛
 * [SourceUnresolvable]，由本类统一折成 [MetricResult.SourceUnavailable]。
 */
class MetricQueryService(private val scope: PanelScope) {

    private val resolver = SourceResolver(scope)
    private val store get() = scope.metricStore

    /**
     * 读某一天的值。今天没数据不自动往前找——要不要退一步是调用方的取舍，
     * 见 [readWithFallback]。
     *
     * @param sourceKey 卡片上存的数据源键
     * @param metricKey 卡片上存的指标键；内置源可留空，留空时取该源的默认量纲
     * @param date `yyyy-MM-dd`
     */
    suspend fun read(
        sourceKey: String,
        metricKey: String?,
        date: String,
    ): MetricResult = withContext(Dispatchers.IO) {
        val reading = resolver.resolveReading(sourceKey, metricKey)
        // metric.bucket 列存的是量纲枚举名，不是 metricKey。
        // 内置源两者恰好同名（指标契约的设计约定），插件指标则要靠
        // 插件声明的 MetricSpec 把 metricKey 换成枚举名
        val row = store.getMetric(sourceKey, date, reading.bucket.name)
            ?: return@withContext MetricResult.NoData

        MetricResult.Value(reading.toReading(row.value, row.date))
    }

    /**
     * 读「最近一次」的值：先读 [today]，没有就在最近 [days] 天里往前找。
     *
     * 指标卡天生会「今天还没有数据」——步数要等采集跑完、打卡要等人动手。
     * 这时显示 0 是在撒谎：0 步和「还没采到」是两回事。
     * 退一步显示最近一次的值，并把日期带出去让卡片自己标注。
     *
     * @param today `yyyy-MM-dd`
     * @return 今天有数据时返回的就是今天的；整个窗口都没有返回 [MetricResult.NoData]
     */
    suspend fun readWithFallback(
        sourceKey: String,
        metricKey: String?,
        today: String,
        days: Int = 7,
    ): MetricResult = withContext(Dispatchers.IO) {
        val reading = resolver.resolveReading(sourceKey, metricKey)

        // 从库里已有的日期往回找，而不是逐天算日期字符串：
        // 日期是本地时区的 yyyy-MM-dd，自己减天数要在跨月、夏令时上出错。
        // 这里让数据自己说话。
        val from = LocalDate.parse(today).minusDays(days.toLong() - 1).toString()
        val date = store.latestDateWithBucket(sourceKey, reading.bucket.name, from, today)
            ?: return@withContext MetricResult.NoData
        val row = store.getMetric(sourceKey, date, reading.bucket.name)
            ?: return@withContext MetricResult.NoData

        MetricResult.Value(reading.toReading(row.value, row.date))
    }

    /** 读一张指标卡。卡片没绑源时返回 [MetricResult.SourceUnavailable] */
    suspend fun readCard(card: DashboardCard, today: String): MetricResult {
        val sourceKey = card.sourceKey?.takeIf { it.isNotBlank() }
            ?: return MetricResult.SourceUnavailable
        return try {
            readWithFallback(sourceKey, card.metricKey, today)
        } catch (e: SourceUnresolvable) {
            MetricResult.SourceUnavailable
        }
    }
}

/** 把一次解析好的口径套上具体数值 */
private fun SourceResolver.Reading.toReading(value: Double, date: String) = MetricReading(
    value = value,
    title = title,
    unit = unit,
    bucket = bucket,
    date = date,
)

/**
 * 可选的一个数据源及其指标，给「添加指标卡」的选择器用。
 *
 * @property metrics 这个源能读的指标。内置源与自建源各只有一个（就是它自己的量纲），
 *   插件源可能有多个
 * @property groupName 选择器里的分组名。
 *   - 插件源：插件的展示名（「小米健康」）。**必须由这里给出而不是界面按
 *     pluginId 反查**——[PanelScope.pluginMetrics] 拿得到插件的展示名，
 *     而界面只拿到一个 `PLUGIN_{id}:{metric}` 键，反查要靠解析键再猜名字
 *   - 内置源：[SOURCE_GROUP_NATIVE]（「原生」）
 *   - 自建源：[SOURCE_GROUP_CUSTOM]（「自定义」）
 */
data class MetricCandidate(
    val sourceKey: String,
    val displayName: String,
    val metrics: List<MetricOption>,
    val groupName: String = SOURCE_GROUP_NATIVE,
)

/**
 * 软件自身能力在选择器里的分组名。
 *
 * 只放 [SourceType.native] 为 true 的源。第三方来源已下推给插件，
 * 它们出现在插件分组里，不再混进「原生」。
 */
const val SOURCE_GROUP_NATIVE: String = "原生"

/**
 * 用户自建指标的分组名。
 *
 * 与原生分开是必须的：自建源的数值要靠规则命中或手动录入产生，
 * 跟「屏幕使用」这种自动采集的指标混在一组，用户分不清哪些会自己长数字。
 */
const val SOURCE_GROUP_CUSTOM: String = "自定义"

/**
 * 可选的一个指标。
 *
 * @property metricKey 写进卡片的 metricKey。内置源与自建源留空——
 *   [MetricQueryService] 能自己从源推出量纲，多存一个键反而会在源改量纲后失效
 */
data class MetricOption(
    val metricKey: String?,
    val label: String,
    val unit: String,
)

/**
 * 列出「能放进指标卡的源与指标」。
 *
 * 与「规则能选的源」的区别：那一份是能采集的源，
 * 这里还要再过滤掉读不出量的源，并把指标展开成卡片能绑的键。
 *
 * ## 三组各自的来源
 *
 * - **原生**：软件自身能力（屏幕使用、手动打卡），一个源一种量纲
 * - **自定义**：用户自建源，量纲存在它自己那行里
 * - **插件**：**一个插件可以声明多个指标**（小米健康同时给步数与睡眠），
 *   所以按 [PluginMetric] 逐个展开，每项都带 metricKey——插件不认识
 *   `MetricBucket` 枚举名，metricKey 是卡片唯一能定位到它的东西
 *
 * 三组的先后顺序就是界面上的展示顺序：原生最常用、自定义次之、插件最后。
 */
suspend fun MetricQueryService.candidates(scope: PanelScope): List<MetricCandidate> =
    withContext(Dispatchers.IO) {
        // 原生：软件自身能力（屏幕使用、手动打卡）
        val native = SourceType.entries.map { type ->
            val ref = builtinRef(type)
            MetricCandidate(
                sourceKey = ref.key,
                displayName = ref.displayName,
                metrics = listOf(
                    MetricOption(null, bucketLabelOf(ref.bucket), ref.bucket.unit),
                ),
                groupName = SOURCE_GROUP_NATIVE,
            )
        }

        // 自定义：用户自建源，一个源一种量纲
        val custom = scope.customSources().map { source ->
            MetricCandidate(
                sourceKey = source.key,
                displayName = source.displayName,
                metrics = listOf(
                    MetricOption(null, bucketLabelOf(source.bucket), source.bucket.unit),
                ),
                groupName = SOURCE_GROUP_CUSTOM,
            )
        }

        native + custom + pluginCandidates(scope)
    }

/**
 * 已加载插件的指标，按插件聚成一组。
 *
 * 每个插件聚成**一个** [MetricCandidate] 而不是每个指标一个：
 * 选择器第一屏应该显示「小米健康」，点进去才看到「步数 / 睡眠」。
 * 七个指标摊平在第一屏，用户得自己从名字里挤出它属于哪个插件。
 */
private suspend fun pluginCandidates(scope: PanelScope): List<MetricCandidate> =
    scope.pluginMetrics().mapNotNull { (pluginId, plugin) ->
        val options = plugin.metrics.map { (metricKey, metric) ->
            MetricOption(
                metricKey = metricKey,
                label = metric.displayName,
                unit = metric.unit,
            )
        }
        if (options.isEmpty()) return@mapNotNull null

        // 候选上先挂第一个指标的 key，只是为了让 sourceKey 是个合法键；
        // 用户真正选中哪个由 [sourceKeyFor] 重算
        MetricCandidate(
            sourceKey = SourceRef.pluginKey(pluginId, options.first().metricKey!!),
            displayName = plugin.displayName,
            metrics = options,
            groupName = plugin.displayName,
        )
    }

/**
 * 选中某个指标后，卡片该存的 sourceKey。
 *
 * 插件源每行数据的 key 都是 `PLUGIN_{id}:{metricKey}`（见 [SourceRef.pluginKey]），
 * 所以一个插件里的两个指标是两个不同的 sourceKey，而不是同一个源的两个指标。
 *
 * 内置源与自建源的 key 与指标无关，直接用候选上的那个。
 */
fun MetricCandidate.sourceKeyFor(option: MetricOption): String {
    val metricKey = option.metricKey ?: return sourceKey
    val plugin = SourceRef.parsePluginKey(sourceKey) ?: return sourceKey
    return SourceRef.pluginKey(plugin.first, metricKey)
}
