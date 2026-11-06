package com.lifeboard.server.mihealth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.MetricEntity
import com.lifeboard.server.db.SourceHealthEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 内置小米健康迁成插件后的一次性清理。
 *
 * 这个清理很容易写出两种致命错误，所以值得单独测：
 * 1. **漏删**：旧行留着，面板上出现永远不更新的卡片
 * 2. **重复删**：每次启动都跑，把插件新写入的数据当旧数据删掉
 *
 * 第 2 种尤其隐蔽——用户装了插件、数据正常，重启一次就全没了。
 */
@RunWith(AndroidJUnit4::class)
class MiHealthMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        resetMarker()
        clearRows()
    }

    @After
    fun tearDown() {
        resetMarker()
        clearRows()
    }

    /** 标记是私有的，测试要能重跑就必须把它清掉，否则第二次执行直接被跳过 */
    private fun resetMarker() {
        context.getSharedPreferences("lifeboard_migration", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun clearRows() = runBlocking {
        val db = LifeBoardDatabase.get(context)
        db.metricDao().deleteBySource(LEGACY_KEY)
        db.sourceHealthDao().deleteBySource(LEGACY_KEY)
        Unit
    }

    private suspend fun seedLegacyRows(date: String) {
        val db = LifeBoardDatabase.get(context)
        db.metricDao().upsert(
            MetricEntity(sourceType = LEGACY_KEY, date = date, bucket = "STEPS", value = 1234.0),
        )
        db.sourceHealthDao().upsert(
            SourceHealthEntity(sourceType = LEGACY_KEY, healthy = true, lastSuccessAt = 1L, message = "正常"),
        )
    }

    @Test
    fun cleanup_removesLegacyRows() = runBlocking {
        seedLegacyRows("2026-01-01")

        val removed = MiHealthMigration.cleanupLegacyRows(context)

        // 一条指标 + 一条健康度
        assertEquals(2, removed)
        assertNull(
            "旧指标必须删掉，否则面板会显示一张永远不更新的卡片",
            LifeBoardDatabase.get(context).metricDao()
                .getOne(LEGACY_KEY, "2026-01-01", "STEPS"),
        )
        assertEquals(
            0,
            LifeBoardDatabase.get(context).sourceHealthDao().getAll()
                .count { it.sourceType == LEGACY_KEY },
        )
    }

    @Test
    fun cleanup_isIdempotent_andDoesNotTouchPluginRows() = runBlocking {
        seedLegacyRows("2026-01-02")
        MiHealthMigration.cleanupLegacyRows(context)

        // 模拟插件（M5 之后）写入的新数据
        val pluginSource = "PLUGIN_com.lifeboard.plugin.mihealth:STEPS"
        LifeBoardDatabase.get(context).metricDao().upsert(
            MetricEntity(sourceType = pluginSource, date = "2026-01-03", bucket = "STEPS", value = 500.0),
        )

        // 再跑一次：必须什么都不做
        val second = MiHealthMigration.cleanupLegacyRows(context)

        assertEquals("已清理过就不该再动数据库", 0, second)
        assertNotNull(
            "插件写的数据绝不能被当成旧数据删掉",
            LifeBoardDatabase.get(context).metricDao()
                .getOne(pluginSource, "2026-01-03", "STEPS"),
        )
    }

    private companion object {
        /** 与 SourceType.MI_HEALTH.name 一致 */
        const val LEGACY_KEY = "MI_HEALTH"
    }
}
