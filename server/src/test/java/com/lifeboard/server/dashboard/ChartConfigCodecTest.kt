package com.lifeboard.server.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表配置的编解码。
 *
 * 这部分是纯逻辑（只依赖 org.json），不碰 Room 和 Context，可以直接做 JVM 单测。
 * 它值得单独测，因为配置是**用户数据**：写坏了不会报错，只会让已建好的图表
 * 在下次打开时变成一张空卡，而且用户没有任何线索。
 */
class ChartConfigCodecTest {

    private fun sampleConfig() = ChartCardConfig(
        chartType = ChartType.LINE,
        series = listOf(
            ChartSeriesConfig(
                sourceKey = "builtin",
                metricKey = "STEPS",
                label = "步数",
                unit = "步",
                axis = ChartAxis.LEFT,
                colorArgb = 0xFF3F51B5.toInt(),
            ),
            ChartSeriesConfig(
                sourceKey = "mihealth",
                metricKey = "SLEEP",
                label = "睡眠",
                unit = "小时",
                axis = ChartAxis.RIGHT,
            ),
        ),
        timeWindowDays = 90,
        showLegend = false,
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val original = sampleConfig()
        val decoded = ChartConfigCodec.decode(ChartConfigCodec.encode(original))

        assertNotNull(decoded)
        val actual = requireNotNull(decoded)
        assertEquals(original.chartType, actual.chartType)
        assertEquals(original.timeWindowDays, actual.timeWindowDays)
        assertEquals(original.showLegend, actual.showLegend)
        assertEquals(2, actual.series.size)

        val first = actual.series[0]
        assertEquals("builtin", first.sourceKey)
        assertEquals("STEPS", first.metricKey)
        assertEquals("步数", first.label)
        assertEquals("步", first.unit)
        assertEquals(ChartAxis.LEFT, first.axis)
        assertEquals(0xFF3F51B5.toInt(), first.colorArgb)

        val second = actual.series[1]
        assertEquals(ChartAxis.RIGHT, second.axis)
        // 未指定颜色时不应写入，读回来也应为空，交给主题分配
        assertNull(second.colorArgb)
    }

    @Test
    fun decodeReturnsNullForBlankOrBrokenJson() {
        assertNull(ChartConfigCodec.decode(null))
        assertNull(ChartConfigCodec.decode(""))
        assertNull(ChartConfigCodec.decode("   "))
        assertNull(ChartConfigCodec.decode("不是 json"))
        assertNull(ChartConfigCodec.decode("""{"v":1}"""))
    }

    @Test
    fun decodeRejectsUnknownChartType() {
        val raw = """{"v":1,"type":"RADAR","series":[]}"""
        // 雷达图不在支持范围内，认不出来就当损坏配置，而不是猜成折线
        assertNull(ChartConfigCodec.decode(raw))
    }

    @Test
    fun decodeRejectsFutureVersion() {
        val raw = """{"v":${ChartConfigCodec.VERSION + 1},"type":"LINE","series":[]}"""
        // 版本更高说明字段含义可能已变（降级安装），不能按当前规则硬读
        assertNull(ChartConfigCodec.decode(raw))
    }

    @Test
    fun decodeSkipsSeriesWithoutSourceOrMetric() {
        val raw = """
            {"v":1,"type":"LINE","series":[
              {"source":"builtin","metric":"STEPS","label":"步数","unit":"步"},
              {"source":"","metric":"SLEEP","label":"睡眠","unit":"小时"},
              {"source":"builtin","label":"缺指标","unit":"次"}
            ]}
        """.trimIndent()
        val decoded = requireNotNull(ChartConfigCodec.decode(raw))
        // 没有源或指标的序列画不出来，直接丢弃；剩下那条仍要能正常显示
        assertEquals(1, decoded.series.size)
        assertEquals("STEPS", decoded.series[0].metricKey)
    }

    @Test
    fun decodeFallsBackToMetricKeyWhenLabelMissing() {
        val raw = """{"v":1,"type":"COLUMN","series":[{"source":"builtin","metric":"STEPS"}]}"""
        val decoded = requireNotNull(ChartConfigCodec.decode(raw))
        assertEquals("STEPS", decoded.series[0].label)
    }

    @Test
    fun decodeTreatsMissingVersionAsCurrent() {
        // v1 之前还没有版本字段；缺省按当前版本读，保证早期数据不被丢弃
        val raw = """{"type":"AREA","series":[{"source":"s","metric":"m","label":"l","unit":"u"}]}"""
        val decoded = requireNotNull(ChartConfigCodec.decode(raw))
        assertEquals(ChartType.AREA, decoded.chartType)
        assertEquals(1, decoded.series.size)
    }

    @Test
    fun pieConfigCarriesCategorySourceAndTopN() {
        val config = ChartCardConfig(
            chartType = ChartType.PIE,
            series = emptyList(),
            categorySourceKey = "app_usage",
            topN = 5,
        )
        val decoded = requireNotNull(ChartConfigCodec.decode(ChartConfigCodec.encode(config)))
        assertEquals(ChartType.PIE, decoded.chartType)
        assertEquals("app_usage", decoded.categorySourceKey)
        assertEquals(5, decoded.topN)
    }

    @Test
    fun allChartTypesSurviveRoundTrip() {
        ChartType.entries.forEach { type ->
            val config = ChartCardConfig(
                chartType = type,
                series = listOf(
                    ChartSeriesConfig("s", "m", "标签", "个"),
                ),
            )
            val decoded = ChartConfigCodec.decode(ChartConfigCodec.encode(config))
            assertEquals("$type 未能往返", type, requireNotNull(decoded).chartType)
        }
    }

    @Test
    fun radarIsNotAnOfferedChartType() {
        // Vico 不支持雷达图，首版已移出；这条测试防止以后误加回来却没实现渲染
        assertTrue(ChartType.entries.none { it.name == "RADAR" })
        assertFalse(ChartType.entries.isEmpty())
    }

    @Test
    fun configWithoutSeriesIsNotRenderable() {
        // 选了折线却没选指标：存进去只会显示一张空图，用户会以为坏了
        val config = ChartCardConfig(chartType = ChartType.LINE, series = emptyList())
        assertFalse(config.isRenderable())
    }

    @Test
    fun configWithBlankSourceOrMetricIsNotRenderable() {
        val config = ChartCardConfig(
            chartType = ChartType.LINE,
            series = listOf(ChartSeriesConfig("", "", "空", "个")),
        )
        assertFalse(config.isRenderable())
    }

    @Test
    fun validLineConfigIsRenderable() {
        assertTrue(sampleConfig().isRenderable())
    }

    @Test
    fun pieRequiresCategorySourceNotSeries() {
        // 饼图读分类数据，不读 series。只有 series 的饼图配置必须判为不可用
        val onlySeries = ChartCardConfig(
            chartType = ChartType.PIE,
            series = listOf(ChartSeriesConfig("s", "m", "标签", "个")),
        )
        assertFalse(onlySeries.isRenderable())

        val withCategory = onlySeries.copy(categorySourceKey = "app_usage")
        assertTrue(withCategory.isRenderable())
    }

    @Test
    fun invalidTimeWindowIsNotRenderable() {
        // 时间窗只允许 7/30/90；写入其他值说明配置被改坏了
        assertFalse(sampleConfig().copy(timeWindowDays = 45).isRenderable())
        assertTrue(sampleConfig().copy(timeWindowDays = 7).isRenderable())
        assertTrue(sampleConfig().copy(timeWindowDays = 90).isRenderable())
    }

    @Test
    fun taskDependentBuiltinWithoutTaskIsNotRenderable() {
        // 连击没有任务是算不出任何值的。这条以前只写在配置弹窗里，
        // 绕过界面的写入（导入、迁移）会得到一张永远空着的图
        val withoutTask = ChartCardConfig(
            chartType = ChartType.LINE,
            series = listOf(
                ChartSeriesConfig("BUILTIN", "BUILTIN_STREAK", "连击走势", "天"),
            ),
        )
        assertFalse(withoutTask.isRenderable())

        val withTask = withoutTask.copy(
            series = listOf(withoutTask.series.single().copy(taskId = 7L)),
        )
        assertTrue(withTask.isRenderable())
    }

    @Test
    fun builtinWithoutTaskRequirementStaysRenderable() {
        // 每日得分这类内置序列不需要任务，不该被上一条规则误伤
        val config = ChartCardConfig(
            chartType = ChartType.LINE,
            series = listOf(
                ChartSeriesConfig("BUILTIN", "BUILTIN_DAILY_SCORE", "每日得分", "分"),
            ),
        )
        assertTrue(config.isRenderable())
    }

    @Test
    fun oneUnusableSeriesMakesWholeConfigNotRenderable() {
        // 有一条序列算不出来就整张卡判不可用：放它进去只会在图上少一条线，
        // 而用户配的是两条
        val config = ChartCardConfig(
            chartType = ChartType.LINE,
            series = listOf(
                ChartSeriesConfig("STEPS", "STEPS", "步数", "步"),
                ChartSeriesConfig("BUILTIN", "BUILTIN_RULE_COMPLETION", "规则完成率", "%"),
            ),
        )
        assertFalse(config.isRenderable())
    }

    @Test
    fun taskIdSurvivesRoundTrip() {
        // 连击、单条规则完成率这两条序列必须记住看的是哪条任务，
        // 丢了 taskId 就等于把图变成「未指定任务」
        val config = ChartCardConfig(
            chartType = ChartType.LINE,
            series = listOf(
                ChartSeriesConfig(
                    sourceKey = "BUILTIN",
                    metricKey = "BUILTIN_STREAK",
                    label = "连击天数走势 · 每日走路",
                    unit = "天",
                    taskId = 42L,
                ),
            ),
        )
        val decoded = requireNotNull(ChartConfigCodec.decode(ChartConfigCodec.encode(config)))
        assertEquals(42L, decoded.series.single().taskId)
    }

    @Test
    fun seriesWithoutTaskIdStaysNull() {
        // 普通采集序列不该凭空多出一个任务绑定
        val decoded = requireNotNull(
            ChartConfigCodec.decode(ChartConfigCodec.encode(sampleConfig())),
        )
        assertTrue(decoded.series.all { it.taskId == null })
    }

    @Test
    fun versionOneConfigWithoutTaskIdStillDecodes() {
        // v1 写出的配置里没有 taskId 字段。升级后必须照常读出来，
        // 否则已建好的图表会在升级瞬间全部变成「配置已损坏」
        val raw = """{"v":1,"type":"LINE","series":[
            {"source":"builtin","metric":"STEPS","label":"步数","unit":"步"}
        ]}"""
        val decoded = requireNotNull(ChartConfigCodec.decode(raw))
        assertEquals(1, decoded.series.size)
        assertNull(decoded.series.single().taskId)
    }

    @Test
    fun versionOnePieConfigStillDecodes() {
        // 饼图不再要求序列，但老配置里塞过的序列也不应让它读不出来
        val raw = """{"v":1,"type":"PIE","series":[],"categorySource":"app_usage","topN":5}"""
        val decoded = requireNotNull(ChartConfigCodec.decode(raw))
        assertEquals(ChartType.PIE, decoded.chartType)
        assertTrue(decoded.isRenderable())
    }
}
