package com.lifeboard.plugin.api

import java.io.File

/**
 * 插件运行时拿到的上下文。
 *
 * 由宿主构造，构造时**绑定好本插件的 pluginId**。
 * 插件拿不到「指定他人 pluginId」的入口，跨插件读取在接口层面就不可表达——
 * 这是凭据隔离的落脚点，不是靠运行时检查。
 */
interface PluginContext {

    /** 本插件的全局唯一标识，由宿主注入，插件不能修改 */
    val pluginId: String

    /** 本插件名下的凭据存取口，已绑定 [pluginId] */
    val credentials: CredentialStore

    /** 本插件私有目录（`filesDir/plugins/{id}/data`），已创建 */
    val filesDir: File

    /**
     * 读本插件**自己**已落库的指标，按日期升序。
     *
     * 用途是详情页画趋势图这类「把已有数据展示出来」的场景：
     * 数据由宿主采集链路写入，插件只读。
     *
     * **只返回本插件的数据**，实现已绑定 [pluginId]，插件无法用它读别人的指标。
     * 写入不在这里提供——插件产出数据一律走 `fetch`，由宿主校验量纲、去重、
     * 更新健康度后落库。给自己开一个写入口等于绕过这些检查。
     *
     * @param metricKey 指标键，须属于插件声明的 `metrics`
     * @param days 从今天往前数几天（含今天）
     * @return 有数据的日子，缺的日期不会补 0——「没采到」和「真的是 0」必须能区分
     */
    suspend fun recentMetrics(metricKey: String, days: Int): List<MetricSample>

    /**
     * 一次读本插件**已落库的全部指标**，按指标分组。
     *
     * 与 [recentMetrics] 的区别只在调用形态，不在权限：两者都绑定本插件，
     * 都读不到别人的数据。但插件声明的指标数会增长（小米健康以后可能加心率），
     * 逐个调 [recentMetrics] 会让「展示全部数据」这类代码每加一个指标就要改一次，
     * 漏掉一个就变成「有数据但页面上看不见」。
     *
     * 返回的每一项都对应 [SourcePlugin.metrics] 里声明过的一个 metricKey，
     * 且**只有真有数据的指标才会出现**——没采到的指标不返回空列表，
     * 「没采到」和「采到 0」仍然是两件事。
     *
     * 默认实现走 [recentMetrics] 逐个拼接：宿主换实现时插件无需改动。
     *
     * @param days 从今天往前数几天（含今天）
     * @param metrics 参与读取的 metricKey 清单；缺省取插件声明的全部指标
     */
    suspend fun allRecentMetrics(
        days: Int,
        metrics: List<String>,
    ): Map<String, List<MetricSample>> {
        if (days <= 0) return emptyMap()
        return metrics.mapNotNull { key ->
            recentMetrics(key, days).takeIf { it.isNotEmpty() }?.let { key to it }
        }.toMap()
    }

    /**
     * 上报本次 [SourcePlugin.fetch] 拿到的**原始条目**。
     *
     * ## 为什么需要这条通道
     *
     * `fetch` 返回的是 [PluginMetric]——已经按当日聚合成一个数字
     * （小米健康的步数是采样点累加、睡眠是按自然日裁剪后累加）。
     * 聚合之后，接口里真正返回的那些条目（每个步数采样点、每段睡眠的起止时间）
     * 就再也拿不到了。用户排查「步数为什么是 1253」时需要的恰恰是这些原始条目。
     *
     * 所以插件在 `fetch` 内把聚合前的条目原样交上来，宿主存成一份
     * 「**上次同步的快照**」：下次同步整体覆盖，不累积历史。
     *
     * ## 约定
     *
     * - 只在 [SourcePlugin.fetch] 内调用。宿主把这个调用当成「本次同步的原始数据」，
     *   每次采集开始时清空本插件的快照，所以不必自己清理
     * - 条目只是给人看的展示数据，**不参与**任何判定与计分：真正的数值仍然走
     *   [PluginMetric]，那条路径上的量纲校验、去重、健康度一概不变
     * - 上报失败的条目不会影响采集结果，插件不必 try/catch
     *
     * @param metricKey 这些条目属于哪个指标，须属于插件声明的 `metrics`
     * @param records 原始条目，按时间升序；宿主不会解读其中的字段
     * @param date 这批条目属于哪一天（`yyyy-MM-dd`）。
     *   默认空串表示「就是本次 fetch 的日期」——绝大多数插件只拉当天，不必传。
     *   按日回补历史时（一次 fetch 内拉多天）才需要显式指定，
     *   否则不同日期的条目会被混进同一天。
     */
    suspend fun reportRawRecords(
        metricKey: String,
        records: List<RawRecord>,
        date: String = "",
    ) {
        // 默认空实现：不需要展示原始数据的插件不必重写
    }

    /**
     * 告诉宿主「这次同步确实查询了这些指标」。
     *
     * ## 为什么需要它
     *
     * 光看 [reportRawRecords] 无法区分两种情况：
     * - 插件根本没实现原始数据上报（数据不该出现在页面上）
     * - 插件查了但这次一条没拿到（应该显示「暂无」）
     *
     * 前者显示「暂无」是错的——会让人以为插件工作不正常；后者不显示也是错的——
     * 用户点名要看的指标凭空消失，看不出是没数据还是漏采了。
     *
     * 所以把「查过」这件事显式说出来。只在指标真的没有条目时才有必要调用：
     * 有条目时 [reportRawRecords] 已经隐含了「查过」。
     *
     * @param metricKey 本次查询的指标，须属于插件声明的 `metrics`
     * @param date 查询的是哪一天（`yyyy-MM-dd`），空串表示本次 `fetch` 的日期
     */
    suspend fun reportMetricQueried(metricKey: String, date: String = "") {
        // 默认空实现：不展示原始数据的插件不必关心
    }
}

/**
 * 插件上报的一条原始数据。
 *
 * 刻意做成「时间戳 + 一个数值 + 一段说明」这种最小结构，而不是让插件交任意 JSON：
 * - 宿主能统一排版、排序、按指标分组，不用为每个插件的字段结构写一套渲染
 * - 插件交上来的东西永远只是**展示用**，不参与判定，所以不需要可被程序消费的强类型
 *
 * [detail] 留给表达不了的部分，比如睡眠区间的「23:40 → 07:20」——
 * 那是两个时间点，硬塞进 [timestamp] 与 [value] 会丢掉其中一个。
 *
 * @property timestamp 条目发生的时间（毫秒）。区间类条目取起始时刻
 * @property value 条目的数值。区间类条目填时长，纯说明性条目可以是 0
 * @property detail 附注，展示在数值旁边；为空则只显示时间与数值
 */
data class RawRecord(
    val timestamp: Long,
    val value: Double,
    val detail: String = "",
)

/**
 * 一条历史指标值。
 *
 * @property date `yyyy-MM-dd`
 */
data class MetricSample(
    val date: String,
    val value: Double,
)

/**
 * 一个指标的全量历史，[PluginContext.allRecentMetrics] 的一项。
 *
 * @property metricKey 指标键，对应 [SourcePlugin.metrics] 里的声明
 * @property displayName 展示名，取自插件的 [MetricSpec]；宿主据此免去二次查表
 * @property unit 展示单位
 * @property samples 按日期升序，缺的日期不补 0
 */
data class MetricSeries(
    val metricKey: String,
    val displayName: String,
    val unit: String,
    val samples: List<MetricSample>,
) {
    /** 最后一次有数据的值；没有数据返回 null */
    val latest: MetricSample? get() = samples.lastOrNull()

    /** 窗口内的合计，用于「近 N 天共走了多少步」这类汇总 */
    val total: Double get() = samples.sumOf { it.value }
}

/**
 * 凭据存取口。
 *
 * 只暴露「本插件自己的槽位」：所有方法都没有 pluginId 参数，
 * 自然无法越权访问他人凭据。
 *
 * 约定：
 * - 槽位名由插件自定义（如 `access_token`），插件内唯一
 * - 值一律按敏感数据对待，**插件不得把值写进日志**
 * - [clear] 清空本插件**全部**槽位
 */
interface CredentialStore {

    /** 读取某槽位；不存在或解密失败返回 null */
    suspend fun get(slot: String): String?

    /** 写入（覆盖）某槽位 */
    suspend fun put(slot: String, value: String)

    /** 删除单个槽位 */
    suspend fun remove(slot: String)

    /** 清空本插件名下全部凭据（登出时调用） */
    suspend fun clear()
}
