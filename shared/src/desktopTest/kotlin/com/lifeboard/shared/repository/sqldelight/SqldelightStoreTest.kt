package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.TimeSource
import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.db.DatabaseDriverFactory
import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.openDatabase
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.model.AppUsageRecord
import com.lifeboard.shared.repository.model.CustomSourceRecord
import com.lifeboard.shared.repository.model.DashboardCard
import com.lifeboard.shared.repository.model.DashboardTab
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-2.4 验收：SQLDelight Store 的行为与 Room 版等价。
 *
 * 这一层不比对 SQL 文本，而是**跑真实读写**，钉住那些「写错了不报错、
 * 只在特定数据下出错」的语义：
 *
 * - `save` 的 REPLACE 语义（传已存在 id 时整行替换）
 * - `deleteById` 的级联顺序（任务、记录、连击一起清）
 * - `insertCard` 的 IGNORE 语义（重复 id 返回 -1 且不覆盖）
 * - 外键 CASCADE（删页签带走卡片）
 * - 老数据回落（`windows` 为空串时用旧的单时段）
 * - 观测 Flow 能收到写入后的值
 */
class SqldelightStoreTest {

    private val dbFile = File.createTempFile("store-", ".db").apply { delete() }

    /** 测试里没有自建源，查找器恒定返回 null；内置源不需要它 */
    private val lookup = SourceKeys.CustomSourceLookup { null }

    private lateinit var db: LifeBoardDb

    private fun open(): LifeBoardDb {
        db = openDatabase(DatabaseDriverFactory(migrate = true), dbFile.absolutePath)
        return db
    }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    private fun sampleTask(
        id: Long = 0L,
        title: String = "每日步数",
        enabled: Boolean = true,
        windows: List<TimeWindow> = listOf(TimeWindow(540, 570)),
    ) = TaskConfig(
        id = id,
        title = title,
        source = SourceRef.Builtin("SCREEN_TIME", "屏幕使用", MetricBucket.COUNT),
        metricBucket = MetricBucket.COUNT,
        threshold = 30.0,
        weight = 1.0,
        remindLevel = RemindLevel.WEAK,
        windowStartMinute = 540,
        windowEndMinute = 570,
        schedule = ScheduleRule(
            windows = windows,
            repeatType = RepeatType.DAILY,
        ),
        ruleId = 0L,
        enabled = enabled,
        timeSource = TimeSource.FOLLOW_RULE,
        doneRemindLevel = RemindLevel.NONE,
        doneToastEnabled = true,
        windowOffsetMinutes = 5,
    )

    // ---- TaskStore ----

    @Test
    fun 任务增删改查往返一致() = runBlocking {
        val store = SqldelightTaskStore(open(), lookup)

        val id = store.save(sampleTask())
        assertTrue(id > 0, "新增应返回自增 id，实际 $id")

        val loaded = assertNotNull(store.getById(id))
        assertEquals("每日步数", loaded.title)
        assertEquals(SourceRef.Builtin::class, loaded.source::class)
        assertEquals(1, loaded.schedule.windows.size)
        assertEquals(540, loaded.schedule.windows.first().startMinute)
        assertEquals(570, loaded.schedule.windows.first().endMinute)
        assertEquals(TimeSource.FOLLOW_RULE, loaded.timeSource)
    }

    @Test
    fun save用REPLACE语义整行替换() = runBlocking {
        val store = SqldelightTaskStore(open(), lookup)
        val id = store.save(sampleTask())

        // 同 id 再存一次，标题应变
        store.save(sampleTask(id = id, title = "改过的标题"))
        assertEquals("改过的标题", store.getById(id)?.title)
        assertEquals(1, store.getAll().size, "REPLACE 不应产生第二行")
    }

    @Test
    fun 未启用的任务不出现在getEnabled里() = runBlocking {
        val store = SqldelightTaskStore(open(), lookup)
        store.save(sampleTask(title = "启用", enabled = true))
        store.save(sampleTask(title = "停用", enabled = false))

        assertEquals(2, store.getAll().size)
        assertEquals(listOf("启用"), store.getEnabled().map { it.title })
    }

    @Test
    fun 删除任务同时清掉记录与连击() = runBlocking {
        val store = SqldelightTaskStore(open(), lookup)
        val id = store.save(sampleTask())
        store.saveRecord(
            DailyRecord(taskId = id, date = "2026-11-13", value = 1.0, completed = true, score = 10.0),
        )
        store.saveStreak(StreakInfo(taskId = id, currentStreak = 3, bestStreak = 5, lastDate = "2026-11-13"))

        assertNotNull(store.getRecord(id, "2026-11-13"))
        assertNotNull(store.getStreak(id))

        store.deleteById(id)

        assertNull(store.getById(id))
        assertNull(store.getRecord(id, "2026-11-13"), "任务删除后记录必须一起清，否则趋势页会读到孤儿行")
        assertNull(store.getStreak(id))
    }

    @Test
    fun 记录按日期与区间查询() = runBlocking {
        val store = SqldelightTaskStore(open(), lookup)
        val id = store.save(sampleTask())
        listOf("2026-11-11", "2026-11-12", "2026-11-13").forEach { d ->
            store.saveRecord(DailyRecord(taskId = id, date = d, value = 1.0, completed = true, score = 1.0))
        }

        assertEquals(1, store.getRecordsByDate("2026-11-12").size)
        val range = store.getRecordsInRange("2026-11-11", "2026-11-13")
        assertEquals(3, range.size)
        assertEquals("2026-11-11", range.first().date, "区间结果必须按日期升序")
    }

    @Test
    fun 记录同键重复写入是覆盖() = runBlocking {
        val store = SqldelightTaskStore(open(), lookup)
        val id = store.save(sampleTask())
        store.saveRecord(DailyRecord(taskId = id, date = "2026-11-13", value = 1.0, completed = false, score = 0.0))
        store.saveRecord(DailyRecord(taskId = id, date = "2026-11-13", value = 2.0, completed = true, score = 5.0))

        val row = assertNotNull(store.getRecord(id, "2026-11-13"))
        assertEquals(2.0, row.value)
        assertTrue(row.completed)
        assertEquals(1, store.getRecordsByDate("2026-11-13").size, "主键 (taskId,date) 应保证只有一行")
    }

    @Test
    fun countByRule按规则统计() = runBlocking {
        val store = SqldelightTaskStore(open(), lookup)
        store.save(sampleTask(title = "绑规则").copy(ruleId = 7L))
        store.save(sampleTask(title = "另一条").copy(ruleId = 7L))
        store.save(sampleTask(title = "不绑"))

        assertEquals(2, store.countByRule(7L))
        assertEquals(0, store.countByRule(99L))
    }

    // ---- 老数据回落 ----

    @Test
    fun windows为空串时回落到旧的单时段() = runBlocking {
        val database = open()
        // 直接写一行 windows = ''，模拟 v3 之前升级上来的任务
        database.taskQueries.insertTaskAuto(
            title = "老任务", sourceType = "SCREEN_TIME", metricBucket = "COUNT",
            threshold = 1.0, weight = 1.0, remindLevel = 0,
            windowStartMinute = 480, windowEndMinute = 600, enabled = 1,
            windows = "", startDate = "", endDate = "", repeatType = "DAILY",
            weekdayMask = "", anchorDay = 1, anchorMonth = 1, ruleId = 0,
            timeSource = "FOLLOW_RULE", doneRemindLevel = 0, doneToastEnabled = 1,
            windowOffsetMinutes = 5,
        )

        val store = SqldelightTaskStore(database, lookup)
        val task = assertNotNull(store.getAll().single())
        assertEquals(1, task.schedule.windows.size, "windows 空串必须回落到单时段，否则任务没有生效时段")
        assertEquals(480, task.schedule.windows.first().startMinute)
        assertEquals(600, task.schedule.windows.first().endMinute)
    }

    // ---- MetricStore ----

    @Test
    fun 指标写入与按源取区间() = runBlocking {
        val store = SqldelightMetricStore(open(), lookup)
        val ref = SourceRef.Builtin("SCREEN_TIME", "屏幕使用", MetricBucket.COUNT)

        store.saveMetric(MetricRecord(source = ref, date = "2026-11-12", value = 10.0))
        store.saveMetric(MetricRecord(source = ref, date = "2026-11-13", value = 20.0))

        assertEquals(1, store.getMetricsByDate("2026-11-13").size)
        val range = store.getSourceRange("SCREEN_TIME", "COUNT", "2026-11-01", "2026-11-30")
        assertEquals(2, range.size)
        assertEquals("2026-11-12", range.first().date)
    }

    @Test
    fun latestDate只按同一量纲找() = runBlocking {
        val store = SqldelightMetricStore(open(), lookup)
        val ref = SourceRef.Builtin("SCREEN_TIME", "屏幕使用", MetricBucket.COUNT)
        store.saveMetric(MetricRecord(source = ref, date = "2026-11-10", value = 1.0))
        store.saveMetric(MetricRecord(source = ref, date = "2026-11-13", value = 2.0))

        assertEquals(
            "2026-11-13",
            store.latestDateWithBucket("SCREEN_TIME", "COUNT", "2026-11-01", "2026-11-30"),
        )
        // 换个不存在的量纲不该拿到别的量纲的日期
        assertNull(store.latestDateWithBucket("SCREEN_TIME", "DURATION", "2026-11-01", "2026-11-30"))
    }

    @Test
    fun 分应用用量覆盖写入与汇总() = runBlocking {
        val store = SqldelightMetricStore(open(), lookup)
        store.saveAppUsage(
            listOf(
                AppUsageRecord(date = "2026-11-12", packageName = "com.a", minutes = 10.0, label = "A"),
                AppUsageRecord(date = "2026-11-13", packageName = "com.a", minutes = 5.0, label = "A"),
                AppUsageRecord(date = "2026-11-13", packageName = "com.b", minutes = 30.0, label = "B"),
            ),
        )
        // 同一天同一应用再写一次应覆盖
        store.saveAppUsage(
            listOf(AppUsageRecord(date = "2026-11-13", packageName = "com.b", minutes = 60.0, label = "B")),
        )

        assertEquals(1, store.getAppUsageByDate("2026-11-13").count { it.packageName == "com.b" })

        val sums = store.sumAppUsageInRange("2026-11-01", "2026-11-30")
        assertEquals(2, sums.size)
        // 按时长降序：B 60 > A 15
        assertEquals("com.b", sums.first().packageName)
        assertEquals(60.0, sums.first().minutes)
        assertEquals(15.0, sums.first { it.packageName == "com.a" }.minutes)
    }

    // ---- DashboardStore ----

    @Test
    fun 页签与卡片写入并按顺序读出() = runBlocking {
        val store = SqldelightDashboardStore(open())
        store.saveTab(DashboardTab(tabId = "t1", name = "面板", order = 0, isBuiltin = true))
        store.saveCard(
            DashboardCard(cardId = "c2", type = "TODO", order = 1, tabId = "t1"),
        )
        store.saveCard(
            DashboardCard(cardId = "c1", type = "SCORE", order = 0, tabId = "t1"),
        )

        assertEquals(1, store.getTabs().size)
        assertEquals(listOf("c1", "c2"), store.getCardsByTab("t1").map { it.cardId })
        assertEquals(2, store.countCards())
    }

    @Test
    fun insertCard成功返回0() = runBlocking {
        val store = SqldelightDashboardStore(open())
        store.saveTab(DashboardTab(tabId = "t1", name = "面板", order = 0))

        // 契约：成功返回 0，被 IGNORE 挡住才返回 -1。
        // ViewModel 用 >= 0 判成功，这里把返回值钉死，防止有人把成功改成别的正数
        val result = store.insertCard(
            DashboardCard(cardId = "c1", type = "TODO", order = 0, tabId = "t1"),
        )
        assertEquals(0L, result, "首次插入应返回 0")
        assertEquals(1, store.countCards())
    }

    @Test
    fun insertCard用IGNORE不覆盖已有卡() = runBlocking {
        val store = SqldelightDashboardStore(open())
        store.saveTab(DashboardTab(tabId = "t1", name = "面板", order = 0))
        store.saveCard(DashboardCard(cardId = "c1", type = "SCORE", order = 0, tabId = "t1"))

        // 同 id 再插一次：必须被忽略，原有卡不能被替换
        val result = store.insertCard(
            DashboardCard(cardId = "c1", type = "TODO", order = 9, tabId = "t1"),
        )
        assertEquals(-1L, result, "重复 id 插入应返回 -1")
        assertEquals("SCORE", store.getCardsByTab("t1").single().type, "已有卡不能被覆盖")
    }

    @Test
    fun 删除页签级联删掉卡片() = runBlocking {
        val store = SqldelightDashboardStore(open())
        store.saveTab(DashboardTab(tabId = "t1", name = "面板", order = 0))
        store.saveCard(DashboardCard(cardId = "c1", type = "SCORE", order = 0, tabId = "t1"))

        store.deleteTab("t1")

        assertEquals(0, store.getTabs().size)
        assertEquals(0, store.countCards(), "删页签必须带走卡片，否则留下永远不显示的孤儿数据")
    }

    @Test
    fun 卡片开关与移动页签() = runBlocking {
        val store = SqldelightDashboardStore(open())
        store.saveTab(DashboardTab(tabId = "t1", name = "A", order = 0))
        store.saveTab(DashboardTab(tabId = "t2", name = "B", order = 1))
        store.saveCard(DashboardCard(cardId = "c1", type = "SCORE", order = 0, tabId = "t1"))

        store.setCardEnabled("c1", false)
        assertEquals(false, store.getCardsByTab("t1").single().enabled)

        store.moveCardToTab("c1", "t2")
        assertEquals(0, store.getCardsByTab("t1").size)
        assertEquals(1, store.getCardsByTab("t2").size)
    }

    // ---- RuleStore ----

    @Test
    fun 规则写入返回自增id且可按包名查启用() = runBlocking {
        val store = SqldelightRuleStore(open())
        val id = store.saveRule(
            com.lifeboard.shared.repository.model.CaptureRuleRecord(
                packageName = "com.feishu",
                sourceName = "CUSTOM_1",
                enabled = true,
                createdAt = 1L,
            ),
        )
        assertTrue(id > 0, "新增规则应返回自增 id")

        assertEquals(1, store.getEnabledRulesByPackage("com.feishu").size)
        assertEquals(0, store.getEnabledRulesByPackage("com.other").size)
        assertEquals(1, store.countRulesBySource("CUSTOM_1"))
    }

    @Test
    fun 自建源写入返回自增id() = runBlocking {
        val store = SqldelightRuleStore(open())
        val id = store.saveCustomSource(CustomSourceRecord(displayName = "背单词", bucket = "COUNT"))
        assertTrue(id > 0)
        assertEquals("背单词", store.getCustomSource(id)?.displayName)
    }

    // ---- LogStore ----

    @Test
    fun 日志分页与裁剪() = runBlocking {
        val store = SqldelightLogStore(open())
        (1..5).forEach { i ->
            store.insertAppLog(
                com.lifeboard.shared.repository.model.AppLogRecord(
                    level = if (i % 2 == 0) "ERROR" else "INFO",
                    tag = "T$i",
                    location = "L$i",
                    message = "消息$i",
                    time = i.toLong(),
                ),
            )
        }

        assertEquals(5, store.countAppLogs())
        // 最新在前
        assertEquals("消息5", store.latestAppLogs(1).single().message)
        // 按级别过滤
        assertEquals(2, store.pageAppLogs("ERROR", "", 10, 0).size)
        // 按关键词过滤（匹配 message）
        assertEquals(1, store.pageAppLogs("", "消息3", 10, 0).size)

        store.trimAppLogs(2)
        assertEquals(2, store.countAppLogs())
    }

    // ---- PluginStore ----

    @Test
    fun 凭据存取与按插件清除() = runBlocking {
        val store = SqldelightPluginStore(open())
        store.saveCredential(
            com.lifeboard.shared.repository.model.PluginCredentialRecord(
                pluginId = "p1", slot = "access_token", cipherText = "cipher", updatedAt = 1L,
            ),
        )
        assertEquals("cipher", store.getCredential("p1", "access_token"))
        assertEquals(listOf("access_token"), store.getCredentialSlots("p1"))

        assertEquals(1, store.deleteCredentialsByPlugin("p1"))
        assertNull(store.getCredential("p1", "access_token"))
    }

    @Test
    fun 原始条目按天与指标整体替换() = runBlocking {
        val store = SqldelightPluginStore(open())
        fun row(metric: String, seq: Int) = com.lifeboard.shared.repository.model.PluginRawRecord(
            pluginId = "p1", date = "2026-11-13", metricKey = metric,
            timestamp = 1000L + seq, value = seq.toDouble(), detail = "", seq = seq,
        )
        store.insertRawRecords(listOf(row("STEPS", 0), row("STEPS", 1), row("SLEEP", 0)))

        assertEquals(3, store.getRawRecords("p1", "2026-11-01", "2026-11-30").size)

        // 只替换 STEPS：SLEEP 必须留着
        assertEquals(2, store.deleteRawRecords("p1", "2026-11-13", "STEPS"))
        store.insertRawRecords(listOf(row("STEPS", 0)))

        val after = store.getRawRecords("p1", "2026-11-01", "2026-11-30")
        assertEquals(2, after.size, "只替换指定指标，不能顺手删掉别的指标")
        assertTrue(after.any { it.metricKey == "SLEEP" })
        assertTrue(after.any { it.metricKey == "STEPS" })
    }

    @Test
    fun 查询痕迹去重且可清空() = runBlocking {
        val store = SqldelightPluginStore(open())
        store.recordMetricQueries("p1", "2026-11-13", listOf("STEPS", "SLEEP"))
        // 同一天重复记录同指标不应报冲突
        store.recordMetricQueries("p1", "2026-11-13", listOf("STEPS"))

        assertEquals(
            setOf("STEPS", "SLEEP"),
            store.getMetricQueries("p1", "2026-11-01", "2026-11-30").toSet(),
        )
        assertEquals("2026-11-13", store.earliestMetricQueryDate("p1"))

        assertEquals(2, store.clearAllMetricQueries())
    }
}
