package com.lifeboard.plugin.api

/**
 * 插件拉取到的一条指标数据。
 *
 * **只有 [metricKey] 与 [value]，没有量纲**：量纲由插件在 [SourcePlugin.metrics] 里声明的
 * [MetricSpec] 给出，宿主落库前按 key 反查补齐。
 *
 * 这样从结构上就不可能出现「声明的量纲与产出的量纲不一致」：
 * 同一条数据不会被两种口径解读，也不需要宿主逐条校验后丢弃。
 * 与 M1 把 `MetricRecord.bucket` 改成从 source 派生的做法是同一个思路。
 *
 * @property metricKey 指标键，必须能在本插件的 [SourcePlugin.metrics] 里找到
 * @property value 当日数值
 */
data class PluginMetric(
    val metricKey: String,
    val value: Double,
)
