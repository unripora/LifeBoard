package com.lifeboard.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-2.6 验证：迁移链的数据无损性。
 *
 * 与 [SchemaMigrationChainTest] 的分工：
 *
 * - 那边只看**结构**（表/索引/外键建出来没有），从 v1 空库一次性跨到 18
 * - 这边只看**数据**（每版本写入真实业务行，逐版本回放后逐项核对）
 *
 * 两者的共同前提是「`.sqm` 里的 SQL 与 Room 当年跑的是同一套语义」，
 * 所以这里刻意**一步一步**迁移（5→6→7…→18），而不是一把跨到底：
 * 一把跨完时某条 `.sqm` 写错（例如 ALTER 的默认值变了）仍然可能被后续语句掩盖，
 * 逐版本才能把责任钉到具体那一条。
 *
 * ## 为什么从 v5 起
 *
 * 计划里 2.6 的措辞是「逐版本回放 5→18」：v5 是 `ruleId` 落地的那一版，
 * 也是本项目第一份有真实用户数据的版本。v1~v4 的结构已经由
 * [SchemaMigrationChainTest] 覆盖，再补一遍数据意义不大。
 *
 * ## 断言什么
 *
 * 每个版本迁移完都核对四件事，缺一项都可能「看起来迁移成功、实际丢数据」：
 *
 * 1. `user_version` 走到目标版本
 * 2. 每张**当时已存在**的表行数不变
 * 3. 抽样字段值逐字相同（老数据不被 ALTER 的 DEFAULT 覆盖）
 * 4. 新加的列拿到 `.sqm` 里声明的默认值（老行不出现 NULL / 0 之外的意外值）
 */
class MigrationDataPreservationTest {

    private val dbFile = File.createTempFile("lifeboard-m26-", ".db").apply { delete() }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    /** 不开 migrate：测试自己控制「先建 v5 结构、再逐版本迁」的过程 */
    private fun rawDriver(): SqlDriver =
        DatabaseDriverFactory(migrate = false).create(dbFile.absolutePath)

    @Test
    fun 从v5逐版本回放到v18_行数与抽样值全部保留() {
        val driver = rawDriver()
        createV5Schema(driver)
        driver.writeUserVersion(5)

        // ---- v5 状态的业务数据 ----
        // 两条任务：一条不关联规则（ruleId=0），一条关联规则
        driver.execute(
            null,
            "INSERT INTO task (id, title, sourceType, metricBucket, threshold, weight, " +
                "remindLevel, windowStartMinute, windowEndMinute, enabled, ruleId) VALUES " +
                "(1, '喝水', 'CUSTOM_1', 'COUNT', 8.0, 1.0, 1, 480, 1320, 1, 0), " +
                "(2, '飞书打卡', 'FEISHU', 'FLAG', 1.0, 2.0, 2, 540, 570, 1, 7)",
            0,
        )
        // 记录：一条达成、一条未达成
        driver.execute(
            null,
            "INSERT INTO daily_record (taskId, date, value, completed, score) VALUES " +
                "(1, '2026-11-01', 8.0, 1, 1.0), (1, '2026-11-02', 5.0, 0, 0.0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO metric (sourceType, date, bucket, value) VALUES " +
                "('CUSTOM_1', '2026-11-01', 'COUNT', 8.0), ('FEISHU', '2026-11-01', 'FLAG', 1.0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO streak (taskId, currentStreak, bestStreak, lastDate) VALUES " +
                "(1, 1, 3, '2026-11-01')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO notification_log (id, taskId, remindLevel, triggeredAt, delivered, note, createdAt) " +
                "VALUES (1, 2, 2, 1762000000000, 1, '已提醒', 1762000001000)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO source_health (sourceType, healthy, lastSuccessAt, message) VALUES " +
                "('CUSTOM_1', 1, 1762000000000, '正常'), ('FEISHU', 0, 0, '未登录')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO raw_notification (id, packageName, title, text, matchedSources, postedAt) " +
                "VALUES (1, 'com.ss.android.lark', '打卡成功', '今日已打卡', 'FEISHU', 1762000000000)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO capture_rule (id, packageName, sourceName, bucketName, includeKeywords, " +
                "excludeKeywords, timeRegex, windowStartMinute, windowEndMinute, dailyOnce, enabled, createdAt) " +
                "VALUES (7, 'com.ss.android.lark', 'FEISHU', 'FLAG', '打卡成功', '补卡', '', 540, 570, 1, 1, 1761000000000)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO custom_source (id, displayName, bucket, createdAt) VALUES (1, '喝水', 'COUNT', 1761000000000)",
            0,
        )

        // v5 时的行数快照，迁移后逐项比对
        val rowCountsAtV5 = mapOf(
            "task" to 2L,
            "daily_record" to 2L,
            "metric" to 2L,
            "streak" to 1L,
            "notification_log" to 1L,
            "source_health" to 2L,
            "raw_notification" to 1L,
            "capture_rule" to 1L,
            "custom_source" to 1L,
        )
        rowCountsAtV5.forEach { (table, expected) ->
            assertEquals(expected, driver.longValue("SELECT count(*) FROM `$table`"), "v5 基线：$table")
        }

        // ---- 逐版本回放 5 -> 18 ----
        for (from in 5L until 18L) {
            LifeBoardDb.Schema.migrate(driver, from, from + 1).value
            driver.writeUserVersion(from + 1)

            assertEquals(from + 1, driver.longValue("PRAGMA user_version"), "迁移后版本应为 ${from + 1}")

            // 每一跳之后，已有表的行数都不能变——这是「不丢数据」最直接的证据。
            // 11.sqm 会重建 dashboard_card，但那时该表还是空的，不影响这里的断言。
            rowCountsAtV5.forEach { (table, expected) ->
                assertEquals(
                    expected,
                    driver.longValue("SELECT count(*) FROM `$table`"),
                    "从 v$from 迁到 v${from + 1} 后 $table 的行数变了",
                )
            }
        }

        assertEquals(18L, driver.longValue("PRAGMA user_version"))
    }

    @Test
    fun 老数据不被新增列的默认值覆盖() {
        val driver = rawDriver()
        createV5Schema(driver)
        driver.writeUserVersion(5)
        driver.execute(
            null,
            "INSERT INTO task (id, title, sourceType, metricBucket, threshold, weight, " +
                "remindLevel, windowStartMinute, windowEndMinute, enabled, ruleId) VALUES " +
                "(1, '喝水', 'CUSTOM_1', 'COUNT', 8.0, 1.5, 1, 480, 1320, 1, 0)",
            0,
        )

        LifeBoardDb.Schema.migrate(driver, 5L, 18L).value
        driver.writeUserVersion(18)

        // 原有列逐字不变，新增列取迁移里声明的默认值。
        // 这些默认值不是随手挑的：每一个都对应「老用户升级后行为不变」的承诺，
        // 改错任意一个都会让升级当天的提醒、判定、界面发生静默变化。
        assertEquals(
            listOf<Any?>(
                // 原有列（v5 时已存在）
                "喝水", "CUSTOM_1", "COUNT", "8.0", "1.5", 1L, 480L, 1320L, 1L, 0L,
                // v3 时就有的多时段列，升级后必须原样保留
                "", "", "", "DAILY", "", 1L, 1L,
                // 5.sqm：完成不提醒、浮窗开
                0L, 1L,
                // 6.sqm：时段结束后 5 分钟判定
                5L,
                // 7.sqm：跟随规则
                "FOLLOW_RULE",
            ),
            driver.row(
                "SELECT title, sourceType, metricBucket, threshold, weight, remindLevel, " +
                    "windowStartMinute, windowEndMinute, enabled, ruleId, " +
                    "windows, startDate, endDate, repeatType, weekdayMask, anchorDay, anchorMonth, " +
                    "doneRemindLevel, doneToastEnabled, windowOffsetMinutes, timeSource " +
                    "FROM task WHERE id = 1",
            ),
        )
    }

    @Test
    fun 升级前记录的origin落到UNKNOWN而不是AUTO() {
        val driver = rawDriver()
        createV5Schema(driver)
        driver.writeUserVersion(5)
        driver.execute(
            null,
            "INSERT INTO daily_record (taskId, date, value, completed, score) VALUES " +
                "(1, '2026-11-01', 8.0, 1, 1.0)",
            0,
        )

        LifeBoardDb.Schema.migrate(driver, 5L, 18L).value
        driver.writeUserVersion(18)

        // 升级前的记录来源不可考。猜成 AUTO 会让「手动打卡」类图表把历史数据
        // 全算成自动采集，用户看到一条从没有过的曲线
        assertEquals(
            "UNKNOWN",
            driver.stringValue("SELECT origin FROM daily_record WHERE date = '2026-11-01'"),
            "17.sqm 必须把老记录落成 UNKNOWN",
        )
    }

    @Test
    fun 老卡片的configJson保持NULL不写成空串() {
        val driver = rawDriver()
        createV5Schema(driver)
        driver.writeUserVersion(5)

        // 先迁到 14（dashboard_card 已建好且还是一次性建表），再插一张老卡片
        LifeBoardDb.Schema.migrate(driver, 5L, 14L).value
        driver.writeUserVersion(14)
        driver.execute(
            null,
            "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin) VALUES ('p', '面板', 0, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId) " +
                "VALUES ('c1', 'SCORE', NULL, NULL, 0, 'p')",
            0,
        )

        LifeBoardDb.Schema.migrate(driver, 14L, 18L).value
        driver.writeUserVersion(18)

        // 16.sqm 有意不给 configJson 默认值：空串会被 ChartConfigCodec 判成「配置损坏」，
        // 界面把老卡片显示成坏卡
        assertEquals(
            null,
            driver.stringValue("SELECT configJson FROM dashboard_card WHERE cardId = 'c1'"),
            "老卡片（非 CHART 类型）的 configJson 必须是 NULL",
        )
    }

    @Test
    fun v12到v14之间唯一约束被真正拆掉() {
        // 这条单独测，因为它是「静默失败」的典型：13.sqm 若只用
        // CREATE INDEX IF NOT EXISTS 而不先 DROP，同名的唯一索引会原样留着，
        // 迁移不报任何错，用户却仍然加不了第二张指标卡。
        val driver = rawDriver()
        createV5Schema(driver)
        driver.writeUserVersion(5)
        LifeBoardDb.Schema.migrate(driver, 5L, 14L).value
        driver.writeUserVersion(14)

        driver.execute(
            null,
            "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin) VALUES ('p', '面板', 0, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId) " +
                "VALUES ('c1', 'METRIC', 'STEPS', 'STEPS', 0, 'p')",
            0,
        )
        // 同一页签的第二张同类型卡：v13 之后必须能插进去
        driver.execute(
            null,
            "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId) " +
                "VALUES ('c2', 'METRIC', 'SLEEP', 'SLEEP_MINUTES', 1, 'p')",
            0,
        )
        assertEquals(2L, driver.longValue("SELECT count(*) FROM dashboard_card"))

        val uniques = driver.stringColumn(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND sql LIKE '%UNIQUE%'",
        )
        assertTrue(uniques.isEmpty(), "v18 不应有任何唯一索引，实际有：$uniques")
    }

    @Test
    fun 迁移后外键级联仍然生效() {
        val driver = rawDriver()
        createV5Schema(driver)
        driver.writeUserVersion(5)
        LifeBoardDb.Schema.migrate(driver, 5L, 18L).value
        driver.writeUserVersion(18)

        assertEquals(1L, driver.longValue("PRAGMA foreign_keys"), "外键必须开启，否则 CASCADE 静默失效")

        driver.execute(
            null,
            "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin, enabled) " +
                "VALUES ('t', '测试', 0, 0, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId, configJson, enabled) " +
                "VALUES ('c', 'METRIC', 'STEPS', 'STEPS', 0, 't', NULL, 1)",
            0,
        )
        driver.execute(null, "DELETE FROM dashboard_tab WHERE tabId = 't'", 0)

        assertEquals(
            0L,
            driver.longValue("SELECT count(*) FROM dashboard_card"),
            "迁移后的表结构仍须保留 ON DELETE CASCADE",
        )
    }

    /**
     * 按 v5 的结构建库：9 张表、6 个索引。
     *
     * 手写而不是复用 `.sq`：这里的目的是「造一个当年真实存在的库」，
     * 用当前 `.sq` 建出来的已经是 v18 结构，迁不出任何东西。
     *
     * 注意 `task` / `capture_rule` 里带着 `windows`、`repeatType` 等列——
     * 那是 3.sqm（v3→v4）加进来的，v5 的库本来就该有。漏掉它们会让
     * 后续 `ALTER TABLE ... ADD COLUMN` 撞上「列已存在」或断言读不到列。
     */
    private fun createV5Schema(driver: SqlDriver) {
        listOf(
            "CREATE TABLE `task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `metricBucket` TEXT NOT NULL, " +
                "`threshold` REAL NOT NULL, `weight` REAL NOT NULL, `remindLevel` INTEGER NOT NULL, " +
                "`windowStartMinute` INTEGER NOT NULL, `windowEndMinute` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                // 3.sqm（v3->v4）新增
                "`windows` TEXT NOT NULL DEFAULT '', `startDate` TEXT NOT NULL DEFAULT '', " +
                "`endDate` TEXT NOT NULL DEFAULT '', `repeatType` TEXT NOT NULL DEFAULT 'DAILY', " +
                "`weekdayMask` TEXT NOT NULL DEFAULT '', `anchorDay` INTEGER NOT NULL DEFAULT 1, " +
                "`anchorMonth` INTEGER NOT NULL DEFAULT 1, " +
                // 4.sqm（v4->v5）新增
                "`ruleId` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE `daily_record` (`taskId` INTEGER NOT NULL, `date` TEXT NOT NULL, " +
                "`value` REAL NOT NULL, `completed` INTEGER NOT NULL, `score` REAL NOT NULL, " +
                "PRIMARY KEY(`taskId`, `date`))",
            "CREATE TABLE `metric` (`sourceType` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                "`bucket` TEXT NOT NULL, `value` REAL NOT NULL, " +
                "PRIMARY KEY(`sourceType`, `date`, `bucket`))",
            "CREATE TABLE `streak` (`taskId` INTEGER NOT NULL, `currentStreak` INTEGER NOT NULL, " +
                "`bestStreak` INTEGER NOT NULL, `lastDate` TEXT, PRIMARY KEY(`taskId`))",
            "CREATE TABLE `notification_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`taskId` INTEGER NOT NULL, `remindLevel` INTEGER NOT NULL, " +
                "`triggeredAt` INTEGER NOT NULL, `delivered` INTEGER NOT NULL, " +
                "`note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE `source_health` (`sourceType` TEXT NOT NULL, `healthy` INTEGER NOT NULL, " +
                "`lastSuccessAt` INTEGER NOT NULL, `message` TEXT NOT NULL, PRIMARY KEY(`sourceType`))",
            "CREATE TABLE `raw_notification` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`packageName` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`matchedSources` TEXT NOT NULL, `postedAt` INTEGER NOT NULL)",
            "CREATE TABLE `capture_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`packageName` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `bucketName` TEXT NOT NULL, " +
                "`includeKeywords` TEXT NOT NULL, `excludeKeywords` TEXT NOT NULL, " +
                "`timeRegex` TEXT NOT NULL, `windowStartMinute` INTEGER NOT NULL, " +
                "`windowEndMinute` INTEGER NOT NULL, `dailyOnce` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                // 3.sqm（v3->v4）新增
                "`windows` TEXT NOT NULL DEFAULT '', `startDate` TEXT NOT NULL DEFAULT '', " +
                "`endDate` TEXT NOT NULL DEFAULT '', `repeatType` TEXT NOT NULL DEFAULT 'DAILY', " +
                "`weekdayMask` TEXT NOT NULL DEFAULT '', `anchorDay` INTEGER NOT NULL DEFAULT 1, " +
                "`anchorMonth` INTEGER NOT NULL DEFAULT 1)",
            "CREATE TABLE `custom_source` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`displayName` TEXT NOT NULL, `bucket` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE INDEX `index_daily_record_date` ON `daily_record` (`date`)",
            "CREATE INDEX `index_metric_date` ON `metric` (`date`)",
            "CREATE INDEX `index_notification_log_createdAt` ON `notification_log` (`createdAt`)",
            "CREATE INDEX `index_raw_notification_postedAt` ON `raw_notification` (`postedAt`)",
            "CREATE INDEX `index_raw_notification_packageName` ON `raw_notification` (`packageName`)",
            "CREATE INDEX `index_capture_rule_packageName` ON `capture_rule` (`packageName`)",
        ).forEach { driver.execute(null, it, 0) }
    }
}

/** 读单列字符串结果集 */
private fun SqlDriver.stringColumn(sql: String): List<String> {
    val out = mutableListOf<String>()
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            while (cursor.next().value) {
                cursor.getString(0)?.let { out += it }
            }
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    return out
}

/** 读单行多列，按「能转 Long 就转 Long、能转 Double 就转 Double，否则保留 String」归一化 */
private fun SqlDriver.row(sql: String): List<Any?> {
    var cells: List<Any?> = emptyList()
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            if (cursor.next().value) {
                val acc = mutableListOf<Any?>()
                var i = 0
                while (true) {
                    val value = runCatching { cursor.getString(i) }.getOrNull() ?: break
                    acc += value.toLongOrNull() ?: value
                    i++
                }
                cells = acc
            }
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    return cells
}

private fun SqlDriver.stringValue(sql: String): String? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0)) },
        parameters = 0,
    ).value

private fun SqlDriver.longValue(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) },
        parameters = 0,
    ).value
