package com.lifeboard.desktop.schedule

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSchedulerTest {
    @Test
    fun replacingQueuedImmediateTimerOnlyInvokesNewestCallbackOnce() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scheduler = DesktopScheduler(nowMillis = { 100L }, dispatcher = dispatcher)
        val calls = mutableListOf<String>()
        try {
            scheduler.register("same") { calls += "old" }
            scheduler.scheduleAt("same", 99L)
            scheduler.register("same") { calls += "new" }
            scheduler.scheduleAt("same", 100L)
            assertTrue(scheduler.isScheduled("same"))
            dispatcher.drain()

            assertEquals(listOf("new"), calls)
            assertFalse(scheduler.isScheduled("same"))
        } finally {
            scheduler.shutdown()
            dispatcher.drain()
        }
    }

    @Test
    fun immediateTimerCanBeCancelledBeforeDispatch() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scheduler = DesktopScheduler(nowMillis = { 100L }, dispatcher = dispatcher)
        var calls = 0
        try {
            scheduler.register("cancelled") { calls++ }
            scheduler.scheduleAt("cancelled", 90L)
            scheduler.cancel("cancelled")
            dispatcher.drain()
            assertEquals(0, calls)
            assertFalse(scheduler.isScheduled("cancelled"))
        } finally {
            scheduler.shutdown()
            dispatcher.drain()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            work.addLast(block)
        }
        fun drain() {
            while (work.isNotEmpty()) work.removeFirst().run()
        }
    }
}
