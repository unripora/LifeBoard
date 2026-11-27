package com.lifeboard.shared.plugin.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 能力协商测试（对应计划 3.4 / M1.7 的集合一致性）。
 *
 * 关键约束：能力集合只列**已验证可用**项。列了没实现的能力，插件会以为能用
 * 而被放行；漏列已实现的能力，插件声明后会被误拒。
 */
class HostCapabilitiesTest {

    @Test
    fun knownCapabilitiesAreAccepted() {
        assertTrue(HostCapabilities.unmet(emptyList()).isEmpty(), "空声明不校验")
        assertTrue(
            HostCapabilities.unmet(listOf(HostCapabilities.CONTRACT_LOGIN_INTENT)).isEmpty(),
            "登录意图已打通，应可声明",
        )
        assertTrue(HostCapabilities.unmet(listOf(HostCapabilities.CONTRACT_ERROR_CODE)).isEmpty())
        assertTrue(HostCapabilities.unmet(listOf(HostCapabilities.CTX_FILES_WRITE)).isEmpty())
        assertTrue(HostCapabilities.unmet(listOf(HostCapabilities.CTX_CREDENTIALS)).isEmpty())
    }

    @Test
    fun unknownCapabilityIsReported() {
        val unmet = HostCapabilities.unmet(listOf("runtime.lifecycle", "not.a.real.capability"))
        assertEquals(2, unmet.size)
        assertTrue("runtime.lifecycle" in unmet)
    }

    /**
     * `runtime.lifecycle` 必须**不**在可用集合里。
     *
     * 宿主只做到「解析 manifest 的 lifecycle」，空闲回收默认关闭。
     * 若把它列进能力集，插件声明后就会误以为"空闲释放一定生效"，
     * 从而把关键状态只放在内存里——正好踩中本任务要消灭的坑。
     */
    @Test
    fun lifecycleIsNotAdvertisedWhileEvictionIsOff() {
        assertTrue(
            HostCapabilities.RUNTIME_LIFECYCLE !in HostCapabilities.ALL,
            "回收默认关闭期间不得宣称 runtime.lifecycle 可用",
        )
    }

    @Test
    fun rejectReasonMentionsMissingNames() {
        val reason = HostCapabilities.rejectReason(
            requires = listOf("a.b", "c.d"),
            unmet = listOf("a.b"),
        )
        assertTrue(reason.contains("a.b"), "拒载原因必须点名缺失能力")
        assertTrue(!reason.contains("c.d") || reason.contains("声明"), "原因应区分声明与缺失")
    }
}
