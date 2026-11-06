package com.lifeboard.core.model

import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1.3 / M1.4：`SourceRef.Plugin` 分支与 key 生成解析的集中化。
 *
 * 重点验证三件事：
 * 1. 插件键全局唯一、可往返解析（历史 bug 的根因就是各处自拼 key、解不回来还静默降级）
 * 2. 插件键不会与内置源、自建源的键撞车
 * 3. 片段里含分隔符时**拒绝**，而不是生成一个解不回来的键
 */
class SourceRefPluginKeyTest {

    // ---------- 生成 ----------

    @Test
    fun `插件键格式为 PLUGIN_ 前缀加两段`() {
        assertEquals(
            "PLUGIN_dev.foo.mihealth:steps",
            SourceRef.pluginKey("dev.foo.mihealth", "steps"),
        )
    }

    @Test
    fun `插件分支的 key 走集中生成`() {
        val ref = SourceRef.Plugin(
            pluginId = "dev.foo.mihealth",
            metricKey = "sleep_minutes",
            displayName = "小米健康·睡眠",
            bucket = MetricBucket.SLEEP_MINUTES,
        )
        assertEquals("PLUGIN_dev.foo.mihealth:sleep_minutes", ref.key)
        assertTrue(ref.isPlugin)
        assertFalse(ref.isCustom)
        assertEquals(MetricBucket.SLEEP_MINUTES, ref.bucket)
    }

    // ---------- 往返解析 ----------

    @Test
    fun `插件键可以往返解析`() {
        val samples = listOf(
            "dev.foo.mihealth" to "steps",
            "com.example.plugin" to "count_by_rule",
            "a" to "b",
            "plugin_with_underscore" to "metric_with_underscore",
        )
        samples.forEach { (pluginId, metricKey) ->
            val key = SourceRef.pluginKey(pluginId, metricKey)
            assertEquals(pluginId to metricKey, SourceRef.parsePluginKey(key))
        }
    }

    @Test
    fun `pluginId 含点号不会破坏解析`() {
        // 用 ':' 分隔的理由：pluginId 是反向域名，必然含 '.'，
        // 若用 '.' 或 '_' 分隔，切分点就不唯一了
        val parsed = SourceRef.parsePluginKey("PLUGIN_dev.foo.mihealth:steps")
        assertEquals("dev.foo.mihealth", parsed?.first)
        assertEquals("steps", parsed?.second)
    }

    // ---------- 拒绝非法输入 ----------

    @Test
    fun `片段含分隔符时拒绝生成`() {
        assertThrows { SourceRef.pluginKey("dev:foo", "steps") }
        assertThrows { SourceRef.pluginKey("dev.foo", "steps:extra") }
    }

    @Test
    fun `片段为空时拒绝生成`() {
        assertThrows { SourceRef.pluginKey("", "steps") }
        assertThrows { SourceRef.pluginKey("dev.foo", "") }
    }

    @Test
    fun `解不出片段时返回 null 而不是猜`() {
        // 缺 metricKey
        assertNull(SourceRef.parsePluginKey("PLUGIN_dev.foo.mihealth:"))
        // 缺 pluginId
        assertNull(SourceRef.parsePluginKey("PLUGIN_:steps"))
        // 没有分隔符
        assertNull(SourceRef.parsePluginKey("PLUGIN_dev.foo.mihealth"))
        // 分隔符多于一个
        assertNull(SourceRef.parsePluginKey("PLUGIN_a:b:c"))
        // 不是插件键
        assertNull(SourceRef.parsePluginKey("SCREEN_TIME"))
        assertNull(SourceRef.parsePluginKey("CUSTOM_1"))
        assertNull(SourceRef.parsePluginKey(""))
    }

    // ---------- 与既有键不撞车 ----------

    @Test
    fun `插件键与内置源和自建源互不冲突`() {
        val builtinKeys = SourceType.entries.map { builtinRef(it).key }
        val customKey = SourceRef.Custom(1L, "自建", MetricBucket.COUNT).key
        val pluginKey = SourceRef.pluginKey("dev.foo", "steps")

        assertFalse(builtinKeys.contains(pluginKey))
        assertFalse(pluginKey == customKey)
        // 反向：内置/自建键不能被误认成插件键
        builtinKeys.forEach { assertNull(SourceRef.parsePluginKey(it)) }
        assertNull(SourceRef.parsePluginKey(customKey))
        // 自建键也不会被插件解析器吃掉
        assertNull(SourceRef.parseCustomId(pluginKey))
    }

    @Test
    fun `同一插件的不同指标键互不相同`() {
        val steps = SourceRef.pluginKey("dev.foo.mihealth", "steps")
        val sleep = SourceRef.pluginKey("dev.foo.mihealth", "sleep_minutes")
        assertFalse(steps == sleep)
    }

    @Test
    fun `不同插件的同名指标键也互不相同`() {
        // 这是"插件 A 的数据被当成插件 B 的"那类事故的防线
        val a = SourceRef.pluginKey("dev.a", "steps")
        val b = SourceRef.pluginKey("dev.b", "steps")
        assertFalse(a == b)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 预期行为
        }
    }
}
