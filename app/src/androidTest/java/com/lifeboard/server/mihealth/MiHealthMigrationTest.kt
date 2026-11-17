package com.lifeboard.server.mihealth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.repository.MetricStore
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
    private lateinit var store: MetricStore

    /** 内置源在库里的 key，与 `SourceType.MI_HEALTH.name` 一致 */
    private val legacyKey = "MI_HEALTH"

    /** 旧内置实现写的是 STEPS 量纲，源对象与 metric 表的 sourceType 用同一个 key */
    private fun legacySource() = SourceRef.Builtin(
        key = legacyKey,
        displayName = "小米健康",
        bucket = MetricBucket.STEPS,
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        store = DatabaseProviders.metricStore(context)
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
        store.deleteMetricsBySource(legacyKey)
        store.deleteSourceHealth(legacyKey)
        Unit
    }

    private suspend fun seedLegacyRows(date: String) {
        store.saveMetric(MetricRecord(source = legacySource(), date = date, value = 1234.0))
        store.saveSourceHealth(
            SourceHealth(source = legacySource(), healthy = true, lastSuccessAt = 1L, message = "正常"),
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
            store.getMetric(legacyKey, "2026-01-01", MetricBucket.STEPS.name),
        )
        assertEquals(
            0,
            store.getAllSourceHealth().count { it.source.key == legacyKey },
        )
    }

    @Test
    fun cleanup_isIdempotent_andDoesNotTouchPluginRows() = runBlocking {
        seedLegacyRows("2026-01-02")
        MiHealthMigration.cleanupLegacyRows(context)

        // 模拟插件（M5 之后）写入的新数据
        val pluginKey = "PLUGIN_com.lifeboard.plugin.mihealth:STEPS"
        val pluginSource = SourceRef.Plugin(
            pluginId = "com.lifeboard.plugin.mihealth",
            metricKey = "STEPS",
            displayName = "小米健康",
            bucket = MetricBucket.STEPS,
        )
        assertEquals("插件源 key 必须与约定一致", pluginKey, pluginSource.key)
        store.saveMetric(MetricRecord(source = pluginSource, date = "2026-01-03", value = 500.0))

        // 再跑一次：必须什么都不做
        val second = MiHealthMigration.cleanupLegacyRows(context)

        assertEquals("已清理过就不该再动数据库", 0, second)
        assertNotNull(
            "插件写的数据绝不能被当成旧数据删掉",
            store.getMetric(pluginKey, "2026-01-03", MetricBucket.STEPS.name),
        )
    }
}
