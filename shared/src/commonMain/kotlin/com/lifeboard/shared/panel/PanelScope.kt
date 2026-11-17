package com.lifeboard.shared.panel

import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.repository.MetricStore
import com.lifeboard.shared.repository.RuleStore
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.TaskStore

/**
 * 面板业务层需要的全部数据与平台能力。
 *
 * ## 为什么要有这个接口
 *
 * `panel` / `metric` / `chart` 这三块逻辑本来写在 `:server`，构造函数里塞着
 * `android.content.Context`，只为了两件事：取 Store、取 `applicationContext` 去建闹钟。
 * 结果同一套判定与读数逻辑没法在桌面端复用——而口径分叉正是这次重构要消灭的东西。
 *
 * 把「要哪些 Store」和「平台侧要做什么」显式列成一个端口以后，这段逻辑回到 commonMain，
 * Android 与桌面各自提供实现。
 *
 * ## 为什么自带 [customSources]
 *
 * 自建源的展示名与量纲在 `custom_source` 表里，解析 `SourceRef` 时要用。
 * 直接依赖 `RuleStore` 也行，但那样每个实现都得再拼一遍映射；
 * 这里收口成一个方法，两端只需实现一次。
 */
interface PanelScope {

    val taskStore: TaskStore
    val metricStore: MetricStore
    val ruleStore: RuleStore

    /** 全部自建源。解析 `CUSTOM_{id}` 时用，顺序按 id 升序 */
    suspend fun customSources(): List<SourceRef.Custom>

    /**
     * 插件声明的指标，key 是 pluginId。
     *
     * 按插件聚合而不是按 `PLUGIN_{id}:{metric}` 摊平：「添加指标卡」的选择器要按插件分组，
     * 而一个插件可以声明多个指标（小米健康同时给步数与睡眠）。
     *
     * 只有已加载且已信任的插件才会出现在这里。桌面端没有插件运行时，
     * 返回空 Map 即可——那时插件源卡片的正确表现就是「源已不存在」。
     */
    suspend fun pluginMetrics(): Map<String, PluginMetricSet>

    /**
     * 任务写入后的平台侧联动：重排提醒闹钟。
     *
     * 桌面端没有系统闹钟，实现为空。
     */
    suspend fun onTaskSaved(taskId: Long)

    /** 任务删除后的平台侧清理：撤销闹钟与未完成通知 */
    suspend fun onTaskDeleted(taskId: Long)
}

/**
 * 插件声明的一个指标。
 *
 * @property displayName 指标名（「步数」），用在卡片标题里
 * @property bucket 量纲，决定去 `metric` 表的哪一桶里读
 * @property unit 单位，插件可以自定义（「步」而不是「次」）
 */
data class PluginMetric(
    val displayName: String,
    val bucket: MetricBucket,
    val unit: String = bucket.unit,
)

/**
 * 一个插件声明的全部指标，以及它在界面上的展示名。
 *
 * 单独拎出 [displayName] 是因为「添加指标卡」的选择器要按插件分组，
 * 而卡片行里只存了 `PLUGIN_{id}:{metric}` 这种键，反查名字只能靠这里。
 */
data class PluginMetricSet(
    val displayName: String,
    val metrics: Map<String, PluginMetric>,
)

/**
 * 按 [PanelScope] 解析数据源引用。
 *
 * 与 `SourceKeys` 的区别：那一层是纯函数，只认传进来的自建源列表；
 * 这里负责把列表取出来，并额外补上插件源的真实元数据。
 * 面板读数必须走这条路径——插件源的量纲只能来自插件自己的声明，
 * 用 `MetricSpec.bucketOf(metricKey)` 猜会一路掉到 COUNT，读出来永远是空。
 */
class SourceResolver(private val scope: PanelScope) {

    /**
     * 解析一个 key。
     *
     * @throws SourceUnresolvable 自建源已被删除、或插件指标已不存在。
     *   抛出而不是回落到 MANUAL：卡片绑的源没了就该说「源已不存在」，
     *   拿手动打卡的数据顶上会让用户以为卡是好的。
     */
    suspend fun resolve(key: String): SourceRef {
        val customs = scope.customSources()
        val resolved = SourceKeys.resolve(key) { id -> customs.firstOrNull { it.id == id } }

        return when (resolved) {
            is SourceKeys.Resolved.Ok -> enrichPlugin(resolved.ref, key)
            // 兜底时 ref 的 key 与入参不一致，说明这个 key 没被认出来：
            // 自建源被删、或是历史遗留的未知键。都算「源已不存在」。
            // 入参本身就是 MANUAL 时两者相同，那是真的手动打卡，照常返回。
            is SourceKeys.Resolved.Fallback -> {
                if (resolved.ref.key != key) throw SourceUnresolvable(key)
                resolved.ref
            }
        }
    }

    /**
     * 插件源补上真实展示名与量纲；内置源与自建源原样返回。
     *
     * `SourceKeys` 里插件源只能兜出一个「pluginId·metricKey」的名字与 COUNT 量纲，
     * 因为那层拿不到插件注册表。这里补上插件自己声明的名字和量纲；
     * 插件没在列表里（被卸了、或根本没装）就当作源已不存在。
     */
    private suspend fun enrichPlugin(ref: SourceRef, key: String): SourceRef {
        if (ref !is SourceRef.Plugin) return ref
        val plugin = scope.pluginMetrics()[ref.pluginId] ?: throw SourceUnresolvable(key)
        val metric = plugin.metrics[ref.metricKey] ?: throw SourceUnresolvable(key)
        return ref.copy(
            displayName = "${plugin.displayName}·${metric.displayName}",
            bucket = metric.bucket,
        )
    }

    /**
     * 读某一天要用的量纲与展示名。
     *
     * 卡片行里只存了 `sourceKey` 与可空的 `metricKey`，读数前必须先把量纲定下来。
     * 三种源求法不同：内置源按指标契约的 `MetricSpec.bucketOf`、自建源看它自己那行、
     * 插件源看 [PluginMetric.bucket]。
     */
    suspend fun resolveReading(sourceKey: String, metricKey: String?): Reading {
        val source = resolve(sourceKey)
        if (source is SourceRef.Plugin) {
            val metric = scope.pluginMetrics()[source.pluginId]
                ?.metrics?.get(source.metricKey)
                ?: throw SourceUnresolvable(sourceKey)
            return Reading(
                displayName = source.displayName,
                bucket = metric.bucket,
                unit = metric.unit,
                metricLabel = metric.displayName,
            )
        }
        val bucket = metricKey?.let { MetricSpec.bucketOf(it) } ?: source.bucket
        return Reading(
            displayName = source.displayName,
            bucket = bucket,
            unit = bucket.unit,
            metricLabel = bucketLabelOf(bucket),
        )
    }

    /** 一次解析好的读数口径 */
    data class Reading(
        val displayName: String,
        val bucket: MetricBucket,
        val unit: String,
        val metricLabel: String,
    ) {
        /** 卡片标题：「源名 · 指标名」 */
        val title: String get() = "$displayName · $metricLabel"
    }
}

/** 数据源或指标已经不存在。见 [SourceResolver.resolve] */
class SourceUnresolvable(val key: String) : Exception("数据源已不存在：$key")

/**
 * 量纲的中文名。
 *
 * 不直接用枚举名：选择器上写「COUNT」对用户没有意义。
 * 提成顶层函数是因为它同时要被卡片标题和选择器用到。
 */
fun bucketLabelOf(bucket: MetricBucket): String = when (bucket) {
    MetricBucket.DURATION_MINUTES -> "时长"
    MetricBucket.SLEEP_MINUTES -> "睡眠"
    MetricBucket.STEPS -> "步数"
    MetricBucket.COUNT -> "计数"
    MetricBucket.FLAG -> "完成情况"
}
