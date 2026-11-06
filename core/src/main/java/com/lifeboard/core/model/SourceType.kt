package com.lifeboard.core.model

/**
 * 数据源类型。一期仅包含可免解包采集的源；抖音等深度 hook 源留待二期。
 *
 * 只留宿主内部（注入、采集、UI 选择器）需要的信息。
 * 契约层的数据源标识见 `com.lifeboard.plugin.api.SourceRef`，
 * 那里的 `Builtin.key` 就等于本枚举的名字。
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

        /**
         * 按 key（枚举名）反解，认不出返回 null。
         *
         * 宿主内部把契约层的 `SourceRef.Builtin.key` 还原成枚举只有这一个入口，
         * 不要再在各处写 `entries.firstOrNull { it.name == key } ?: MANUAL`。
         */
        fun fromKey(key: String): SourceType? = entries.firstOrNull { it.name == key }
    }
}
