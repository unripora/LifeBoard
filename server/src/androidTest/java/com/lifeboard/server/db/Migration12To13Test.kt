package com.lifeboard.server.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v12 → v13：新增 `app_log` 表。
 *
 * 只验两件事：
 * 1. 老数据一张不少（这是「升级不丢数据」的底线）
 * 2. 新表结构确实建出来了，能写能查
 */
@RunWith(AndroidJUnit4::class)
class Migration12To13Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeBoardDatabase::class.java,
    )

    private val dbName = "migration-12-13-test.db"

    @Test
    fun migrate12To13_keepsExistingData_andCreatesAppLog() {
        helper.createDatabase(dbName, 12).use { db ->
            // task 表字段大多 NOT NULL 且无默认值，这里按 v12 结构逐个补齐，
            // 只填最小必要值：本用例关心的是「迁移后数据还在不在」，不是业务语义
            db.execSQL(
                "INSERT INTO task (title, sourceType, metricBucket, threshold, weight, remindLevel, " +
                    "windowStartMinute, windowEndMinute, enabled, windows, startDate, endDate, " +
                    "repeatType, weekdayMask, anchorDay, anchorMonth, ruleId, timeSource, " +
                    "doneRemindLevel, doneToastEnabled, windowOffsetMinutes) " +
                    "VALUES ('背单词', 'MANUAL', 'CUSTOM_A', 1.0, 1.0, 0, 0, 1439, 1, '', '', '', " +
                    "'NONE', 0, 0, 0, '', 'SCHEDULE', 0, 1, 0)",
            )
            db.execSQL(
                "INSERT INTO plugin_credential (pluginId, slot, cipherText, updatedAt) " +
                    "VALUES ('com.example.plugin', 'token', 'cipher-blob', 1700000000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            13,
            true,
            LifeBoardDatabase.MIGRATION_12_13,
        )

        // 1. 既有业务数据与凭据密文原样保留
        migrated.query("SELECT title FROM task").use {
            it.moveToFirst()
            assertEquals("背单词", it.getString(0))
        }
        migrated.query("SELECT cipherText FROM plugin_credential").use {
            it.moveToFirst()
            assertEquals("cipher-blob", it.getString(0))
        }

        // 2. 新表可用：写入后能查回来
        migrated.execSQL(
            "INSERT INTO app_log (level, tag, location, message, time) " +
                "VALUES ('INFO', 'PluginLoader', 'PluginLoader.kt:228', '已注册插件源 2 个', 1700000000000)",
        )
        migrated.query("SELECT level, tag, message FROM app_log").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("INFO", it.getString(0))
            assertEquals("PluginLoader", it.getString(1))
            assertEquals("已注册插件源 2 个", it.getString(2))
        }

        // 3. 索引建好了：日志量上万时靠它分页，缺了会全表扫
        migrated.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='app_log'").use {
            val names = buildList {
                while (it.moveToNext()) add(it.getString(0))
            }
            assertTrue("缺少 time 索引：$names", names.any { n -> n.contains("time") })
            assertTrue("缺少 level 索引：$names", names.any { n -> n.contains("level") })
        }
    }
}
