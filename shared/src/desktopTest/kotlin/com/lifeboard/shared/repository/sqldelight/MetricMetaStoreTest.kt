package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.db.DatabaseDriverFactory
import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.openDatabase
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.model.MetricMeta
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 插件指标元数据（`metric_meta`）的存取与解析。
 *
 * ## 这张表解决什么
 *
 * 插件的指标是运行时装进来的，`MetricSpec` 只活在内存里。此前读取侧只能从
 * 落库键 `PLUGIN_{pluginId}:{metricKey}` 反推元数据，于是：
 *
 * - 展示名拼成 `com.foo.bar·OBS_HEART_RATE`，用户看到的是包名；
 * - 量纲靠枚举名反解，插件的自定义 key 一律认不出，退化成 `COUNT`，
 *   心率被标成「次」。
 *
 * 落库后即使**插件已卸载**，历史数据仍能显示真实名称与单位，
 * 而不是报「源已不存在」或退化成包名。
 */
class MetricMetaStoreTest {

    private val dbFile = File.createTempFile("meta-", ".db").apply { delete() }
    private var db: LifeBoardDb? = null

    private val noCustom = SourceKeys.CustomSourceLookup { null }

    private fun open(): LifeBoardDb {
        db = openDatabase(DatabaseDriverFactory(migrate = true), dbFile.absolutePath)
        return db!!
    }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    private val pluginId = "com.lifeboard.plugin.mihealth"

    private fun meta(
        metricKey: String,
        displayName: String,
        bucket: MetricBucket,
        unit: String = bucket.unit,
    ) = MetricMeta(
        sourceType = SourceRef.pluginKey(pluginId, metricKey),
        pluginId = pluginId,
        metricKey = metricKey,
        displayName = displayName,
        unit = unit,
        bucket = bucket.name,
        updatedAt = 1L,
    )

    @Test
    fun `写入后能按 sourceType 读回`() = runBlocking {
        val store = SqldelightMetricStore(open(), noCustom)
        val heart = meta("OBS_HEART_RATE", "心率", MetricBucket.COUNT, "次/分")
        store.saveMetricMeta(heart)

        assertEquals(heart, store.getMetricMeta(heart.sourceType), "读回的元数据必须与写入的一致")
    }

    @Test
    fun `未写入时返回 null 而不是抛异常`() = runBlocking {
        val store = SqldelightMetricStore(open(), noCustom)
        assertNull(
            store.getMetricMeta("PLUGIN_unknown:whatever"),
            "查不到必须是 null：调用方据此退回从 key 猜，而不是让整条数据消失",
        )
    }

    @Test
    fun `同一 sourceType 重复写入是覆盖而不是报错`() = runBlocking {
        val store = SqldelightMetricStore(open(), noCustom)
        store.saveMetricMeta(meta("STEPS", "步数", MetricBucket.STEPS))
        // 模拟插件升级后改了展示名
        store.saveMetricMeta(meta("STEPS", "总步数", MetricBucket.STEPS))

        assertEquals(
            "总步数",
            store.getMetricMeta(SourceRef.pluginKey(pluginId, "STEPS"))?.displayName,
            "重复写入必须覆盖：插件升级改展示名是常事，用 INSERT 会主键冲突",
        )
    }

    @Test
    fun `批量查询一次取回多条且空集合不炸`() = runBlocking {
        val store = SqldelightMetricStore(open(), noCustom)
        store.saveMetricMeta(meta("STEPS", "步数", MetricBucket.STEPS))
        store.saveMetricMeta(meta("SLEEP_MINUTES", "睡眠", MetricBucket.SLEEP_MINUTES))

        val all = store.getMetricMetas(
            listOf(
                SourceRef.pluginKey(pluginId, "STEPS"),
                SourceRef.pluginKey(pluginId, "SLEEP_MINUTES"),
            ),
        )
        assertEquals(2, all.size, "两条都该取回，实际：$all")

        // 空集合必须提前返回：`IN ()` 在 SQLite 里是语法错误，
        // 而「还没有任何插件指标」时调用方必然传空
        assertTrue(store.getMetricMetas(emptyList()).isEmpty(), "空集合应返回空表而不是抛 SQL 错误")

        // 不存在的键不该出现在结果里，而不是以 null 值占位
        assertTrue(
            SourceRef.pluginKey(pluginId, "NOT_EXIST") !in
                store.getMetricMetas(listOf(SourceRef.pluginKey(pluginId, "NOT_EXIST"))),
            "不存在的键不该出现在返回值里",
        )
    }

    @Test
    fun `插件卸载只清元数据不动历史指标`() = runBlocking {
        val store = SqldelightMetricStore(open(), noCustom)
        store.saveMetricMeta(meta("STEPS", "步数", MetricBucket.STEPS))
        store.saveMetric(
            MetricRecord(
                source = SourceRef.Plugin(pluginId, "STEPS", "步数", MetricBucket.STEPS),
                date = "2026-11-19",
                value = 8823.0,
            ),
        )

        val deleted = store.deleteMetricMetaByPlugin(pluginId)
        assertEquals(1, deleted, "应删掉 1 条元数据")
        assertNull(store.getMetricMeta(SourceRef.pluginKey(pluginId, "STEPS")))

        // 历史指标必须还在：用户可能只是想重装插件，
        // 顺手清掉历史数据是不可接受的副作用
        val rows = store.getMetricsInRange("2026-11-19", "2026-11-19")
        assertEquals(1, rows.size, "插件卸载不该删掉历史指标，实际剩 ${rows.size} 行")
        assertEquals(8823.0, rows.single().value, "历史值必须原样保留")
    }

    @Test
    fun `有元数据时读取侧用真实展示名与量纲`() = runBlocking {
        val store = SqldelightMetricStore(open(), noCustom)
        // 心率的 metricKey 不是 MetricBucket 的枚举名，
        // 没有 meta 时会被反解成 COUNT（单位「次」）
        store.saveMetricMeta(meta("OBS_HEART_RATE", "心率", MetricBucket.COUNT, "次/分"))
        store.saveMetric(
            MetricRecord(
                source = SourceRef.Plugin(pluginId, "OBS_HEART_RATE", "心率", MetricBucket.COUNT),
                date = "2026-11-19",
                value = 63.5,
            ),
        )

        val row = store.getMetricsByDate("2026-11-19").single()
        val ref = row.source
        assertTrue(ref is SourceRef.Plugin, "应解析成插件源，实际：$ref")
        assertEquals("心率", ref.displayName, "应使用元数据里的展示名，而不是拼出来的包名")
        assertEquals("次/分", ref.unit, "单位必须来自元数据")
    }

    @Test
    fun `元数据缺失时退回从 key 猜而不是报错`() = runBlocking {
        val store = SqldelightMetricStore(open(), noCustom)
        // 只写指标、不写 meta：模拟「插件还没采集过一次」或「meta 写入失败」
        store.saveMetric(
            MetricRecord(
                source = SourceRef.Plugin(pluginId, "OBS_HEART_RATE", "心率", MetricBucket.COUNT),
                date = "2026-11-19",
                value = 63.5,
            ),
        )

        val row = store.getMetricsByDate("2026-11-19").single()
        val ref = row.source
        assertTrue(ref is SourceRef.Plugin, "缺 meta 也要能解析，不能抛异常")
        assertEquals(
            "$pluginId·OBS_HEART_RATE",
            ref.displayName,
            "缺 meta 时退回拼出来的名字：难看但数据还在，比抛异常好",
        )
    }

    @Test
    fun `SourceKeys 批量解析能带上元数据`() = runBlocking {
        val store = SqldelightMetricStore(open(), noCustom)
        store.saveMetricMeta(meta("OBS_HEART_RATE", "心率", MetricBucket.COUNT, "次/分"))

        val stepsKey = SourceRef.pluginKey(pluginId, "STEPS")
        val heartKey = SourceRef.pluginKey(pluginId, "OBS_HEART_RATE")
        val metas = store.getMetricMetas(listOf(stepsKey, heartKey))

        val resolved = SourceKeys.resolveAll(
            keys = listOf(stepsKey, heartKey),
            customs = emptyList(),
            metas = metas,
        )

        val heart = resolved[heartKey]
        assertNotNull(heart)
        assertEquals("心率", heart.displayName, "有元数据的键应显示真名")

        val steps = resolved[stepsKey]
        assertNotNull(steps)
        assertEquals(
            "$pluginId·STEPS",
            steps.displayName,
            "没有元数据的键仍退回拼名，不影响其它键",
        )
    }
}
