package com.lifeboard.shared.panel

import com.lifeboard.shared.dashboard.ChartCardConfig
import com.lifeboard.shared.dashboard.ChartConfigCodec
import com.lifeboard.shared.dashboard.ChartDensity
import com.lifeboard.shared.dashboard.ChartSeriesConfig
import com.lifeboard.shared.dashboard.ChartType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartConfigValidationTest {
    @Test
    fun categoryPieSavesAndRoundTripsWithoutMetricSeries() {
        val config = ChartCardConfig(
            title = "App usage",
            chartType = ChartType.PIE,
            series = emptyList(),
            categorySourceKey = CategorySource.APP_USAGE,
            topN = 10,
            timeWindowDays = 7,
            density = ChartDensity.COMPACT,
        )
        assertNull(validateChartConfig(ChartType.PIE, config, loading = false))
        val encoded = assertNotNull(ChartConfigCodec.encode(config))
        val decoded = assertNotNull(ChartConfigCodec.decode(encoded))
        assertEquals(config, decoded)
        assertTrue(decoded.isRenderable())
    }

    @Test
    fun metricOnlyPieDoesNotBypassUnsupportedSourceValidation() {
        val config = ChartCardConfig(
            chartType = ChartType.PIE,
            series = listOf(ChartSeriesConfig("SCREEN_TIME", "MINUTES", "usage", "minutes")),
        )
        assertEquals("分类数据源不可用", validateChartConfig(ChartType.PIE, config, loading = false))
    }
}
