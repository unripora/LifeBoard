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
 * v13 → v14：去掉 dashboard_card 的 (tabId, type) 唯一约束。
 *
 * 这一步是「一个页签里能放多张指标卡」的前提。要验三件事：
 * 1. 老卡片一张不少（升级不丢布局）
 * 2. 同一页签可以插多张指标卡——这正是修复的那个问题
 * 3. 索引仍然是 (tabId, type) 的非唯一索引：`runMigrationsAndValidate` 会拿
 *    迁移结果与 `14.json` 逐字段比对，建错了这里直接失败
 */
@RunWith(AndroidJUnit4::class)
class Migration13To14Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeBoardDatabase::class.java,
    )

    private val dbName = "migration-13-14-test.db"

    @Test
    fun migrate13To14_keepsCards_andAllowsMultipleMetricCards() {
        helper.createDatabase(dbName, 13).use { db ->
            db.execSQL(
                "INSERT INTO dashboard_tab (tabId, name, `order`, isBuiltin) " +
                    "VALUES ('user_1', '小米', 0, 0)",
            )
            // v13 里唯一索引挡着，同一页签只能有一条 METRIC
            db.execSQL(
                "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId) " +
                    "VALUES ('card_steps', 'METRIC', 'PLUGIN_p:STEPS', 'STEPS', 0, 'user_1')",
            )
            db.execSQL(
                "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId) " +
                    "VALUES ('card_score', 'SCORE', NULL, NULL, 1, 'user_1')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            14,
            true,
            LifeBoardDatabase.MIGRATION_13_14,
        )

        // 1. 老卡片原样保留
        migrated.query("SELECT cardId FROM dashboard_card ORDER BY `order`").use { cursor ->
            assertEquals("两张老卡都应保留", 2, cursor.count)
            cursor.moveToFirst()
            assertEquals("card_steps", cursor.getString(0))
            cursor.moveToNext()
            assertEquals("card_score", cursor.getString(0))
        }

        // 2. 关键断言：同页签第二张指标卡这次插得进去
        val secondMetricInserted = runCatching {
            migrated.execSQL(
                "INSERT INTO dashboard_card (cardId, type, sourceKey, metricKey, `order`, tabId) " +
                    "VALUES ('card_sleep', 'METRIC', 'PLUGIN_p:SLEEP', 'SLEEP', 2, 'user_1')",
            )
        }.isSuccess
        assertTrue("同页签必须能放第二张指标卡", secondMetricInserted)

        migrated.query(
            "SELECT COUNT(*) FROM dashboard_card WHERE tabId = 'user_1' AND type = 'METRIC'",
        ).use {
            it.moveToFirst()
            assertEquals("两张指标卡都应落库", 2, it.getInt(0))
        }
    }
}
