package com.lifeboard.shared.repository.model

/**
 * 仪表盘页签。
 *
 * 为什么这一个不复用 `:core`：`core` 里没有布局概念，
 * 页签与卡片只属于「界面摆在哪」，参与判定与计算的一律没有它。
 * 所以它落在 shared 的存储契约里，不进 `:core`。
 *
 * [tabId] 是稳定标识而非自增主键：页签被卡片引用，
 * 自增 id 在重建布局时会漂移，卡片就会指向错的页签。
 */
data class DashboardTab(
    val tabId: String,
    val name: String,
    val order: Int,
    /** 内置页签不允许删除/改名，只能调顺序 */
    val isBuiltin: Boolean = false,
    /** 是否在顶部页签行里显示；关掉不等于删除 */
    val enabled: Boolean = true,
)

/**
 * 仪表盘卡片。只存「摆哪张、什么类型、绑哪个数据」，不存数据本身。
 *
 * [cardId] 是实例 id，同一类型可以有多张，所以不能用 type 做主键。
 */
data class DashboardCard(
    val cardId: String,
    val type: String,
    /** 仅 METRIC 卡使用，绑定 `SourceRef.key` */
    val sourceKey: String? = null,
    /** 仅 METRIC 卡使用，配合指标元数据反查量纲 / 单位 / 展示名 */
    val metricKey: String? = null,
    val order: Int,
    val tabId: String,
    /** 图表卡的完整配置（JSON），仅 CHART 卡使用，其余类型恒为 null */
    val configJson: String? = null,
    val enabled: Boolean = true,
)
