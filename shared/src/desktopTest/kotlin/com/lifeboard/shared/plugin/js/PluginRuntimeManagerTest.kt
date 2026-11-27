package com.lifeboard.shared.plugin.js

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 租约管理器测试（对应计划 8.1 / 8.5）。
 *
 * 覆盖几类最容易出错、且出错后果最严重的场景：
 * - 并发首次申请只创建一次实例
 * - 有租约时绝不回收（历史 bug 的回归）
 * - 归还后按策略回收，代际递增
 * - 旧代际租约不能污染新实例
 * - 强制停机不受租约保护
 * - 总开关关闭 / 旧包保活时不回收
 */
class PluginRuntimeManagerTest {

    /** 记录创建/关闭次数与「是否被执行过」的可观测实例 */
    private class FakeRuntime(val key: String) {
        @Volatile
        var closed = false
    }

    private class Recorder {
        val created = AtomicInteger(0)
        val closed = AtomicInteger(0)
    }

    private fun manager(
        autoEvict: Boolean = true,
        now: () -> Long = { System.currentTimeMillis() },
    ): Triple<PluginRuntimeManager<FakeRuntime>, Recorder, PluginRuntimeManager<FakeRuntime>> {
        val recorder = Recorder()
        val manager = PluginRuntimeManager<FakeRuntime>(
            create = { key ->
                recorder.created.incrementAndGet()
                FakeRuntime(key)
            },
            close = { rt ->
                rt.closed = true
                recorder.closed.incrementAndGet()
            },
            autoEvictEnabled = autoEvict,
            nowMs = now,
        )
        return Triple(manager, recorder, manager)
    }

    private val policy = PluginRuntimePolicy.of(PluginLifecycle.ON_DEMAND, idleTimeoutSec = 60)

    @Test
    fun concurrentFirstAcquiresCreateSingleInstance() {
        val (manager, recorder, _) = manager()
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val leases = java.util.concurrent.ConcurrentLinkedQueue<Lease>()
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                val result = manager.acquire("p1", policy, revocable = true)
                if (result is PluginRuntimeManager.AcquireResult.Ready) {
                    leases += result.lease
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发申请超时")
        pool.shutdownNow()

        assertEquals(threads, leases.size, "所有申请都应拿到租约")
        assertEquals(1, recorder.created.get(), "并发首次申请只能创建一次实例")
        assertEquals(threads, manager.leaseCount("p1"))
        leases.forEach { it.release() }
        assertEquals(0, manager.leaseCount("p1"))
    }

    @Test
    fun leasePreventsEvictionEvenAfterIdleThreshold() {
        var now = 0L
        val (manager, recorder, _) = manager(now = { now })
        val ready = manager.acquire("p1", policy, revocable = true)
        assertTrue(ready is PluginRuntimeManager.AcquireResult.Ready)

        now += 3_600_000
        assertEquals(0, manager.evictIdle(), "有租约时绝不能回收")
        assertEquals(0, recorder.closed.get())
        assertEquals(1, manager.liveCount())
    }

    @Test
    fun idleEvictionClosesAndNextAcquireRebuildsWithNewGeneration() {
        var now = 0L
        val (manager, recorder, _) = manager(now = { now })
        val first = manager.acquire("p1", policy, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        val generation1 = first.lease.generation
        first.lease.release()

        now += 61_000
        assertEquals(1, manager.evictIdle(), "空闲超过阈值应被回收")
        assertEquals(1, recorder.closed.get())
        assertEquals(0, manager.liveCount())

        val second = manager.acquire("p1", policy, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        assertTrue(second.lease.generation > generation1, "重建后的代际必须递增")
        assertEquals(2, recorder.created.get())
    }

    @Test
    fun staleLeaseCannotReleaseNewGeneration() {
        var now = 0L
        val (manager, _, _) = manager(now = { now })
        val first = manager.acquire("p1", policy, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        first.lease.release()

        now += 61_000
        manager.evictIdle()

        val second = manager.acquire("p1", policy, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        first.lease.release()
        assertEquals(1, manager.leaseCount("p1"), "旧代际的归还不能减新代际的计数")
        assertNull(first.lease.withRuntime { it })

        second.lease.release()
        assertEquals(0, manager.leaseCount("p1"))
    }

    @Test
    fun sessionScopedReleasesImmediatelyWithoutIdleWait() {
        val (manager, recorder, _) = manager()
        val scoped = PluginRuntimePolicy.of(PluginLifecycle.SESSION_SCOPED)
        val ready = manager.acquire("p1", scoped, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        ready.lease.release()
        assertEquals(1, manager.evictIdle())
        assertEquals(1, recorder.closed.get())
    }

    @Test
    fun residentIsNotEvictedBySweeper() {
        var now = 0L
        val (manager, _, _) = manager(now = { now })
        val resident = PluginRuntimePolicy.of(PluginLifecycle.RESIDENT)
        val ready = manager.acquire("p1", resident, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        ready.lease.release()
        now += 3_600_000
        assertEquals(0, manager.evictIdle(), "常驻实例不参与空闲回收")
    }

    @Test
    fun evictionDisabledWhenMasterSwitchOff() {
        var now = 0L
        val (manager, _, _) = manager(autoEvict = false, now = { now })
        val ready = manager.acquire("p1", policy, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        ready.lease.release()
        now += 3_600_000
        assertEquals(0, manager.evictIdle(), "总开关关闭时只登记不驱逐")
        assertEquals(1, manager.liveCount())
    }

    @Test
    fun nonRevocableEntrySurvivesEviction() {
        var now = 0L
        val (manager, recorder, _) = manager(now = { now })
        val scoped = PluginRuntimePolicy.of(PluginLifecycle.SESSION_SCOPED)
        val ready = manager.acquire("legacy", scoped, revocable = false) as PluginRuntimeManager.AcquireResult.Ready
        ready.lease.release()
        assertEquals(0, manager.evictIdle(), "旧包保活时不得自动回收")
        assertEquals(0, recorder.closed.get())
    }

    @Test
    fun shutdownIgnoresLeases() {
        val (manager, recorder, _) = manager()
        val ready = manager.acquire("p1", policy, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        assertTrue(manager.shutdown("p1"))
        assertEquals(1, recorder.closed.get())
        assertEquals(0, manager.liveCount())
        assertNull(ready.lease.withRuntime { it }, "停机后旧租约不得再访问实例")
    }

    @Test
    fun createFailureIsReportedAndDoesNotStick() {
        val recorder = Recorder()
        var fail = true
        val manager = PluginRuntimeManager<FakeRuntime>(
            create = { key ->
                if (fail) error("入口脚本抛错")
                FakeRuntime(key)
            },
            close = { recorder.closed.incrementAndGet() },
            autoEvictEnabled = true,
        )
        val failed = manager.acquire("p1", policy, revocable = true)
        assertTrue(failed is PluginRuntimeManager.AcquireResult.Unavailable, "创建失败必须返回明确原因")
        assertTrue((failed as PluginRuntimeManager.AcquireResult.Unavailable).reason.contains("入口脚本抛错"))
        assertEquals(0, manager.liveCount())

        fail = false
        val ok = manager.acquire("p1", policy, revocable = true)
        assertNotNull(ok as? PluginRuntimeManager.AcquireResult.Ready)
        manager.shutdown("p1")
    }

    @Test
    fun longCallIdlesFromReleaseNotFromStart() {
        var now = 0L
        val (manager, _, _) = manager(now = { now })
        val ready = manager.acquire("p1", policy, revocable = true) as PluginRuntimeManager.AcquireResult.Ready
        now += 600_000
        ready.lease.release()

        now += 30_000
        assertEquals(0, manager.evictIdle(), "空闲从归还时开始算，长调用刚结束不该被回收")

        now += 31_000
        assertEquals(1, manager.evictIdle())
    }
}
