package com.lifeboard.server.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v17 → v18：页签与卡片各加一列 `enabled`（是否显示）。
 *
 * 验三件事：
 * 1. 老数据一行不少（升级不丢页签与卡片）
 * 2. **老数据一律回填成「显示」**——这是这次迁移唯一有实际后果的决定：
 *    如果默认写成 0，用户一升级就会发现页签与卡片全没了
 * 3. 表结构与 `18.json` 一致（`runMigrationsAndValidate` 会逐字段比对）
 */
@RunWith(AndroidJUnit4::class)
class Migration17To18Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeBoardDatabase::class.java,
    )

    private val dbName = "migration-17-18-test.db"

    @Test
    fun migrate17To18_keepsExistingData_andDefaultsToEnabled() {
        helper.createDatabase(dbName, 17).use { db ->
            db.execSQL(
                "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin) " +
                    "VALUES ('builtin_panel', '面板', 0, 1)",
            )
            db.execSQL(
                "INSERT INTO dashboard_card " +
                    "(cardId, type, sourceKey, metricKey, `order`, tabId, configJson) " +
                    "VALUES ('c1', 'METRIC', 'PLUGIN_p:STEPS', 'STEPS', 0, 'builtin_panel', NULL)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            18,
            true,
            LifeBoardDatabase.MIGRATION_17_18,
        )

        // 1 + 2：老页签还在，且默认是显示
        migrated.query("SELECT enabled FROM dashboard_tab WHERE tabId = 'builtin_panel'").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("升级后页签应默认显示", 1, it.getInt(0))
        }

        // 1 + 2：老卡片同理
        migrated.query("SELECT enabled FROM dashboard_card WHERE cardId = 'c1'").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("升级后卡片应默认显示", 1, it.getInt(0))
        }

        // 3：新列能写能读，关掉之后查询值确实变了
        migrated.execSQL("UPDATE dashboard_card SET enabled = 0 WHERE cardId = 'c1'")
        migrated.query("SELECT enabled FROM dashboard_card WHERE cardId = 'c1'").use {
            it.moveToFirst()
            assertEquals("关掉之后应为 0", 0, it.getInt(0))
        }
    }
}
