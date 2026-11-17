package com.lifeboard.shared.panel

import com.lifeboard.shared.panel.ChartSeries
import com.lifeboard.shared.panel.SeriesPoint
import com.lifeboard.shared.panel.SeriesStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表渲染输入的整形规则。
 *
 * 这层是纯函数，所以能脱离 Android 环境测。它决定的都是「画出来对不对」的事：
 * 谁走哪根轴、哪些序列被排除、为什么被排除。算错了不会崩，
 * 只会安静地画出一张比例错误的图。
 */
class ChartRenderModelTest {

    private fun series(
        label: String,
        unit: String,
        points: List<Pair<String, Double>>,
        status: SeriesStatus = SeriesStatus.Ok,
    ) = ChartSeries(
        label = label,
        unit = unit,
        points = points.map { SeriesPoint(it.first, it.second) },
        status = status,
    )

    @Test
    fun configuredAxisWinsOverUnitInference() {
        // 两条同单位的序列，配置要求左右各一根。按单位推断会给同一个轴，
        // 用户配的「右边这条」就静默失效了
        val model = buildChartRenderModel(
            listOf(
                RequestedSeries("步数", "步", ChartAxisSlot.LEFT),
                RequestedSeries("爬楼", "步", ChartAxisSlot.RIGHT),
            ),
            listOf(
                series("步数", "步", listOf("2026-11-01" to 10.0)),
                series("爬楼", "步", listOf("2026-11-01" to 3.0)),
            ),
        )
        assertEquals(ChartAxisSlot.LEFT, model.series[0].axis)
        assertEquals(ChartAxisSlot.RIGHT, model.series[1].axis)
        assertEquals("步", model.leftUnit)
        assertEquals("步", model.rightUnit)
    }

    @Test
    fun configuredAxisIsNotReorderedByUnit() {
        // 配置把不同单位的两条都放左轴：这是用户的选择，不能按单位自动拆到右轴
        val model = buildChartRenderModel(
            listOf(
                RequestedSeries("步数", "步", ChartAxisSlot.LEFT),
                RequestedSeries("睡眠", "小时", ChartAxisSlot.LEFT),
            ),
            listOf(
                series("步数", "步", listOf("2026-11-01" to 8000.0)),
                series("睡眠", "小时", listOf("2026-11-01" to 7.5)),
            ),
        )
        assertTrue(model.series.all { it.axis == ChartAxisSlot.LEFT })
        assertEquals(null, model.rightUnit)
    }

    @Test
    fun axisPairingIsByPositionNotByLabel() {
        // 同一个指标加两次（比如两个任务的单条规则完成率）：只能按顺序配对。
        // 按 sourceKey/metricKey 查表会让两条都拿到第一个轴
        val model = buildChartRenderModel(
            listOf(
                RequestedSeries("单条规则完成率 · 早起", "%", ChartAxisSlot.LEFT),
                RequestedSeries("单条规则完成率 · 阅读", "%", ChartAxisSlot.RIGHT),
            ),
            listOf(
                series("单条规则完成率 · 早起", "%", listOf("2026-11-01" to 80.0)),
                series("单条规则完成率 · 阅读", "%", listOf("2026-11-01" to 40.0)),
            ),
        )
        assertEquals(ChartAxisSlot.LEFT, model.series[0].axis)
        assertEquals(ChartAxisSlot.RIGHT, model.series[1].axis)
    }

    @Test
    fun missingRequestedConfigFallsBackToUnitInference() {
        // 老配置或调用方少传时不能直接丢掉序列：回退到按单位推断
        val model = buildChartRenderModel(
            emptyList(),
            listOf(
                series("步数", "步", listOf("2026-11-01" to 8000.0)),
                series("睡眠", "小时", listOf("2026-11-01" to 7.5)),
            ),
        )
        assertEquals(ChartAxisSlot.LEFT, model.series[0].axis)
        assertEquals(ChartAxisSlot.RIGHT, model.series[1].axis)
    }

    @Test
    fun legendFlagIsCarriedIntoModel() {
        val model = buildChartRenderModel(
            listOf(RequestedSeries("步数", "步")),
            listOf(series("步数", "步", listOf("2026-11-01" to 10.0))),
            showLegend = false,
        )
        assertEquals(false, model.showLegend)
    }

    @Test
    fun singleSeriesGoesToLeftAxis() {
        val model = buildChartRenderModel(
            listOf(RequestedSeries("步数", "步")),
            listOf(series("步数", "步", listOf("2026-11-01" to 10.0))),
        )
        assertEquals(ChartAxisSlot.LEFT, model.series.single().axis)
        assertEquals("步", model.leftUnit)
        assertEquals(null, model.rightUnit)
    }

    @Test
    fun sameUnitSharesLeftAxis() {
        // 同单位必须共用一根轴，否则两条线的比例无法互相比较
        val model = buildChartRenderModel(
            listOf(RequestedSeries("步数", "步"), RequestedSeries("爬楼", "步")),
            listOf(
                series("步数", "步", listOf("2026-11-01" to 10.0)),
                series("爬楼", "步", listOf("2026-11-01" to 3.0)),
            ),
        )
        assertTrue(model.series.all { it.axis == ChartAxisSlot.LEFT })
        assertEquals("步", model.leftUnit)
        assertEquals(null, model.rightUnit)
    }

    @Test
    fun differentUnitGoesToRightAxisWithoutConfig() {
        // 没有配置轴时回退到按单位推断：不同单位分到两根轴
        val model = buildChartRenderModel(
            emptyList(),
            listOf(
                series("步数", "步", listOf("2026-11-01" to 8000.0)),
                series("睡眠", "小时", listOf("2026-11-01" to 7.5)),
            ),
        )
        assertEquals(ChartAxisSlot.LEFT, model.series[0].axis)
        assertEquals(ChartAxisSlot.RIGHT, model.series[1].axis)
        assertEquals("步", model.leftUnit)
        assertEquals("小时", model.rightUnit)
    }

    @Test
    fun thirdIncomparableUnitIsDroppedWithWarning() {
        // 只有两根轴。第三种单位不能硬塞进已有轴——那会让比例失真。
        // 正确做法是排除并说明，而不是画一条错的线。
        // 只在回退推断时可能发生：配置里每条序列都明确指了轴
        val model = buildChartRenderModel(
            emptyList(),
            listOf(
                series("步数", "步", listOf("2026-11-01" to 8000.0)),
                series("睡眠", "小时", listOf("2026-11-01" to 7.5)),
                series("次数", "次", listOf("2026-11-01" to 2.0)),
            ),
        )
        assertEquals(2, model.series.size)
        assertEquals(1, model.warnings.size)
        assertTrue(model.warnings[0].contains("次数"))
        assertTrue(model.warnings[0].contains("不兼容"))
    }

    @Test
    fun unavailableSeriesIsExcludedWithReason() {
        val model = buildChartRenderModel(
            listOf(RequestedSeries("步数", "步")),
            listOf(
                series(
                    "步数",
                    "步",
                    emptyList(),
                    SeriesStatus.SourceUnavailable("数据源或指标已不存在"),
                ),
            ),
        )
        assertTrue(model.series.isEmpty())
        assertTrue(model.warnings.single().contains("不存在"))
    }

    @Test
    fun noDataSeriesExplainsWindowIsEmpty() {
        val model = buildChartRenderModel(
            listOf(RequestedSeries("步数", "步")),
            listOf(series("步数", "步", emptyList(), SeriesStatus.NoData)),
        )
        assertTrue(model.series.isEmpty())
        assertTrue(model.warnings.single().contains("时间窗"))
    }

    @Test
    fun partialStatusKeepsSeriesAndWarns() {
        // 手动打卡这类统计会偏低，但数据本身有效，必须画出来 + 说明
        val model = buildChartRenderModel(
            listOf(RequestedSeries("手动打卡次数", "次")),
            listOf(
                series(
                    "手动打卡次数",
                    "次",
                    listOf("2026-11-01" to 2.0),
                    SeriesStatus.Partial("部分历史记录来源不可考"),
                ),
            ),
        )
        assertEquals(1, model.series.size)
        assertTrue(model.warnings.single().contains("不可考"))
    }

    @Test
    fun xLabelsAreUnionOfAllDates() {
        // 用并集而不是交集：一条序列缺几天，不该让其它序列也丢掉那几天
        val model = buildChartRenderModel(
            listOf(RequestedSeries("A", "步"), RequestedSeries("B", "步")),
            listOf(
                series("A", "步", listOf("2026-11-01" to 1.0, "2026-11-03" to 3.0)),
                series("B", "步", listOf("2026-11-02" to 2.0)),
            ),
        )
        assertEquals(listOf("2026-11-01", "2026-11-02", "2026-11-03"), model.xLabels)
    }

    @Test
    fun xLabelsKeepRealDaySpacing() {
        // 横轴从第一个有数据的日期铺到最后一个：11-01 与 11-05 相距 4 天，
        // 中间三天仍然占位，缺口在图上就是真实的宽度
        val model = buildChartRenderModel(
            listOf(RequestedSeries("A", "步")),
            listOf(series("A", "步", listOf("2026-11-01" to 1.0, "2026-11-05" to 5.0))),
        )
        assertEquals(0, model.xIndexByDate.getValue("2026-11-01"))
        assertEquals(4, model.xIndexByDate.getValue("2026-11-05"))
        assertEquals(5, model.xLabels.size)
    }

    @Test
    fun xLabelsStartAtFirstDateWithDataNotWindowStart() {
        // 窗口 30 天但只有最近 3 天有数据时，前 27 天不该变成一大片空白：
        // 横轴从第一个有数据的日期起算，只保留数据之间真实的断档
        val model = buildChartRenderModel(
            listOf(RequestedSeries("A", "步")),
            listOf(
                series(
                    "A",
                    "步",
                    listOf("2026-11-28" to 1.0, "2026-11-29" to 2.0, "2026-11-30" to 3.0),
                ),
            ),
        )
        assertEquals(3, model.xLabels.size)
        assertEquals("2026-11-28", model.xLabels.first())
        assertEquals("2026-11-30", model.xLabels.last())
    }

    @Test
    fun singleDaySeriesHasSingleTick() {
        val model = buildChartRenderModel(
            listOf(RequestedSeries("A", "步")),
            listOf(series("A", "步", listOf("2026-11-07" to 1.0))),
        )
        assertEquals(listOf("2026-11-07"), model.xLabels)
    }

    @Test
    fun gapSplitsSeriesIntoContinuousRuns() {
        // 11-01 与 11-05 之间没有数据：必须拆成两段，否则折线会跨过缺口连成一条直线
        val runs = continuousRuns(
            listOf(
                SeriesPoint("2026-11-01", 1.0),
                SeriesPoint("2026-11-02", 2.0),
                SeriesPoint("2026-11-05", 5.0),
            ),
        )
        assertEquals(2, runs.size)
        assertEquals(listOf("2026-11-01", "2026-11-02"), runs[0].map { it.date })
        assertEquals(listOf("2026-11-05"), runs[1].map { it.date })
    }

    @Test
    fun continuousDatesStayInOneRun() {
        val runs = continuousRuns(
            listOf(
                SeriesPoint("2026-10-31", 1.0),
                SeriesPoint("2026-11-01", 2.0),
            ),
        )
        assertEquals(1, runs.size)
    }

    @Test
    fun emptySeriesHasNoRuns() {
        assertTrue(continuousRuns(emptyList()).isEmpty())
    }

    @Test
    fun scatterHasOneSegmentPerPoint() {
        // 散点表达「这些天分别是多少」，点与点之间没有连续关系，不能连成线
        val chartSeries = series(
            "A",
            "步",
            listOf("2026-11-01" to 1.0, "2026-11-02" to 2.0, "2026-11-03" to 3.0),
        )
        val segments = chartPoints(
            chartSeries,
            mapOf("2026-11-01" to 0, "2026-11-02" to 1, "2026-11-03" to 2),
            scatter = true,
        )
        assertEquals(3, segments.size)
        assertTrue(segments.all { it.size == 1 })
    }

    @Test
    fun lineKeepsSingleSegmentForContinuousDates() {
        val chartSeries = series(
            "A",
            "步",
            listOf("2026-11-01" to 1.0, "2026-11-02" to 2.0),
        )
        val segments = chartPoints(
            chartSeries,
            mapOf("2026-11-01" to 0, "2026-11-02" to 1),
            scatter = false,
        )
        assertEquals(1, segments.size)
        assertEquals(listOf(0.0 to 1.0, 1.0 to 2.0), segments.single())
    }

    @Test
    fun lineSegmentKeepsGapWidth() {
        // 拆段后两段各自的 x 仍按真实天数，缺口不是「一个格子」
        val chartSeries = series(
            "A",
            "步",
            listOf("2026-11-01" to 1.0, "2026-11-04" to 4.0),
        )
        val segments = chartPoints(
            chartSeries,
            mapOf("2026-11-01" to 0, "2026-11-04" to 3),
            scatter = false,
        )
        assertEquals(2, segments.size)
        assertEquals(listOf(0.0 to 1.0), segments[0])
        assertEquals(listOf(3.0 to 4.0), segments[1])
    }

    @Test
    fun pointsOutsideWindowAreDropped() {
        // 落在横轴范围外的点没有位置可放，丢掉比画到边界外更安全
        val chartSeries = series(
            "A",
            "步",
            listOf("2026-10-31" to 1.0, "2026-11-01" to 2.0),
        )
        val segments = chartPoints(
            chartSeries,
            mapOf("2026-11-01" to 0),
            scatter = false,
        )
        assertEquals(1, segments.size)
        assertEquals(listOf(0.0 to 2.0), segments.single())
    }
}
