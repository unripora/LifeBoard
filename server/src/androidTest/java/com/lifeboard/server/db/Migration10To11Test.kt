package com.lifeboard.server.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v10 → v11 迁移。
 *
 * 这一步只加两张表，但**必须验证旧数据没被清掉**：
 * 一旦落到 destructive fallback，用户的任务、规则、插件凭据会全部消失，
 * 而界面上表现为「升级之后所有配置都没了」，且没有任何报错。
 */
@RunWith(AndroidJUnit4::class)
class Migration10To11Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeBoardDatabase::class.java,
    )

    private val dbName = "migration-10-11-test.db"

    @Test
    fun migrate10To11_createsDashboardTables_andKeepsExistingData() {
        // 建一个 v10 的库，写入一条任务作为「老数据」代表。
        // 字段必须给全：task 表里大量字段是 NOT NULL 且有默认值，
        // 但 SQL 插入不走 Room 的默认值，漏一个就违反约束
        helper.createDatabase(dbName, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO task (
                    title, sourceType, metricBucket, threshold, weight, remindLevel,
                    windowStartMinute, windowEndMinute, enabled,
                    windows, startDate, endDate, repeatType, weekdayMask,
                    anchorDay, anchorMonth, ruleId, timeSource,
                    doneRemindLevel, doneToastEnabled, windowOffsetMinutes
                ) VALUES (
                    '老任务', 'SCREEN_TIME', 'DURATION', 60.0, 1.0, 0,
                    540, 600, 1,
                    '', '', '', 'DAILY', '',
                    1, 1, 0, 'FOLLOW_RULE',
                    0, 1, 5
                )
                """.trimIndent(),
            )
        }

        // 执行迁移
        val migrated = helper.runMigrationsAndValidate(
            dbName,
            11,
            true,
            LifeBoardDatabase.MIGRATION_10_11,
        )

        // 两张新表应存在且为空
        migrated.query("SELECT COUNT(*) FROM dashboard_tab").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM dashboard_card").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }

        // 老数据必须还在
        migrated.query("SELECT title FROM task").use {
            it.moveToFirst()
            assertEquals("升级不能清空已有任务", "老任务", it.getString(0))
        }
    }
}
