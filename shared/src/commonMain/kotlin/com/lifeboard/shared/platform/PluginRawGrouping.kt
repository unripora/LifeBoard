package com.lifeboard.shared.platform

import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.shared.repository.PluginStore
import com.lifeboard.shared.repository.model.PluginRawRecord

/**
 * 「全部数据」的读取与分组。
 *
 * 两端（Android / 桌面）读的是同一张 `plugin_raw_record` 表，用的也是同一个
 * [PluginStore]，唯一的差别是「插件声明了哪些指标」要从各自的插件宿主里拿。
 * 所以把拼装过程放在这里，两端实现 [PluginDetailStore] 时只需提供指标清单。
 */
object PluginRawGrouping {

    /**
     * 读 `[from, to]` 内的原始条目并分组。
     *
     * @param from 为 null 时表示「从有数据的第一天到今天」，由库里最早的记录决定。
     *   注意要同时看条目表和指标查询表：[getMetricQueries] 里可能记录着一个
     *   从来没返回过数据的指标，它条目表里一行都没有，只按条目算会把它排除在外。
     * @param specs 插件当前声明的指标，键为 metricKey。插件已被卸载时传空
     */
    suspend fun read(
        store: PluginStore,
        pluginId: String,
        from: String?,
        to: String,
        specs: Map<String, MetricSpec>,
    ): PluginRawPage {
        if (from == null) {
            // 一个日期都没有：这个插件从没上报过，也没有查询痕迹
            return PluginRawPage()
        }

        val rows = store.getRawRecords(pluginId, from, to)
        // 这段时间内被查询过的指标：即使一条条目都没有（接口返回 0 条），
        // 也要出现在列表里并标注「暂无」，否则用户会以为插件漏采了这个指标
        val queried = store.getMetricQueries(pluginId, from, to)
        val earliest = store.earliestRawRecordDate(pluginId)
        val latest = store.latestRawRecordDate(pluginId)

        return PluginRawPage(
            groups = rows.groupToGroups(specs, queried),
            available = if (earliest != null && latest != null) earliest to latest else null,
        )
    }

    /**
     * `null` 表示「全部」时的起点。
     *
     * 不能只按条目表的最早日期算：查过但从来没数据的指标在条目表里没有任何行，
     * 按条目算会让「全部」这一段永远看不到它。
     */
    suspend fun earliestDate(store: PluginStore, pluginId: String): String? = listOfNotNull(
        store.earliestRawRecordDate(pluginId),
        store.earliestMetricQueryDate(pluginId),
    ).minOrNull()

    /**
     * 把库里的行按指标分组。
     *
     * 同一个指标在选中的时间段里可能跨多天，这里**不再按天拆开**——
     * 用户选「近 7 天」是想看这 7 天的采样点放在一起，
     * 每天再套一层分组会多出一堆只装几行的标题。
     *
     * 排序按插件声明的顺序：让「步数、睡眠、心率…」的位置固定，
     * 而不是随这次有没有数据而上下浮动。
     */
    fun List<PluginRawRecord>.groupToGroups(
        specs: Map<String, MetricSpec>,
        queried: List<String>,
    ): List<PluginRawGroup> {
        val byMetric = groupBy { it.metricKey }

        // 有条目的 + 查过但空的，合在一起去重
        val keys = (byMetric.keys + queried).distinct()

        // 插件声明顺序优先；未声明的（插件已删掉这个指标）排在后面，保持稳定
        val order = specs.keys.withIndex().associate { (index, key) -> key to index }
        val ordered = keys.sortedBy { order[it] ?: Int.MAX_VALUE }

        return ordered.map { metricKey ->
            val spec = specs[metricKey]
            PluginRawGroup(
                metricKey = metricKey,
                // 插件已不再声明这个指标时仍展示原始 key：数据是真实采到的，
                // 用 key 至少能看出它是谁；直接丢掉反而像数据丢了
                displayName = spec?.displayName ?: metricKey,
                unit = spec?.unit.orEmpty(),
                records = byMetric[metricKey]
                    .orEmpty()
                    .sortedWith(compareBy({ it.date }, { it.seq }))
                    .map { RawRecord(timestamp = it.timestamp, value = it.value, detail = it.detail) },
            )
        }
    }
}
