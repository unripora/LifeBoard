package com.lifeboard.shared.plugin.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 盯住 `optStringOrNull(index)` 的跨端一致语义：索引处不是 JSON 字符串就返回 null。
 *
 * 桌面侧曾用 `(element as? JsonPrimitive)?.content` 实现，这会把 JSON null
 * 读成字符串 "null"、把数字 123 读成 "123"，而 Android 侧的
 * `opt(index) as? String` 返回 null。插件跟 JS 桥接在同一份数据上就会两端表现不同。
 */
class PluginJsonArrayTest {

    @Test
    fun 字符串原样返回() {
        val array = PluginJsonArray("""["abc", 1]""")
        assertEquals("abc", array.optStringOrNull(0))
    }

    @Test
    fun 数字与布尔返回null() {
        val array = PluginJsonArray("""[123, true]""")
        assertNull(array.optStringOrNull(0))
        assertNull(array.optStringOrNull(1))
    }

    @Test
    fun JSON_null与越界返回null() {
        val array = PluginJsonArray("""[null, "x"]""")
        assertNull(array.optStringOrNull(0))
        assertNull(array.optStringOrNull(5))
    }
}
