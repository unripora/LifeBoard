package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.Align
import com.lifeboard.plugin.api.BarChartSpec
import com.lifeboard.plugin.api.ChartPoint
import com.lifeboard.plugin.api.ChartSpec
import com.lifeboard.plugin.api.Emphasis
import com.lifeboard.plugin.api.KeyValue
import com.lifeboard.plugin.api.LineChartSpec
import com.lifeboard.plugin.api.ProgressRingSpec
import com.lifeboard.plugin.api.SelectOption
import com.lifeboard.plugin.api.Spacing
import com.lifeboard.plugin.api.TableColumn
import com.lifeboard.plugin.api.UnsupportedNode
import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject

/**
 * 节点载荷里「小类型」的解析：表格列/行、键值对、选项、图表规格、枚举。
 *
 * ## 为什么单独一个文件
 *
 * 这些是**叶子值**的解析（一行表格、一个数据点），跟「一个节点该长什么样」
 * 是两件事。分出来后 [JsUiNodeParser] 只剩「type → 节点」的分支表，
 * 新增节点类型时不必在一屏小函数之间来回找。
 *
 * ## 共同约定
 *
 * 一律**不抛异常**：结构不完整的条目被丢弃或退回默认值，让宿主画出一个
 * 少了几项的界面，而不是整页打不开。
 */
internal object JsNodePayloadParser {

    fun PluginJsonArray?.toTableColumns(): List<TableColumn> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            TableColumn(
                title = o.optString("title"),
                weight = o.optDouble("weight", 0.0).toFloat(),
                align = o.optString("align").toAlign(),
            )
        }
    }

    fun PluginJsonArray?.toTableRows(): List<List<String>> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), JsTypeConverter.MAX_TABLE_ROWS)).mapNotNull { i ->
            val row = optJSONArray(i) ?: return@mapNotNull null
            (0 until row.length()).map { j -> row.optStringOrNull(j).orEmpty() }
        }
    }

    fun PluginJsonArray?.toKeyValues(): List<KeyValue> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            KeyValue(
                key = o.optString("key"),
                value = o.optString("value"),
                tone = o.optString("tone").toTone(),
            )
        }
    }

    fun PluginJsonArray?.toSelectOptions(): List<SelectOption> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            // 没有 value 的选项点了也没人认，整条丢弃
            val value = o.optString("value").ifBlank { return@mapNotNull null }
            SelectOption(
                value = value,
                label = o.optString("label"),
                // payload 也是机器字段：超限就丢（否则回传时会被截成一个指向不存在目标的串）
                payload = o.optStringOrNull("payload")
                    ?.takeIf { it.utf8Size() <= JsTypeConverter.MAX_MACHINE_FIELD_BYTES },
            )
        }
    }

    /** 解析图表的 kind；认不出返回 null，由调用方降级为 [UnsupportedNode] */
    fun toChartSpec(o: PluginJsonObject): ChartSpec? = when (o.optString("kind")) {
        "line" -> LineChartSpec(
            points = o.optJSONArray("points").toChartPoints(),
            unit = o.optString("unit"),
        )
        "bar" -> BarChartSpec(
            bars = o.optJSONArray("bars").toChartPoints(),
            unit = o.optString("unit"),
            highlightIndex = o.optInt("highlightIndex", -1),
        )
        "progressRing" -> ProgressRingSpec(
            fraction = o.optDouble("fraction", 0.0).toFloat(),
            centerText = o.optString("centerText"),
            subtitle = o.optStringOrNull("subtitle"),
        )
        else -> null
    }

    private fun PluginJsonArray?.toChartPoints(): List<ChartPoint> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            ChartPoint(label = o.optString("label"), value = o.optDouble("value", 0.0))
        }
    }

    fun String.toSpacing(): Spacing = when (lowercase()) {
        "none" -> Spacing.NONE
        "compact" -> Spacing.COMPACT
        "loose" -> Spacing.LOOSE
        else -> Spacing.NORMAL
    }

    fun String.toEmphasis(): Emphasis = when (lowercase()) {
        "heading" -> Emphasis.HEADING
        "caption" -> Emphasis.CAPTION
        else -> Emphasis.BODY
    }

    fun String.toAlign(): Align = when (lowercase()) {
        "center" -> Align.CENTER
        "end" -> Align.END
        else -> Align.START
    }
}
