package com.lifeboard.server.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v14 → v15：新增 `plugin_raw_record` 表（插件原始条目）。
 *
 * 验三件事：
 * 1. 老数据一张不少（升级不丢卡片与凭据）
 * 2. 新表能写能查，且 `date` 列按天隔离——这是时间段筛选的基础
 * 3. 表结构、索引与 `15.json` 逐字段一致（`runMigrationsAndValidate` 会比对）
 */
@RunWith(AndroidJUnit4::class)
class Migration14To15Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeBoardDatabase::class.java,
    )

    private val dbName = "migration-14-15-test.db"

    @Test
    fun migrate14To15_keepsExistingData_andCreatesRawRecordTable() {
        helper.createDatabase(dbName, 14).use { db ->
            db.execSQL(
                "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin) " +
                    "VALUES ('user_1', '小米', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId) " +
                    "VALUES ('c1', 'METRIC', 'PLUGIN_p:STEPS', 'STEPS', 0, 'user_1')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            15,
            true,
            LifeBoardDatabase.MIGRATION_14_15,
        )

        // 1. 老卡片保留
        migrated.query("SELECT cardId FROM dashboard_card").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("c1", it.getString(0))
        }

        // 2. 新表可用，且不同日期能共存（时间段筛选的前提）
        listOf(
            "INSERT INTO plugin_raw_record " +
                "(pluginId, date, metricKey, `timestamp`, value, detail, seq) " +
                "VALUES ('p', '2026-11-08', 'STEPS', 1000, 10.0, '', 0)",
            "INSERT INTO plugin_raw_record " +
                "(pluginId, date, metricKey, `timestamp`, value, detail, seq) " +
                "VALUES ('p', '2026-11-09', 'STEPS', 2000, 20.0, '', 0)",
        ).forEach { migrated.execSQL(it) }

        migrated.query(
            "SELECT COUNT(*) FROM plugin_raw_record WHERE pluginId = 'p' AND date = '2026-11-09'",
        ).use {
            it.moveToFirst()
            assertEquals("按日期过滤应只命中当天那一条", 1, it.getInt(0))
        }

        migrated.query(
            "SELECT COUNT(*) FROM plugin_raw_record " +
                "WHERE pluginId = 'p' AND date BETWEEN '2026-11-01' AND '2026-11-30'",
        ).use {
            it.moveToFirst()
            assertEquals("时间段查询应命中两天", 2, it.getInt(0))
        }
    }
}
