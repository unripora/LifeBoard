package com.lifeboard.plugin.api

/**
 * 契约版本与指标校验。
 *
 * ## 兼容策略：向后兼容，不做严格相等
 *
 * 插件 [SourcePlugin.apiVersion] **不高于** [VERSION] 即可加载。
 * 契约只允许「加东西」——新增带默认实现的方法、新增数据类，
 * 这些对已编译的插件二进制没有影响：它不需要重写新方法，调用时自然走默认实现。
 *
 * 只有**破坏性变更**（改已有方法签名、删字段、改语义）才需要插件重新编译，
 * 而那类变更的真实后果是 `NoSuchMethodError` / `AbstractMethodError`，
 * 不是版本号能拦住的——所以在这里拦「版本不等」没有保护作用，只会误伤。
 *
 * 之前是严格相等判定，结果是：宿主为了一个带默认实现的新方法升了版本号，
 * 用户手上所有插件立刻全部拒绝加载。那个代价换不来任何安全性。
 */
object PluginApi {

    /**
     * 当前契约版本。**只有破坏性变更才递增它**
     *
     * 2：`SourcePlugin.login` 增加 `inputs` 参数，`LoginResult` 增加 `NeedInput`
     * （小米健康的验证码/二次验证必须有地方表达）。这是破坏性变更：
     * 旧插件实现的是单参数 `login`，新宿主调不到它。
     *
     * 3：新增详情页声明式 UI——`SourcePlugin.detailUi` / `SourcePlugin.onAction`
     * 与 [UiBlock]、[ActionResult]。两个方法都有默认实现，**非破坏性**：
     * 声明 2 的插件可以照常加载，只是详情页里没有它自己的区块。
     */
    const val VERSION: Int = 3
}

/**
 * 指标元数据的校验工具。
 *
 * 交给插件作者和宿主加载器共用：插件可以在自己的测试里先跑一遍，
 * 宿主在加载时再跑一遍，冲突就不会拖到用户拉数时才暴露。
 */
object PluginMetrics {

    /**
     * 校验 plugs 声明的指标列表。
     *
     * 规则：
     * - metricKey 不得为空、不得含 `:`（`:` 是插件 key 的分隔符，含了就无法往返解析）
     * - 同一 metricKey 只能出现一次；重复且量纲相同也是错的——
     *   「声明了两遍」和「声明冲突」一样会让 `metric` 表主键含义变得模糊
     *
     * @return 问题列表，空表示合法。**不抛异常**：加载器要把原因展示给用户，
     *   抛异常只能得到一句 stacktrace
     */
    fun validate(metrics: List<MetricSpec>): List<String> {
        val problems = mutableListOf<String>()
        val seen = mutableMapOf<String, MetricBucket>()

        metrics.forEach { spec ->
            if (!SourceRef.isValidPluginKeyPart(spec.metricKey)) {
                problems += "指标键非法（不能为空或含 ':'）：\"${spec.metricKey}\""
                return@forEach
            }
            val previous = seen.put(spec.metricKey, spec.bucket)
            when {
                previous == null -> Unit
                previous == spec.bucket ->
                    problems += "指标键 \"${spec.metricKey}\" 重复声明"
                else ->
                    problems += "指标键 \"${spec.metricKey}\" 声明了两个量纲：" +
                        "$previous 与 ${spec.bucket}，同一指标只能有一个量纲"
            }
        }
        return problems
    }

    /** [validate] 的布尔形式，供插件自测时简洁使用 */
    fun isValid(metrics: List<MetricSpec>): Boolean = validate(metrics).isEmpty()

    /**
     * 把插件产出的 [PluginMetric] 映射成量纲已知的指标。
     *
     * 量纲只能来自插件声明的 [MetricSpec]，这是 [PluginMetric] 不携带 bucket 的原因：
     * 声明与产出的量纲从结构上就不可能不一致。
     *
     * @return 未声明的 metricKey 不会被猜测补齐，而是收进 [Resolved.rejected]：
     *   猜一个量纲（例如统统当 COUNT）会写入一行没人能解释的数据
     */
    fun resolve(
        declared: List<MetricSpec>,
        produced: List<PluginMetric>,
    ): Resolved {
        val byKey = declared.associateBy { it.metricKey }
        val accepted = mutableListOf<Pair<MetricSpec, PluginMetric>>()
        val rejected = mutableListOf<PluginMetric>()
        produced.forEach { metric ->
            val spec = byKey[metric.metricKey]
            if (spec == null) rejected += metric else accepted += spec to metric
        }
        return Resolved(accepted, rejected)
    }

    /**
     * [resolve] 的结果。
     *
     * @property accepted 声明与产出对得上的数据，可直接落库
     * @property rejected 插件产出了未声明的 metricKey，应记健康度告警而非静默写入
     */
    data class Resolved(
        val accepted: List<Pair<MetricSpec, PluginMetric>>,
        val rejected: List<PluginMetric>,
    ) {
        val isEmpty: Boolean get() = accepted.isEmpty() && rejected.isEmpty()
    }
}
