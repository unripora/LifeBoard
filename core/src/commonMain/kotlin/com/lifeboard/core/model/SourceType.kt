package com.lifeboard.core.model

/**
 * 内置数据源类型，只包含**软件自身就能给出的能力**。
 *
 * 契约层的数据源标识见 `com.lifeboard.plugin.api.SourceRef`，
 * 那里的 `Builtin.key` 就等于本枚举的名字。
 *
 * ## 为什么只有两条
 *
 * 飞书打卡、小米健康、多邻国、Legado、Readest、QcBookLog 这六个第三方来源
 * 曾作为内置源存在，但它们的采集链路已全部下推为 JS 插件。
 * 留在枚举里会产生三类问题：新建规则/任务时能选到一个永远不会再有新数据的源、
 * 「数据指标」页里混进用户根本装不到的东西、以及两套并行的采集实现要一起维护。
 *
 * 删除后它们以插件身份继续工作，数据一律走 `plugin` 前缀的源 key。
 * 历史遗留的 `metric` 行不再被任何代码读取——这是刻意的：
 * 那些行本来就因为内置采集链路停摆而不再增长，保留只会让图表显示一条死线。
 *
 * @property pkg 目标应用包名，用于 Hook 作用域匹配；软件自身能力留空或填自身标记
 * @property displayName 面板展示名称
 */
enum class SourceType(
    val pkg: String,
    val displayName: String,
) {
    /** 屏幕使用时长，走 UsageStatsManager，不依赖任何目标应用 */
    SCREEN_TIME("android", "屏幕使用"),

    /** 纯手动打卡，无自动采集 */
    MANUAL("", "手动打卡"),
    ;

    companion object {
        fun fromPkg(pkg: String): SourceType? = entries.firstOrNull { it.pkg == pkg }

        /**
         * 按 key（枚举名）反解，认不出返回 null。
         *
         * 宿主内部把契约层的 `SourceRef.Builtin.key` 还原成枚举只有这一个入口，
         * 不要再在各处写 `entries.firstOrNull { it.name == key } ?: MANUAL`。
         */
        fun fromKey(key: String): SourceType? = entries.firstOrNull { it.name == key }
    }
}
