package com.lifeboard.core.model

/**
 * 数据源类型。一期仅包含可免解包采集的源；抖音等深度 hook 源留待二期。
 *
 * @property pkg 目标应用包名，用于 Hook 作用域匹配
 * @property displayName 面板展示名称
 */
enum class SourceType(
    val pkg: String,
    val displayName: String,
) {
    /** 屏幕使用时长，走 UsageStatsManager，不依赖任何目标应用 */
    SCREEN_TIME("android", "屏幕使用"),

    /** 飞书办公打卡，通过通知监听识别 */
    FEISHU_ATTENDANCE("com.ss.android.lark", "飞书打卡"),

    /** 多邻国学习签到，通过通知监听识别连胜 */
    DUOLINGO("com.duolingo", "多邻国"),

    /** 小米健康，注入进程内读取本地数据库 */
    MI_HEALTH("com.mi.health", "小米健康"),

    /** Legado 开源阅读，读取 readRecord 阅读时长 */
    LEGADO("io.legado.app", "阅读（Legado）"),

    /** Readest 阅读器，读取 WebView LocalStorage */
    READEST("com.readest", "Readest"),

    /** QcBookLog 用户自有应用，按约定结构对接 */
    QC_BOOK_LOG("com.qc.booklog", "QcBookLog"),

    /** 纯手动打卡，无自动采集 */
    MANUAL("", "手动打卡"),
    ;

    companion object {
        fun fromPkg(pkg: String): SourceType? = entries.firstOrNull { it.pkg == pkg }
    }
}

/**
 * 指标分桶，用于 [com.lifeboard.core.model.MetricRecord] 区分同一数据源下的不同量纲。
 */
enum class MetricBucket {
    /** 时长，单位分钟 */
    DURATION_MINUTES,

    /** 步数 */
    STEPS,

    /** 睡眠时长，单位分钟 */
    SLEEP_MINUTES,

    /** 计数型，如连胜天数、打卡次数 */
    COUNT,

    /** 布尔型，1 表示完成、0 表示未完成 */
    FLAG,
    ;

    /** 该分桶的展示单位 */
    val unit: String
        get() = when (this) {
            DURATION_MINUTES, SLEEP_MINUTES -> "分钟"
            STEPS -> "步"
            COUNT -> "次"
            FLAG -> ""
        }
}
