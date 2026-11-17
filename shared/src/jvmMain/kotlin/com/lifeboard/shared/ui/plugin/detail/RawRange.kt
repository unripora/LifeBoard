package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.RawRecord

/**
 * 一个指标的原始条目列表。
 *
 * @property displayName 展示名取自插件声明的指标名
 * @property unit 该指标的单位，数值格式化时用
 */
data class RawRecordGroup(
    val metricKey: String,
    val displayName: String,
    val unit: String,
    val records: List<RawRecord>,
)

/**
 * 「全部数据」块的时间段。
 *
 * 用枚举而不是直接传日期范围：界面上的快捷按钮是有限的几种，
 * 枚举能让「选中态」和「按钮文案」都从同一处来，不会出现按钮说「近 7 天」
 * 而实际查了 8 天这类不一致。
 *
 * [Custom] 承载弹窗里选的具体起止日期；[from]/[to] 用 `yyyy-MM-dd` 字符串，
 * 与库里 `date` 列的存储格式一致，可以直接做 BETWEEN 比较。
 */
sealed interface RawRange {

    /** 展示用的按钮文案 */
    val label: String

    data object Today : RawRange {
        override val label = "今天"
    }

    data object Yesterday : RawRange {
        override val label = "昨天"
    }

    data object Last7Days : RawRange {
        override val label = "近 7 天"
    }

    data object Last30Days : RawRange {
        override val label = "近 30 天"
    }

    data object All : RawRange {
        override val label = "全部"
    }

    /**
     * 自定义起止日期。
     *
     * @property from 起始日期（含），`yyyy-MM-dd`
     * @property to 结束日期（含），`yyyy-MM-dd`
     */
    data class Custom(val from: String, val to: String) : RawRange {
        // 按钮上显示区间本身，用户一眼能确认自己选的是哪段；
        // 起止同一天时只显示一次，否则「11-09 ~ 11-09」看着像选错了
        override val label: String get() = if (from == to) from else "$from ~ $to"
    }
}
