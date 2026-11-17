package com.lifeboard.desktop

import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.shared.db.DatabaseStores
import com.lifeboard.shared.db.DatabaseDriverFactory
import com.lifeboard.shared.db.openDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * 验证插件的原始条目真的落进了 `plugin_raw_record`。
 *
 * 这条路径曾经断过：`DesktopRawRecordSink.stage` 只把条目归约后写 `metric` 表，
 * 根本不碰 `plugin_raw_record`。症状是采集日志写着「写入 8 条」、仪表盘也有数，
 * 但插件详情页的「全部数据」永远是空的——而详情页读的正是后者。
 *
 * 之所以用单测而不是在界面上点：归约那一步要拿到插件的 `MetricSpec`，
 * 得先加载一个真实的 JS 插件；而「写明细」这半段与插件本身无关，
 * 抽成 [DesktopRawRecordSink.writeDetailsLocked] 之后可以直接喂数据。
 */
class DesktopRawRecordSinkTest {

    private fun newStores(): DatabaseStores {
        val dir = Files.createTempDirectory("lifeboard-raw-test").toFile()
        dir.deleteOnExit()
        return DatabaseStores(
            openDatabase(DatabaseDriverFactory(), File(dir, "test.db").absolutePath),
        )
    }

    @Test
    fun `stage 把每条明细写进 plugin_raw_record`() = runBlocking {
        val stores = newStores()
        val sink = DesktopRawRecordSink(stores) { error("这条用例不该碰插件仓库") }

        val date = "2026-11-14"
        sink.writeDetailsLocked(
            pluginId = PLUGIN_ID,
            metricKey = "STEPS",
            records = listOf(
                RawRecord(timestamp = 1_000L, value = 120.0, detail = "上午"),
                RawRecord(timestamp = 2_000L, value = 80.0, detail = "下午"),
            ),
            date = date,
        )

        val rows = stores.pluginStore.getRawRecords(PLUGIN_ID, date, date)
        assertEquals("两条明细都该落库", 2, rows.size)
        assertEquals(listOf(120.0, 80.0), rows.map { it.value })
        assertEquals(listOf("上午", "下午"), rows.map { it.detail })
        // seq 决定同一天内的展示顺序，必须按下标排好
        assertEquals(listOf(0, 1), rows.map { it.seq })
    }

    @Test
    fun `同一天同一指标重复上报是整组替换而不是叠加`() = runBlocking {
        val stores = newStores()
        val sink = DesktopRawRecordSink(stores) { error("这条用例不该碰插件仓库") }
        val date = "2026-11-14"

        sink.writeDetailsLocked(
            PLUGIN_ID, "STEPS",
            listOf(RawRecord(1_000L, 100.0, ""), RawRecord(2_000L, 200.0, "")),
            date,
        )
        sink.writeDetailsLocked(
            PLUGIN_ID, "STEPS",
            listOf(RawRecord(3_000L, 42.0, "")),
            date,
        )

        val rows = stores.pluginStore.getRawRecords(PLUGIN_ID, date, date)
        // 留着重叠会让用户看到两份互相矛盾的数据，所以是替换不是追加
        assertEquals(1, rows.size)
        assertEquals(42.0, rows.single().value, 0.0001)
    }

    @Test
    fun `stage 之后查询痕迹同步存在`() = runBlocking {
        val stores = newStores()
        val sink = DesktopRawRecordSink(stores) { error("这条用例不该碰插件仓库") }
        val date = "2026-11-14"

        sink.writeDetailsLocked(
            PLUGIN_ID, "OBS_HEART_RATE",
            listOf(RawRecord(1_000L, 61.0, "")),
            date,
        )

        // 详情页靠这份痕迹列出「查过但没有数据」的指标
        val queried = stores.pluginStore.getMetricQueries(PLUGIN_ID, date, date)
        assertTrue("查询痕迹里该有 OBS_HEART_RATE", queried.contains("OBS_HEART_RATE"))
    }

    @Test
    fun `空记录与空日期都不写库`() = runBlocking {
        val stores = newStores()
        val sink = DesktopRawRecordSink(stores) { error("这条用例不该碰插件仓库") }

        sink.writeDetailsLocked(PLUGIN_ID, "STEPS", emptyList(), "2026-11-14")
        sink.writeDetailsLocked(PLUGIN_ID, "STEPS", listOf(RawRecord(1L, 1.0, "")), "")

        assertTrue(stores.pluginStore.getRawRecords(PLUGIN_ID, "2026-01-01", "2026-12-31").isEmpty())
    }

    private companion object {
        const val PLUGIN_ID = "com.lifeboard.plugin.mihealth"
    }
}
