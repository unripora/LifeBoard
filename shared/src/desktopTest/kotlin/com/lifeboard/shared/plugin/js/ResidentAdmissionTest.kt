package com.lifeboard.shared.plugin.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 常驻准入与 LRU 降级测试（对应计划 4.4）。
 *
 * 核心约束：**名额满了不能去关正在使用的实例**。宁可拒绝新增常驻，
 * 也不能让一个活跃会话被抢占——这正是「LRU 断开连接」的风险来源。
 */
class ResidentAdmissionTest {

    private class Fake(val key: String)

    private fun manager(now: () -> Long = { System.currentTimeMillis() }): PluginRuntimeManager<Fake> =
        PluginRuntimeManager(
            create = { Fake(it) },
            close = { },
            autoEvictEnabled = true,
            nowMs = now,
        )

    private fun residentPolicy() = PluginRuntimePolicy.of(PluginLifecycle.RESIDENT)

    @Test
    fun residentCountsAreCappedAndIdleOneIsDegraded() {
        var now = 0L
        val m = manager { now }
        val grants = mutableSetOf<String>()
        val cap = 2

        fun admit(id: String): PluginLifecycle {
            if (id in grants) return PluginLifecycle.RESIDENT
            if (grants.size < cap) {
                grants += id
                val r = m.acquire(id, residentPolicy(), revocable = true)
                (r as PluginRuntimeManager.AcquireResult.Ready).lease.release()
                return PluginLifecycle.RESIDENT
            }
            val victim = m.residentIdleLru()?.takeIf { it in grants && it != id }
            if (victim != null) {
                grants -= victim
                m.updatePolicy(
                    victim,
                    PluginRuntimePolicy.of(
                        PluginLifecycle.RESIDENT,
                        effective = PluginLifecycle.ON_DEMAND,
                        degradeReason = "常驻名额已满",
                    ),
                )
                grants += id
                val r = m.acquire(id, residentPolicy(), revocable = true)
                (r as PluginRuntimeManager.AcquireResult.Ready).lease.release()
                return PluginLifecycle.RESIDENT
            }
            val degraded = residentPolicy().copy(
                effective = PluginLifecycle.ON_DEMAND,
                degradeReason = "名额已满且均在使用中",
            )
            val r = m.acquire(id, degraded, revocable = true)
            (r as PluginRuntimeManager.AcquireResult.Ready).lease.release()
            return PluginLifecycle.ON_DEMAND
        }

        assertEquals(PluginLifecycle.RESIDENT, admit("a"))
        now += 10
        assertEquals(PluginLifecycle.RESIDENT, admit("b"))
        now += 10
        // 名额已满，a 空闲最久 → 降级 a，c 获准
        assertEquals(PluginLifecycle.RESIDENT, admit("c"))
        assertTrue("a" !in grants && "b" in grants && "c" in grants)
    }

    @Test
    fun activeResidentIsNeverPreempted() {
        val m = manager()
        val readyA = m.acquire("a", residentPolicy(), revocable = true)
            as PluginRuntimeManager.AcquireResult.Ready
        // a 正被使用（持租约）→ 不是降级候选
        assertNull(m.residentIdleLru(), "活跃实例不能作为 LRU 候选")

        val readyB = m.acquire("b", residentPolicy(), revocable = true)
            as PluginRuntimeManager.AcquireResult.Ready
        assertNull(m.residentIdleLru(), "都在使用时没有候选")

        readyA.lease.release()
        assertEquals("a", m.residentIdleLru(), "释放后才成为候选")
        readyB.lease.release()
    }

    @Test
    fun degradedResidentBecomesEvictable() {
        var now = 0L
        val m = manager { now }
        val r = m.acquire("a", residentPolicy(), revocable = true)
        (r as PluginRuntimeManager.AcquireResult.Ready).lease.release()
        // 常驻不参与空闲回收
        now += 3_600_000
        assertEquals(0, m.evictIdle())

        // 降级为按需后，空闲阈值一到即可回收
        m.updatePolicy(
            "a",
            PluginRuntimePolicy.of(
                PluginLifecycle.RESIDENT,
                effective = PluginLifecycle.ON_DEMAND,
                degradeReason = "常驻名额已满",
            ),
        )
        assertEquals(1, m.evictIdle())
        assertEquals(0, m.liveCount())
    }

    @Test
    fun residentKeysReflectsEffectivePolicy() {
        val m = manager()
        val r = m.acquire("a", residentPolicy(), revocable = true)
        (r as PluginRuntimeManager.AcquireResult.Ready).lease.release()
        assertEquals(listOf("a"), m.residentKeys())

        m.updatePolicy(
            "a",
            PluginRuntimePolicy.of(PluginLifecycle.RESIDENT, effective = PluginLifecycle.ON_DEMAND),
        )
        assertTrue(m.residentKeys().isEmpty())
    }
}
