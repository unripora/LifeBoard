package com.lifeboard.plugin.mihealth

import org.json.JSONObject

/**
 * 小米健康采样点的解析规则。
 *
 * 抽成纯函数是为了可单测：真实接口的数值不在外层对象上，而在字符串字段 `value`
 * 里的**内层 JSON** 中（见 `external/mihealth-api/server.py` 的 `_parse_items`）。
 * 直接对外层取 `optDouble("value")` 只会拿到 NaN，表现为「登录成功但全 0 条」。
 */
internal object MiHealthParser {

    /**
     * 取出真正承载字段的对象。
     *
     * `value` 为空或不是合法 JSON 时退回原对象：部分接口（如 sport_records）
     * 本来就把字段放在外层，此时原对象才是正确的读取来源。
     */
    fun payload(point: JSONObject): JSONObject {
        val raw = point.optString("value")
        if (raw.isBlank()) return point
        return runCatching { JSONObject(raw) }.getOrDefault(point)
    }

    /** 采样点的数值：按候选字段顺序取第一个有效数值，全部缺失时返回 null */
    fun number(point: JSONObject, vararg names: String): Double? {
        val payload = payload(point)
        for (name in names) {
            // 内层字段优先，外层做兜底：两处都有时以内层为准
            if (payload.has(name)) {
                payload.optDouble(name, Double.NaN).takeIf { !it.isNaN() }?.let { return it }
            }
            if (point.has(name)) {
                point.optDouble(name, Double.NaN).takeIf { !it.isNaN() }?.let { return it }
            }
        }
        return null
    }

    /** 取一个时间戳（毫秒）：兼容秒级时间戳与字符串数字 */
    fun timestamp(point: JSONObject, vararg names: String): Long? {
        val payload = payload(point)
        for (name in names) {
            val raw = when {
                payload.has(name) -> payload.opt(name)
                point.has(name) -> point.opt(name)
                else -> null
            } ?: continue
            val value = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            } ?: continue
            if (value > 0) return normalizeMillis(value)
        }
        return null
    }

    /**
     * 秒级时间戳转毫秒。
     *
     * 判定阈值取 1e11：毫秒时间戳在 1973 年就超过它，
     * 而秒级要到公元 5138 年才会超过，正常健康数据不可能落错区间。
     */
    private fun normalizeMillis(value: Long): Long =
        if (value < SECOND_TIMESTAMP_LIMIT) value * 1000 else value

    /** 秒级与毫秒级时间戳的分界 */
    private const val SECOND_TIMESTAMP_LIMIT = 100_000_000_000L
}
