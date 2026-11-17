package com.lifeboard.shared.panel.chart

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lifeboard.shared.dashboard.ChartType
import com.lifeboard.shared.panel.CategoryBreakdown
import com.lifeboard.shared.panel.ChartRenderModel

/**
 * 按图表形态分派到具体渲染。
 *
 * 这是唯一的形态分派点：上层只给 `ChartType`，不关心底下是 Vico 还是自绘。
 * 加新形态必须同时在这里加分支，`when` 的穷尽性能保证漏不掉。
 *
 * ## 为什么是 expect
 *
 * 笛卡尔图（折线/面积/散点/柱状）用 Vico 画，而 Vico 只有 Android 构件。
 * 饼图与环形图是纯 Canvas 自绘，本可以共享，但它们和笛卡尔图必须走进同一个
 * 分派函数（否则上层要按形态区分「该调哪个入口」，形态一多就会漏）。
 *
 * 所以分派入口声明成 expect，两端各给一份实现：
 * - Android：Vico + Canvas，功能完整
 * - Desktop：Canvas 自绘折线/柱/饼/环（`ChartRenderer.desktop.kt`）
 *
 * 两端都不认得的形态返回空——**静默不画**，而不是画个假图。
 *
 * @param type 图表形态
 * @param model 时间序列的渲染输入（饼图不用）
 * @param categories 分类占比（仅饼图使用）
 * @param ringTarget 环形进度的目标值；null 表示无法计算百分比
 */
@Composable
expect fun ChartRenderer(
    type: ChartType,
    model: ChartRenderModel?,
    categories: CategoryBreakdown? = null,
    ringTarget: Double? = null,
    modifier: Modifier = Modifier,
)
