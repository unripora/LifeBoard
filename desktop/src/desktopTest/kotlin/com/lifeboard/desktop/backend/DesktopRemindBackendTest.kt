package com.lifeboard.desktop.backend

import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.TimeSource
import com.lifeboard.core.model.builtinRef
import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.core.util.Dates
import com.lifeboard.desktop.schedule.DesktopScheduler
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.shared.db.DatabaseDriverFactory
import com.lifeboard.shared.db.DatabaseStores
import com.lifeboard.shared.db.openDatabase
import com.lifeboard.shared.platform.DeliveryLevel
import com.lifeboard.shared.platform.NotificationRequest
import com.lifeboard.shared.platform.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class DesktopRemindBackendTest {
    @Test
    fun rescheduleAndTaskCancellationPreserveCollectionRefresh() = runBlocking {
        withFixture { f ->
            val refresh = "collect:refresh"
            f.scheduler.register(refresh) {}
            f.scheduler.scheduleRepeating(refresh, 60)
            val id = f.backend.createTask(task())
            assertTrue(f.scheduler.isScheduled("remind:$id:0"))
            repeat(2) { f.backend.rescheduleAll() }
            f.backend.cancelTask(id)

            assertTrue(f.scheduler.isScheduled(refresh))
            assertFalse(f.scheduler.isScheduled("remind:$id:0"))
            assertTrue(f.scheduler.isScheduled("remind:midnight-watchdog"))
            assertTrue(f.scheduler.isScheduled("remind:date-watchdog"))
        }
    }

    @Test
    fun inactiveSundayStillRegistersMondayReminderAfterDateChange() = runBlocking {
        withFixture { f ->
            val id = f.backend.createTask(task().copy(schedule = ScheduleRule(
                repeatType = RepeatType.WEEKLY,
                weekdays = setOf(DayOfWeek.MONDAY.value),
                windows = listOf(TimeWindow(9 * 60, 9 * 60 + 30)),
            )))
            assertFalse(f.scheduler.isScheduled("remind:$id:0"))
            assertTrue(f.scheduler.isScheduled("remind:midnight-watchdog"))
            assertFalse(f.backend.refreshForDateChange())

            f.clock.advanceDays(1)
            assertTrue(f.backend.refreshForDateChange())
            assertTrue(f.scheduler.isScheduled("remind:$id:0"))
            assertTrue(f.scheduler.isScheduled("remind:midnight-watchdog"))
            assertFalse(f.backend.refreshForDateChange())
        }
    }

    @Test
    fun sleepAcrossDaysDropsOldCallbackAndEvaluatesCurrentDateOnly() = runBlocking {
        withFixture { f ->
            val id = f.backend.createTask(task())
            val plannedDate = f.clock.date()
            // 不调用午夜回调，模拟休眠跨过多个日期后旧定时器才恢复。
            f.clock.advanceDays(2)
            f.backend.onTriggered(id, plannedDate)
            assertEquals(0, f.notifier.requests.size)
            assertNull(f.stores.taskStore.getRecord(id, f.clock.date().toString()))
            assertFalse(f.backend.refreshForDateChange())
            assertTrue(f.scheduler.isScheduled("remind:$id:0"))

            f.backend.onTriggered(id, f.clock.date())
            assertEquals(1, f.notifier.requests.size)
            assertNotNull(f.stores.taskStore.getRecord(id, f.clock.date().toString()))
        }
    }

    @Test
    fun disabledOrDeletedTaskDoesNotDeliverQueuedCallback() = runBlocking {
        withFixture { f ->
            val id = f.backend.createTask(task())
            val saved = f.stores.taskStore.getById(id)!!
            f.stores.taskStore.save(saved.copy(enabled = false))
            f.backend.rescheduleAll()
            assertFalse(f.scheduler.isScheduled("remind:$id:0"))
            f.backend.onTriggered(id, f.clock.date())
            f.backend.deleteTask(id)
            f.backend.onTriggered(id, f.clock.date())
            assertTrue(f.notifier.requests.isEmpty())
        }
    }

    @Test
    fun emptyDatabaseKeepsRolloverAliveAcrossMoreThanOneDay() = runBlocking {
        withFixture { f ->
            f.backend.rescheduleAll()
            repeat(2) {
                f.clock.advanceDays(1)
                assertTrue(f.backend.refreshForDateChange())
                assertTrue(f.scheduler.isScheduled("remind:midnight-watchdog"))
                assertTrue(f.scheduler.isScheduled("remind:date-watchdog"))
            }
        }
    }

    @Test
    fun failedSameDayRebuildIsRetriedByDateWatchdog() = runBlocking {
        withFixture { f ->
            val id = f.backend.createTask(task())
            java.sql.DriverManager.getConnection("jdbc:sqlite:${f.databaseFile.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("ALTER TABLE task RENAME TO task_offline")
                    try {
                        assertTrue(runCatching { f.backend.rescheduleAll() }.isFailure)
                        assertFalse(f.scheduler.isScheduled("remind:$id:0"))
                        assertTrue(f.scheduler.isScheduled("remind:date-watchdog"))
                    } finally {
                        statement.executeUpdate("ALTER TABLE task_offline RENAME TO task")
                    }
                }
            }
            assertTrue(f.backend.refreshForDateChange())
            assertTrue(f.scheduler.isScheduled("remind:$id:0"))
            assertFalse(f.backend.refreshForDateChange())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.scheduler.shutdown()
            fixture.scope.cancel()
        }
    }

    private class Fixture {
        val clock = MutableClock()
        val scheduler = DesktopScheduler(clock::millis)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = RecordingNotifier()
        private val dir = Files.createTempDirectory("lifeboard-remind-test").toFile().apply { deleteOnExit() }
        val databaseFile = File(dir, "test.db")
        val stores = DatabaseStores(openDatabase(DatabaseDriverFactory(), databaseFile.absolutePath))
        val backend = DesktopRemindBackend(stores, scheduler, notifier, scope, clock)
    }

    private class MutableClock : Clock() {
        private var value = LocalDate.of(2030, 1, 1)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            .atTime(8, 0).atZone(Dates.zone).toInstant()
        override fun getZone(): ZoneId = Dates.zone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
        override fun instant(): Instant = value
        fun date(): LocalDate = value.atZone(zone).toLocalDate()
        fun advanceDays(days: Long) {
            value = value.atZone(zone).plusDays(days).toInstant()
        }
    }

    private class RecordingNotifier : Notifier {
        val requests = mutableListOf<NotificationRequest>()
        override suspend fun notify(request: NotificationRequest): DeliveryLevel {
            requests += request
            return DeliveryLevel.WEAK
        }
        override suspend fun cancel(id: Int) = Unit
        override suspend fun isPermitted(): Boolean = true
        override suspend fun openPermissionSettings() = Unit
    }

    private fun task() = TaskConfig(
        title = "test reminder",
        source = builtinRef(SourceType.SCREEN_TIME),
        metricBucket = MetricBucket.DURATION_MINUTES,
        threshold = 30.0,
        remindLevel = RemindLevel.WEAK,
        timeSource = TimeSource.INDEPENDENT,
        schedule = ScheduleRule(windows = listOf(TimeWindow(9 * 60, 9 * 60 + 30))),
        windowOffsetMinutes = 5,
    )
}
