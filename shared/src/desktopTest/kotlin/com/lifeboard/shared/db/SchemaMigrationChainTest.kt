package com.lifeboard.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-2.2 验证：`.sq` 建表与 `.sqm` 迁移链是否与 v18 结构一致。
 *
 * 这一层不做「数据无损」验证（那是 2.6 用真实库副本做的），
 * 只钉住四件在开发期就会错、且错了很难查的事：
 *
 * 1. 全新建库能成功，且 `user_version` 等于 18（与 Room 侧同号）
 * 2. 16 张业务表、14 个索引、1 个外键全部建出来
 * 3. 索引里没有唯一索引残留（v13->v14 拆过唯一约束）
 * 4. 从 v1 空库逐版本迁移到 18 不报错，老数据还在、新列拿到预期默认值
 *
 * 用临时**文件**库而不是 `:memory:`：sqlite-jdbc 的 `:memory:` 是每连接独立库，
 * 与真实场景（一个文件被多次打开、迁移后立刻被另一次打开读）不符。
 *
 * 全部用原始 SQL 断言，不依赖 2.4 才会生成的 `*Queries`——
 * 这样 2.2 可以被独立验证，不会被后续步骤的进度牵连。
 */
class SchemaMigrationChainTest {

    private val dbFile = File.createTempFile("lifeboard-m2-", ".db").apply { delete() }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    private fun newDriver(): SqlDriver =
        DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)

    @Test
    fun 全新建库后版本为18() {
        assertEquals(
            18L,
            newDriver().longValue("PRAGMA user_version"),
            "目标版本必须与 Room 的 v18 一致",
        )
    }

    @Test
    fun 建库后16张业务表全部存在() {
        val names = newDriver().stringColumn(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        )
        listOf(
            "task", "daily_record", "metric", "streak", "notification_log", "source_health",
            "raw_notification", "capture_rule", "custom_source", "app_usage",
            "plugin_credential", "dashboard_tab", "dashboard_card", "app_log",
            "plugin_raw_record", "plugin_metric_query",
        ).forEach { assertTrue(it in names, "缺表 $it，实际有：$names") }

        // M0 探针已删，不该再出现在正式库里
        assertTrue("m0_probe" !in names, "M0Probe.sq 应已删除，实际仍存在")
    }

    @Test
    fun 索引全部建出且无唯一索引() {
        val driver = newDriver()
        val defined = driver.stringColumn(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL",
        )
        listOf(
            "index_daily_record_date",
            "index_metric_date",
            "index_notification_log_createdAt",
            "index_raw_notification_postedAt",
            "index_raw_notification_packageName",
            "index_capture_rule_packageName",
            "index_plugin_credential_pluginId",
            "index_dashboard_card_tabId",
            "index_dashboard_card_tabId_type",
            "index_app_log_time",
            "index_app_log_level",
            "index_plugin_raw_record_pluginId_date",
            "index_plugin_raw_record_pluginId_date_metricKey",
            "index_plugin_metric_query_pluginId_date",
        ).forEach { assertTrue(it in defined, "缺索引 $it，实际有：$defined") }

        // v13->v14 把 (tabId, type) 从 UNIQUE 改成普通索引。
        // 若迁移链没走对、或建表时写成 UNIQUE，「加不了第二张指标卡」的老 bug 会复现
        val uniques = driver.stringColumn(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND sql LIKE '%UNIQUE%'",
        )
        assertTrue(uniques.isEmpty(), "不应存在唯一索引，实际有：$uniques")
    }

    @Test
    fun 外键级联删除生效() {
        val driver = newDriver()
        // 外键必须在每个连接上都开启，否则 CASCADE 静默失效
        assertEquals(1L, driver.longValue("PRAGMA foreign_keys"), "外键约束必须开启")

        driver.execute(
            null,
            "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin, enabled) " +
                "VALUES ('t1', '测试', 0, 0, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId, configJson, enabled) " +
                "VALUES ('c1', 'METRIC', 'SCREEN_TIME', 'TOTAL', 0, 't1', NULL, 1)",
            0,
        )
        assertEquals(1L, driver.longValue("SELECT count(*) FROM dashboard_card"))

        // 删页签，卡片应被级联带走——这正是 11.sqm 重建表那一趟的目的
        driver.execute(null, "DELETE FROM dashboard_tab WHERE tabId = 't1'", 0)
        assertEquals(
            0L,
            driver.longValue("SELECT count(*) FROM dashboard_card"),
            "删页签后卡片必须级联删除，否则留下永远不显示的孤儿数据",
        )
    }

    @Test
    fun 从v1空库逐版本迁移到18() {
        // 按 v1 的结构手工建一个「最老的库」：只有 6 张表、3 个索引，
        // 没有任何后来新增的列
        val driver = DatabaseDriverFactory(migrate = false).create(dbFile.absolutePath)
        createV1Schema(driver)
        driver.writeUserVersion(1)

        // 插一条「老任务」，验证迁移给新列填的默认值是否符合预期
        driver.execute(
            null,
            "INSERT INTO task (title, sourceType, metricBucket, threshold, weight, " +
                "remindLevel, windowStartMinute, windowEndMinute, enabled) " +
                "VALUES ('老任务', 'SCREEN_TIME', 'FLAG', 1.0, 1.0, 0, 0, 1440, 1)",
            0,
        )

        // 一次性跨完 1 -> 18，SQLDelight 会按顺序跑所有 .sqm
        LifeBoardDb.Schema.migrate(driver, 1L, 18L).value
        driver.writeUserVersion(18)

        assertEquals(18L, driver.longValue("PRAGMA user_version"))

        // 后来新增的表都该出现
        val tables = driver.stringColumn(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        )
        listOf(
            "raw_notification", "capture_rule", "custom_source", "app_usage",
            "plugin_credential", "dashboard_tab", "dashboard_card", "app_log",
            "plugin_raw_record", "plugin_metric_query",
        ).forEach { assertTrue(it in tables, "迁移后缺表 $it，实际有：$tables") }

        // 老任务仍在，新列拿到的是迁移里写的默认值
        assertEquals(
            listOf<Any?>("老任务", 0L, "FOLLOW_RULE", 0L, 1L, 5L, ""),
            driver.row(
                "SELECT title, ruleId, timeSource, doneRemindLevel, doneToastEnabled, " +
                    "windowOffsetMinutes, windows FROM task",
            ),
            "老任务必须保留，且新列取迁移里声明的默认值",
        )

        // 迁移后的结构必须能接受新表写入（11.sqm 重建过的 dashboard_card 尤其要验）
        driver.execute(
            null,
            "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin, enabled) " +
                "VALUES ('t2', '新页签', 0, 0, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId, configJson, enabled) " +
                "VALUES ('c2', 'METRIC', 'SCREEN_TIME', 'TOTAL', 0, 't2', NULL, 1)",
            0,
        )
        assertEquals(
            1L,
            driver.longValue("SELECT count(*) FROM dashboard_card"),
            "迁移后的 dashboard_card 应能正常写入",
        )
    }

    /** 按 v1 的结构建库：6 张表、3 个索引，与 1.json 一致 */
    private fun createV1Schema(driver: SqlDriver) {
        listOf(
            "CREATE TABLE `task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `metricBucket` TEXT NOT NULL, " +
                "`threshold` REAL NOT NULL, `weight` REAL NOT NULL, `remindLevel` INTEGER NOT NULL, " +
                "`windowStartMinute` INTEGER NOT NULL, `windowEndMinute` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL)",
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
            "CREATE INDEX `index_daily_record_date` ON `daily_record` (`date`)",
            "CREATE INDEX `index_metric_date` ON `metric` (`date`)",
            "CREATE INDEX `index_notification_log_createdAt` ON `notification_log` (`createdAt`)",
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

/** 读单行多列，按「能转 Long 就转 Long，否则保留 String」归一化 */
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

private fun SqlDriver.longValue(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) },
        parameters = 0,
    ).value

private fun SqlDriver.writeUserVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version", 0)
}
