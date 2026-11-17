package com.lifeboard.desktop.screentime

import com.lifeboard.shared.db.DatabaseDriverFactory
import com.lifeboard.shared.db.DatabaseStores
import com.lifeboard.shared.db.openDatabase
import com.lifeboard.shared.repository.MetricStore
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DesktopScreenTimeBackend] 的累计与落库语义测试。
 *
 * ## 这些用例在防什么
 *
 * 屏幕时长的实现有三处极易写错，而写错之后**都不会报错**，
 * 只会让用户看到一个说不通的数字：
 *
 * 1. 每次 tick 都 `saveMetric(增量)` —— `metric` 表是 `INSERT OR REPLACE`，
 *    结果库里永远只剩最后一次增量
 * 2. 跨天不清零 —— 昨天的时长累到今天的账上
 * 3. 重启后不回填 —— 早上用了一小时，中午重启，今天只剩下午的
 *
 * 三条都是「数字不对但不报错」，只能靠测试钉住。
 */
class DesktopScreenTimeBackendTest {

    private val dbFile = File.createTempFile("screentime-test-", ".db").apply { delete() }
    private lateinit var stores: DatabaseStores

    private fun newBackend(
        probe: FrontWindowProbe,
        clock: () -> Long = { 0L },
        today: () -> String = { TODAY },
    ): DesktopScreenTimeBackend {
        stores = DatabaseStores(
            openDatabase(DatabaseDriverFactory(migrate = true), dbFile.absolutePath),
        )
        return DesktopScreenTimeBackend(
            metricStore = stores.metricStore,
            frontWindow = probe,
            clock = clock,
            todayProvider = today,
        )
    }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    // ------------------------------------------------------------------
    // 累计语义
    // ------------------------------------------------------------------

    @Test
    fun `两次采样之间的时间记给上一次的前台应用`() = runBlocking {
        // 0 秒时前台是 A，60 秒时前台是 B：
        // 这 60 秒应该记给 A（人是看着 A 过来的），而不是 B
        var now = 0L
        var sample: ForegroundSample? = ForegroundSample("a.exe", "A")
        val backend = newBackend(FakeProbe { sample }, clock = { now })

        backend.tick()          // 第一次只记「现在是谁」，不结算
        now = 60_000L
        sample = ForegroundSample("b.exe", "B")
        backend.tick()          // 结算：a.exe 得 1 分钟

        val usage = backend.todayAppUsage()
        assertEquals(1, usage.size, "只应有一个应用产生时长")
        assertEquals("a.exe", usage[0].packageName)
        assertEquals(1.0, usage[0].minutes, 0.001)
    }

    @Test
    fun `同一天多次采样是累加而不是覆盖`() = runBlocking {
        // 这是最容易写错的地方：每次 tick 写库必须是「当天累计总量」，
        // 而不是「本次增量」，否则后一次会把前一次盖掉
        var now = 0L
        val probe = FakeProbe { ForegroundSample("a.exe", "A") }
        val backend = newBackend(probe, clock = { now })

        backend.tick()
        now = 60_000L
        backend.tick()      // a.exe: 1 分钟
        now = 180_000L
        backend.tick()      // a.exe: +2 分钟 = 3 分钟
        assertEquals(3.0, backend.todayMinutes(), 0.001)

        // 库里的值也必须跟上，否则趋势页会显示 1 分钟
        val stored = stores.metricStore.getMetric(
            sourceKey = SCREEN_TIME_KEY,
            date = TODAY,
            bucket = "DURATION_MINUTES",
        )
        assertEquals(3.0, stored?.value ?: 0.0, 0.001)
    }

    @Test
    fun `空闲期间那段时间不计入任何应用`() = runBlocking {
        // 时间线：0s 前台 A（1 分钟）→ 60s 空闲 → 180s 仍空闲
        // 60s→180s 这 2 分钟没有归属，不该算给 A
        var now = 0L
        var sample: ForegroundSample? = ForegroundSample("a.exe", "A")
        val backend = newBackend(FakeProbe { sample }, clock = { now })

        backend.tick()            // 记下「现在是谁」，不结算
        now = 60_000L
        backend.tick()            // 结算：a.exe 得 1 分钟
        assertEquals(1.0, backend.todayMinutes(), 0.001)

        sample = null             // 用户走开了
        now = 180_000L
        backend.tick()            // 上一拍是 A、这一拍空闲：这 2 分钟仍归 A

        now = 300_000L
        backend.tick()            // 上一拍已空闲，这 2 分钟没人认领，不记

        // 总计仍是 1 + 2 = 3 分钟；第三段没有被算进任何应用
        assertEquals(3.0, backend.todayMinutes(), 0.001)
        assertEquals(1, backend.todayAppUsage().size, "空闲段不该凭空造出一个新应用")
        assertEquals("a.exe", backend.todayAppUsage()[0].packageName)
    }

    @Test
    fun `跨天后昨天的时长不会算进今天`() = runBlocking {
        var now = 0L
        var date = "2026-03-04"
        val probe = FakeProbe { ForegroundSample("a.exe", "A") }
        val backend = newBackend(probe, clock = { now }, today = { date })

        backend.tick()
        now = 60_000L
        backend.tick()                       // 3-04 记 1 分钟
        assertEquals(1.0, backend.todayMinutes(), 0.001)

        date = "2026-03-05"
        now = 120_000L
        backend.tick()                       // 换天了：内存清零，这一拍不产生时长

        assertEquals(0.0, backend.todayMinutes(), 0.001)
    }

    @Test
    fun `进程重启后从库里回填当天已有时长`() = runBlocking {
        // 第一次运行：记 2 分钟
        var now = 0L
        val probe = FakeProbe { ForegroundSample("a.exe", "A") }
        val first = newBackend(probe, clock = { now })
        first.tick()
        now = 120_000L
        first.tick()
        assertEquals(2.0, first.todayMinutes(), 0.001)

        // 模拟重启：新建一个后端实例，指向同一个库
        val second = DesktopScreenTimeBackend(
            metricStore = stores.metricStore,
            frontWindow = FakeProbe { ForegroundSample("b.exe", "B") },
            clock = { 300_000L },
            todayProvider = { TODAY },
        )
        // 重启后读到的今日总时长必须包含重启前那 2 分钟
        assertEquals(2.0, second.todayMinutes(), 0.001)
    }

    @Test
    fun `本周曲线把缺数据的日期补成 0 而不是跳过`() = runBlocking {
        val probe = FakeProbe { null }
        val backend = newBackend(probe)

        val week = backend.thisWeekDaily()
        // 一周固定 7 天。缺的日子若被跳过，柱状图会把不相邻的两天画在一起，
        // 用户会以为「中间那天没数据是程序坏了」
        assertEquals(7, week.size, "本周曲线必须是连续的 7 天")
        assertTrue(week.all { it.minutes == 0.0 })
        // 日期必须递增且连续
        assertEquals(week.map { it.date }, week.map { it.date }.sorted())
    }

    @Test
    fun `没有前台窗口权限时 hasPermission 为 false`() = runBlocking {
        val backend = newBackend(FakeProbe(available = false) { null })
        assertEquals(false, backend.hasPermission())
    }

    private companion object {
        const val TODAY = "2026-03-04"
        const val SCREEN_TIME_KEY = "SCREEN_TIME"
    }
}

/** 可控的前台窗口桩：测试要精确决定「这一刻前台是谁」 */
private class FakeProbe(
    override val available: Boolean = true,
    val sampler: () -> ForegroundSample?,
) : FrontWindowProbe {
    override fun sample(): ForegroundSample? = sampler()
}
