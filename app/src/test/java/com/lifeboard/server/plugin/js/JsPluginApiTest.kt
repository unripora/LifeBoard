package com.lifeboard.server.plugin.js

import com.lifeboard.shared.plugin.js.JsPluginApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住 [JsPluginApi] 的版本判定。
 *
 * 这组断言对应一个真实缺口：`manifest.json` 里的 `apiVersion` 曾被读出来
 * 存进对象，但**没有任何地方用它做判断**。后果是插件用了宿主还没有的函数时，
 * 不会在加载期被拦下，而是在采集时才以 `xxx is not a function` 炸掉，
 * 报错点离真正原因很远，用户和插件作者都看不出是版本不匹配。
 */
class JsPluginApiTest {

    /** 声明版本等于宿主：正常加载 */
    @Test
    fun sameVersion_isCompatible() {
        val verdict = JsPluginApi.evaluate(declared = 2, hostVersion = 2)

        assertTrue(verdict is JsPluginApi.Verdict.Compatible)
        assertEquals(2, (verdict as JsPluginApi.Verdict.Compatible).declared)
        assertTrue("声明了版本就不该被当成推断值", !verdict.assumed)
    }

    /** 老插件声明低版本：向下兼容，必须能加载 */
    @Test
    fun olderVersion_isCompatible() {
        val verdict = JsPluginApi.evaluate(declared = 1, hostVersion = 2)

        assertTrue(verdict is JsPluginApi.Verdict.Compatible)
        assertEquals(1, (verdict as JsPluginApi.Verdict.Compatible).declared)
    }

    /** 插件要求的版本高于宿主：拒绝，并给出可执行的原因 */
    @Test
    fun newerVersion_isRejectedWithActionableReason() {
        val verdict = JsPluginApi.evaluate(declared = 99, hostVersion = 2)

        assertTrue(verdict is JsPluginApi.Verdict.TooNew)
        val reason = (verdict as JsPluginApi.Verdict.TooNew).reason
        assertEquals(99, verdict.declared)
        // 原因必须告诉用户怎么办，不能只说「不兼容」
        assertTrue("原因里要提到升级：$reason", reason.contains("升级"))
    }

    /** 没写 apiVersion 的老包按 v1 处理，不能因为缺字段就整包拒绝 */
    @Test
    fun missingVersion_fallsBackToLegacy() {
        val verdict = JsPluginApi.evaluate(declared = 0, hostVersion = 2)

        assertTrue(verdict is JsPluginApi.Verdict.Compatible)
        verdict as JsPluginApi.Verdict.Compatible
        assertEquals(JsPluginApi.LEGACY, verdict.declared)
        assertTrue("缺字段要标成推断值，便于日志提示作者补上", verdict.assumed)
    }

    /** 负数属于非法值，同样按老包处理而不是拒绝 */
    @Test
    fun negativeVersion_treatedAsLegacy() {
        val verdict = JsPluginApi.evaluate(declared = -5, hostVersion = 2)

        assertTrue(verdict is JsPluginApi.Verdict.Compatible)
        assertEquals(JsPluginApi.LEGACY, (verdict as JsPluginApi.Verdict.Compatible).declared)
    }

    /**
     * 宿主支持的最高版本必须出现在能力表里。
     *
     * 漏记的后果是「当前版本有什么能力」在文档与代码里对不上，
     * 插件作者无法判断某个函数从哪一版开始可用。
     */
    @Test
    fun currentVersionIsDocumented() {
        assertTrue(
            "CURRENT=${JsPluginApi.CURRENT} 未在 CAPABILITIES 里登记",
            JsPluginApi.CAPABILITIES.containsKey(JsPluginApi.CURRENT),
        )
    }

    /** 新增能力只在最高版本上登记，历史版本不应被改动（否则老插件语义会变） */
    @Test
    fun capabilitiesAreContiguous() {
        val versions = JsPluginApi.CAPABILITIES.keys.sorted()
        assertEquals("能力表版本号必须从 1 连续递增", (1..versions.size).toList(), versions)
    }
}
