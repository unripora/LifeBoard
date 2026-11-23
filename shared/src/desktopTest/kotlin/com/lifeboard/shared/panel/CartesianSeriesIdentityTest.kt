package com.lifeboard.shared.panel

import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import kotlin.test.Test
import kotlin.test.assertEquals

class CartesianSeriesIdentityTest {
    @Test
    fun duplicateLabelsProduceValidLineAndColumnModels() {
        val model = buildChartRenderModel(
            requestedSeries = listOf(RequestedSeries("same", "steps"), RequestedSeries("same", "steps")),
            loaded = listOf(series(10.0), series(20.0)),
        )

        // 折线、面积、散点共用 lineModel；柱状图使用 columnModel。
        listOf(false, true).forEach { scatter ->
            val inputs = model.cartesianSeries(scatter)
            val line = LineCartesianLayerModel(
                series = inputs.map { input ->
                    input.points.map { (x, y) -> LineCartesianLayerModel.Entry(x, y) }
                },
                seriesKeys = inputs.map { it.key },
            )
            assertEquals(listOf(0, 1), line.seriesKeys)
            assertEquals(listOf("same", "same"), model.series.map { it.series.label })
        }
        val inputs = model.cartesianSeries(scatter = false)
        val columns = ColumnCartesianLayerModel(
            series = inputs.map { input ->
                input.points.map { (x, y) -> ColumnCartesianLayerModel.Entry(x, y) }
            },
            seriesKeys = inputs.map { it.key },
        )
        assertEquals(listOf(0, 1), columns.seriesKeys)
    }

    @Test
    fun missingSeriesDoesNotChangeRemainingIdentityWhenItReturns() {
        val requested = List(3) { RequestedSeries("same", "steps") }
        val missing = buildChartRenderModel(
            requested,
            listOf(series(null), series(20.0), series(30.0)),
        ).cartesianSeries(scatter = false)
        val recovered = buildChartRenderModel(
            requested,
            listOf(series(10.0), series(20.0), series(30.0)),
        ).cartesianSeries(scatter = false)

        assertEquals(listOf(1, 2), missing.map { it.key })
        assertEquals(listOf(0, 1, 2), recovered.map { it.key })
        assertEquals(missing, recovered.drop(1))
    }

    @Test
    fun duplicateLabelsRetainSeparateAxesAndValueTransforms() {
        val model = buildChartRenderModel(
            listOf(
                RequestedSeries("same", "steps", ChartAxisSlot.LEFT),
                RequestedSeries("same", "minutes", ChartAxisSlot.RIGHT),
            ),
            listOf(series(10.0), series(20.0).copy(unit = "minutes")),
        )
        val inputs = model.cartesianSeries(scatter = false) { placed, value ->
            if (placed.axis == ChartAxisSlot.RIGHT) value / 2 else value
        }

        assertEquals(listOf(0, 1), inputs.map { it.key })
        assertEquals(listOf(10.0, 10.0), inputs.map { it.points.single().second })
        assertEquals(listOf(ChartAxisSlot.LEFT, ChartAxisSlot.RIGHT), model.series.map { it.axis })
    }

    private fun series(value: Double?) = ChartSeries(
        label = "same",
        unit = "steps",
        points = value?.let { listOf(SeriesPoint("2026-11-20", it)) }.orEmpty(),
        status = if (value == null) SeriesStatus.NoData else SeriesStatus.Ok,
    )
}
