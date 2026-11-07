package com.lifeboard.server.metric

import android.content.Context
import com.lifeboard.core.model.defaultBucketOfKey
import com.lifeboard.core.model.SourceType
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.db.DashboardCardEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.plugin.PluginManager
import com.lifeboard.server.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 指标卡读到的一个值。
 *
 * 比 `MetricEntity` 多带名称与单位，都是卡片要显示、而表里没有的：
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
 * `MetricDao.getOne(source, date, bucket)` 要求调用方**先知道 bucket**，
 * 而卡片行里只存了 `sourceKey` 与 `metricKey`。这三种源求 bucket 的方式各不相同：
 * 内置源按 `defaultBucketOf(type)`、自建源读 `CustomSourceEntity.bucket`、
 * 插件源靠 [MetricSpec.bucketOf]。这段推理在这里收口，
 * 免得每个调用点各写一遍、各错一次。
 *
 * ## 为什么需要单独查「源是否存在」
 *
 * `SourceRegistry.resolve` 解析失败时会回落到 MANUAL（见其类注释，
 * 这是为了让规则不崩），但对卡片来说那个回落是静默的：
 * 一张绑了已删除自建源的卡会去读 MANUAL 的数据，显示成 0，
 * 用户完全看不出问题出在卡上。所以这里先自己判断一次。
 */
object MetricQueryService {

    /**
     * 读某一天的值。今天没数据不自动往前找——要不要退一步是调用方的取舍，
     * 见 [readWithFallback]。
     *
     * @param sourceKey 卡片上存的数据源键
     * @param metricKey 卡片上存的指标键；内置源可留空，留空时取该源的默认量纲
     * @param date `yyyy-MM-dd`
     * @throws SourceUnresolvable 源已经不存在。之所以不是返回
     *   [MetricResult.SourceUnavailable]：这里没法在 `withContext` 里安全地
     *   return 一个值再同时表达「出事了」，抛异常能让调用方在一个地方统一折成
     *   那个状态，见 [readCard]
     */
    suspend fun read(
        context: Context,
        sourceKey: String,
        metricKey: String?,
        date: String,
    ): MetricResult = withContext(Dispatchers.IO) {
        val resolved = resolveBucket(context, sourceKey, metricKey)
        val bucket = resolved.bucket
        val row = LifeBoardDatabase.get(context).metricDao()
            // metric.bucket 列存的是量纲枚举名，不是 metricKey。
            // 内置源两者恰好同名（指标契约的设计约定），插件指标则要靠
            // MetricSpec.bucketOf 把 metricKey 换成枚举名
            .getOne(sourceKey, date, bucket.name)
            ?: return@withContext MetricResult.NoData

        MetricResult.Value(resolved.toReading(row.value, row.date))
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
     * @throws SourceUnresolvable 源已经不存在，同 [read]
     */
    suspend fun readWithFallback(
        context: Context,
        sourceKey: String,
        metricKey: String?,
        today: String,
        days: Int = 7,
    ): MetricResult = withContext(Dispatchers.IO) {
        val resolved = resolveBucket(context, sourceKey, metricKey)
        val bucket = resolved.bucket
        val dao = LifeBoardDatabase.get(context).metricDao()

        // 从库里已有的日期往回找，而不是逐天算日期字符串：
        // 日期是本地时区的 yyyy-MM-dd，自己减天数要在跨月、夏令时上出错。
        // 这里让数据自己说话。
        val from = LocalDate.parse(today).minusDays(days.toLong() - 1).toString()
        val date = dao.latestDateWithBucket(sourceKey, bucket.name, from, today)
            ?: return@withContext MetricResult.NoData
        val row = dao.getOne(sourceKey, date, bucket.name)
            ?: return@withContext MetricResult.NoData

        MetricResult.Value(resolved.toReading(row.value, row.date))
    }

    /**
     * 求出「读哪一桶」以及这个名字该怎么显示。
     *
     * @throws SourceUnresolvable 源已经不存在了（自建源被删、插件被卸）。
     *   不用返回值表达：这是三种源共同的失败点，用异常能让调用链只在这一处判断
     */
    private suspend fun resolveBucket(
        context: Context,
        sourceKey: String,
        metricKey: String?,
    ): Resolved {
        val source = resolveExisting(context, sourceKey)

        // 插件源必须走注册表：插件的 metricKey 是它自己起的名字（如 `steps`、
        // `DEMO_STEPS`），不是 MetricBucket 枚举名，所以 MetricSpec.bucketOf 认不出，
        // 会一路掉到 COUNT 上——读出来的永远是空，或者更糟，读到别的量纲的行。
        // 量纲只能来自插件声明的 MetricSpec
        if (source is SourceRef.Plugin) {
            return resolvePlugin(context, source)
        }

        val bucket = metricKey?.let { MetricSpec.bucketOf(it) }
            ?: defaultBucketOfKey(sourceKey)
            ?: source.bucket

        return Resolved(displayName = source.displayName, bucket = bucket)
    }

    /**
     * 插件源：从已加载插件的声明里取量纲与展示名。
     *
     * 插件被卸载、或新版本不再声明这个指标时抛 [SourceUnresolvable]：
     * 对卡片来说这就是「源已经不存在」，应该让用户重新绑定，
     * 而不是拿一个猜出来的量纲继续读。
     */
    private fun resolvePlugin(context: Context, source: SourceRef.Plugin): Resolved {
        val loaded = PluginManager.loaded().firstOrNull { it.pluginId == source.pluginId }
            ?: throw SourceUnresolvable(source.key)
        val spec = loaded.metrics[source.metricKey]
            ?: throw SourceUnresolvable(source.key)

        return Resolved(
            displayName = "${loaded.plugin.displayName}·${spec.displayName}",
            bucket = spec.bucket,
            unit = spec.unit,
            metricLabel = spec.displayName,
        )
    }

    /**
     * 解析源，但解析不出来时抛异常而不是跟着回落到 MANUAL。
     *
     * 判据：`SourceRegistry.resolve` 只在完全认不出 key 时返回 MANUAL。
     * 所以「解析结果的 key 与入参不一致」就说明没认出来；
     * 再加一条「入参本身不是 MANUAL」，否则库里的 MANUAL 卡会被误判成源缺失
     * （那时解析结果与入参正好相同，本来也不会误判，这条是显式说明意图）。
     */
    private suspend fun resolveExisting(context: Context, sourceKey: String): SourceRef {
        val resolved = SourceRegistry.resolve(context, sourceKey)
        val isManual = sourceKey == SourceType.MANUAL.name
        if (resolved.key != sourceKey && !isManual) {
            throw SourceUnresolvable(sourceKey)
        }
        return resolved
    }

    /**
     * 解析好的一次读取：读哪一桶、叫什么、单位是什么。
     *
     * @property unit 单位。插件可以自己声明带单位的展示（如「步」），
     *   所以不能统一取 `bucket.unit`
     * @property metricLabel 指标在标题里的名字。插件给的是它自己声明的
     *   displayName（「步数」），内置源与自建源没有声明过，用量的中文名
     */
    private data class Resolved(
        val displayName: String,
        val bucket: MetricBucket,
        val unit: String = bucket.unit,
        val metricLabel: String = bucketLabelOf(bucket),
    ) {
        /** 卡片标题：「源名 · 指标名」 */
        val title: String get() = "$displayName · $metricLabel"

        fun toReading(value: Double, date: String) = MetricReading(
            value = value,
            title = title,
            unit = unit,
            bucket = bucket,
            date = date,
        )
    }
}

/** 源已经不存在了。见 [MetricQueryService.resolveExisting] */
class SourceUnresolvable(val key: String) : Exception("数据源已不存在：$key")

/**
 * 可选的一个数据源及其指标，给「添加指标卡」的选择器用。
 *
 * @property metrics 这个源能读的指标。内置源与自建源各只有一个（就是它自己的量纲），
 *   插件源可能有多个
 */
data class MetricCandidate(
    val sourceKey: String,
    val displayName: String,
    val metrics: List<MetricOption>,
)

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
 * 与 `SourceRegistry.selectable` 的区别：那一份是给规则用的（能采集的源），
 * 这里还要再过滤掉读不出量的源，并把指标展开成卡片能绑的键。
 *
 * ## 三种源各自怎么来
 *
 * - 内置源：一个源一种量纲，只有一项可选。量与含义由 `defaultBucketOfKey` 给出
 * - 自建源：同上，量纲存在它自己那行里
 * - 插件源：**一个插件可以声明多个指标**（小米健康同时给步数与睡眠），
 *   所以按 `MetricSpec` 逐个展开，每项都带 metricKey——插件不认识
 *   `MetricBucket` 枚举名，metricKey 是卡片唯一能定位到它的东西
 *
 * 插件只列 [PluginManager.loaded] 里的，也就是「已装且已信任」。
 * 未信任的插件一列出来，用户加完卡只会看到「源已不存在」。
 */
suspend fun MetricQueryService.candidates(context: Context): List<MetricCandidate> =
    withContext(Dispatchers.IO) {
        val builtinAndCustom = SourceRegistry.selectable(context).mapNotNull { source ->
            val options = when (source) {
                is SourceRef.Builtin -> {
                    val bucket = defaultBucketOfKey(source.key) ?: return@mapNotNull null
                    listOf(MetricOption(null, bucketLabelOf(bucket), bucket.unit))
                }
                is SourceRef.Custom ->
                    listOf(MetricOption(null, bucketLabelOf(source.bucket), source.bucket.unit))
                // 插件源不在这一批里，下面单独展开
                is SourceRef.Plugin -> return@mapNotNull null
            }
            MetricCandidate(source.key, source.displayName, options)
        }
        builtinAndCustom + pluginCandidates()
    }

/**
 * 已加载插件的指标，按插件聚成一组。
 *
 * 每个插件聚成**一个** [MetricCandidate] 而不是每个指标一个：
 * 选择器第一屏应该显示「小米健康」，点进去才看到「步数 / 睡眠」。
 * 七个指标摊平在第一屏，用户得自己从名字里挤出它属于哪个插件。
 */
private fun pluginCandidates(): List<MetricCandidate> =
    PluginManager.loaded()
        .filter { it.trusted }
        .mapNotNull { loaded ->
            val options = loaded.plugin.metrics.map { spec ->
                MetricOption(
                    // 插件源的 metricKey 必须存：它不是量纲枚举名，
                    // 没有它就读不出这个指标
                    metricKey = spec.metricKey,
                    label = spec.displayName,
                    unit = spec.unit,
                )
            }
            if (options.isEmpty()) return@mapNotNull null

            MetricCandidate(
                // 卡片存的是 per-metric 的落库键，而这里一个插件一组、
                // 组内有多个指标，所以 sourceKey 由选中的 metricKey 再拼出来。
                // 见 MetricOption.toSourceKey
                sourceKey = SourceRef.pluginKey(loaded.pluginId, options.first().metricKey!!),
                displayName = loaded.plugin.displayName,
                metrics = options,
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

/**
 * 量纲的中文名。
 *
 * 不直接用枚举名：选择器上写「COUNT」对用户没有意义。
 * 提成顶层函数是因为它同时要被卡片标题和选择器用到。
 */
internal fun bucketLabelOf(bucket: MetricBucket): String = when (bucket) {
    MetricBucket.DURATION_MINUTES -> "时长"
    MetricBucket.SLEEP_MINUTES -> "睡眠"
    MetricBucket.STEPS -> "步数"
    MetricBucket.COUNT -> "计数"
    MetricBucket.FLAG -> "完成情况"
}

/** 读一张指标卡。卡片没绑源时返回 [MetricResult.SourceUnavailable] */
suspend fun MetricQueryService.readCard(
    context: Context,
    card: DashboardCardEntity,
    today: String,
): MetricResult {
    val sourceKey = card.sourceKey?.takeIf { it.isNotBlank() }
        ?: return MetricResult.SourceUnavailable
    return try {
        readWithFallback(context, sourceKey, card.metricKey, today)
    } catch (e: SourceUnresolvable) {
        MetricResult.SourceUnavailable
    }
}
