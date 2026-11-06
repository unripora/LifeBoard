package com.lifeboard.server.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.3 / M2.4：pluginId 安全校验。
 *
 * pluginId 来自 `.ext` 的 manifest，属于**外部输入**，直接拿去拼文件路径
 * 会被 `../` 穿越到别的插件目录甚至宿主 filesDir。
 * 这类问题在真机上表现为「插件能读到别人的数据」，但平时完全看不出来，
 * 所以用纯逻辑单测把它钉死。
 */
class PluginIdSafetyTest {

    @Test
    fun `反向域名式 pluginId 合法`() {
        assertTrue(HostPluginContext.isSafePluginId("dev.foo.mihealth"))
        assertTrue(HostPluginContext.isSafePluginId("com.example_plugin"))
        assertTrue(HostPluginContext.isSafePluginId("a"))
    }

    @Test
    fun `空与空白 pluginId 被拒绝`() {
        assertFalse(HostPluginContext.isSafePluginId(""))
        assertFalse(HostPluginContext.isSafePluginId("   "))
    }

    @Test
    fun `点号本身被拒绝但含点号的域名合法`() {
        // "." 与 ".." 是目录穿越的最小单元
        assertFalse(HostPluginContext.isSafePluginId("."))
        assertFalse(HostPluginContext.isSafePluginId(".."))
        // 正常反向域名不能因为含点就被误杀
        assertTrue(HostPluginContext.isSafePluginId("dev.foo"))
    }

    @Test
    fun `含路径分隔符的 pluginId 被拒绝`() {
        assertFalse(HostPluginContext.isSafePluginId("dev/foo"))
        assertFalse(HostPluginContext.isSafePluginId("dev\\foo"))
        assertFalse(HostPluginContext.isSafePluginId("../evil"))
        assertFalse(HostPluginContext.isSafePluginId("..\\evil"))
    }

    @Test
    fun `含冒号的 pluginId 被拒绝`() {
        // 冒号是插件 key 的片段分隔符，混进来会让 PLUGIN_xxx:yyy 解析歧义
        assertFalse(HostPluginContext.isSafePluginId("dev:foo"))
    }

    @Test
    fun `含空字符的 pluginId 被拒绝`() {
        assertFalse(HostPluginContext.isSafePluginId("dev\u0000foo"))
    }
}
