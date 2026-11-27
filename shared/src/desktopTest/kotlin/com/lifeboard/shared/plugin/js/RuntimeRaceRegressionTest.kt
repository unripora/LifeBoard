package com.lifeboard.shared.plugin.js

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 审查发现的关闭、创建和跨代际租约竞态，使用屏障固定时序。 */
class RuntimeRaceRegressionTest {
    private val policy = PluginRuntimePolicy.of(PluginLifecycle.ON_DEMAND, idleTimeoutSec = 60)

    @Test
    fun shutdownAndRecreateDoNotReuseLeaseIdentity() {
        val sequence = AtomicInteger()
        val manager = PluginRuntimeManager(create = { _: String -> sequence.incrementAndGet() }, close = {})
        try {
            val old = assertIs<PluginRuntimeManager.AcquireResult.Ready>(manager.acquire("p", policy, true)).lease
            manager.shutdown("p")
            val current = assertIs<PluginRuntimeManager.AcquireResult.Ready>(manager.acquire("p", policy, true)).lease
            assertTrue(current.generation > old.generation)
            assertNull(old.withRuntime { it }, "旧租约不能访问重建实例")
            old.release()
            assertEquals(1, manager.leaseCount("p"), "旧租约不能归还新实例的票据")
            current.release()
        } finally {
            manager.close()
        }
    }

    @Test
    fun acquireDuringIdleCloseDoesNotLeaseClosingRuntime() {
        var now = 0L
        val enteredClose = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val manager = PluginRuntimeManager(
            create = { _: String -> Any() },
            close = {
                enteredClose.countDown()
                check(finishClose.await(5, TimeUnit.SECONDS))
            },
            autoEvictEnabled = true,
            nowMs = { now },
        )
        try {
            assertIs<PluginRuntimeManager.AcquireResult.Ready>(manager.acquire("p", policy, true)).lease.release()
            now = 61_000L
            val closing = pool.submit<Int> { manager.evictIdle() }
            assertTrue(enteredClose.await(5, TimeUnit.SECONDS))
            assertIs<PluginRuntimeManager.AcquireResult.Unavailable>(manager.acquire("p", policy, true))
            assertEquals(0, manager.leaseCount("p"))
            finishClose.countDown()
            assertEquals(1, closing.get(5, TimeUnit.SECONDS))
        } finally {
            finishClose.countDown()
            pool.shutdownNow()
            manager.close()
        }
    }

    @Test
    fun shutdownDuringCreationDiscardsAndClosesLateInstance() {
        val creating = CountDownLatch(1)
        val finishCreate = CountDownLatch(1)
        val closed = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor()
        val manager = PluginRuntimeManager(
            create = { _: String ->
                creating.countDown()
                check(finishCreate.await(5, TimeUnit.SECONDS))
                Any()
            },
            close = { closed.incrementAndGet(); Unit },
        )
        try {
            val future = pool.submit<PluginRuntimeManager.AcquireResult> { manager.acquire("p", policy, true) }
            assertTrue(creating.await(5, TimeUnit.SECONDS))
            manager.shutdown("p")
            finishCreate.countDown()
            assertIs<PluginRuntimeManager.AcquireResult.Unavailable>(future.get(5, TimeUnit.SECONDS))
            assertEquals(0, manager.liveCount())
            assertEquals(1, closed.get(), "撤权后迟到的实例必须关闭，不能泄漏或重新发布")
        } finally {
            finishCreate.countDown()
            pool.shutdownNow()
            manager.close()
        }
    }

    @Test
    fun concurrentReleaseReturnsOnlyOneTicket() {
        val manager = PluginRuntimeManager(create = { _: String -> Any() }, close = {})
        val pool = Executors.newFixedThreadPool(8)
        try {
            val first = assertIs<PluginRuntimeManager.AcquireResult.Ready>(manager.acquire("p", policy, true)).lease
            val second = assertIs<PluginRuntimeManager.AcquireResult.Ready>(manager.acquire("p", policy, true)).lease
            val start = CountDownLatch(1)
            val futures = (1..8).map { pool.submit { start.await(); first.release() } }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, manager.leaseCount("p"))
            second.release()
        } finally {
            pool.shutdownNow()
            manager.close()
        }
    }
}
