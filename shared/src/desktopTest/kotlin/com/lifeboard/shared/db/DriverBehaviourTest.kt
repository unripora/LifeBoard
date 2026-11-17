package com.lifeboard.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-2.3 验收：桌面驱动的行为契约。
 *
 * 这几条都是「写错了不报错、只静默出错」的地方，必须钉住：
 *
 * 1. 外键在**每条连接**上都开启（连接串参数 `?foreign_keys=on`），
 *    否则连接池换一条连接后 `ON DELETE CASCADE` 就静默失效
 * 2. 已有库不会被当成空库重建（`user_version` 非 0 就不走 `create`）
 * 3. **迁移完必须写回 `user_version`**——这条曾经是 bug：
 *    只调 `Schema.migrate` 不写版本，下次打开会认为仍是旧版、把整条链再跑一遍
 */
class DriverBehaviourTest {

    private val dbFile = File.createTempFile("driver-", ".db").apply { delete() }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    @Test
    fun 外键在多次查询间保持开启() {
        val driver = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)
        repeat(5) { i ->
            assertEquals(1L, driver.pragma("PRAGMA foreign_keys"), "第 $i 次读取外键应仍为开启")
        }
    }

    @Test
    fun 事务内外键仍开启() {
        val driver = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)
        val db = LifeBoardDb(driver)
        // 用生成的 transaction {}：驱动内部已管理 BEGIN/COMMIT，
        // 手工 execSQL("BEGIN") 会与它抢事务，报 "cannot commit - no transaction is active"
        var inside = 0L
        db.transaction {
            inside = driver.pragma("PRAGMA foreign_keys")
        }
        assertEquals(1L, inside, "事务内也应开启")
        assertEquals(1L, driver.pragma("PRAGMA foreign_keys"), "事务后也应开启")
    }

    @Test
    fun 迁移后版本被写回() {
        val driver = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)
        assertEquals(18L, driver.pragma("PRAGMA user_version"))
        // 重开同一文件：应识别为「已是 18」，不再重复建表
        driver.close()
        val again = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)
        assertEquals(18L, again.pragma("PRAGMA user_version"))
    }

    @Test
    fun 从老版本库迁移后版本也被写回() {
        // 先造一个 v1 库
        val seed = DatabaseDriverFactory(migrate = false).create(dbFile.absolutePath)
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
        ).forEach { seed.execute(null, it, 0) }
        seed.execute(null, "PRAGMA user_version = 1", 0)
        seed.close()

        // 用生产路径打开：应自动迁移到 18 并写回版本
        val migrated = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)
        assertEquals(18L, migrated.pragma("PRAGMA user_version"), "迁移后必须把 user_version 写成 18")
        migrated.close()

        // 再开一次：不该重复迁移（重复跑 11.sqm 的表重建会丢数据/报错）
        val reopened = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)
        assertEquals(18L, reopened.pragma("PRAGMA user_version"))
    }

    @Test
    fun 已有库不会被当成空库重建() {
        val d1 = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)
        d1.execute(
            null,
            "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin, enabled) " +
                "VALUES ('keep', '要留下的', 0, 0, 1)",
            0,
        )
        d1.close()

        val d2 = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)
        val kept = d2.pragma("SELECT count(*) FROM dashboard_tab WHERE tabId = 'keep'")
        assertEquals(1L, kept, "重开已有库不应重建，数据必须保留")
    }
}

private fun SqlDriver.pragma(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) },
        parameters = 0,
    ).value
