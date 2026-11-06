package com.lifeboard.server.dashboard

/**
 * 卡片类型。
 *
 * 存进数据库的是枚举名，所以**改名字等于改数据格式**：
 * 要重命名必须同时写迁移，否则老库里的行会对不上任何分支。
 */
enum class CardType {
    /** 今日得分：总分、完成率、连击 */
    SCORE,

    /** 今日待办：任务列表与完成操作 */
    TODO,

    /** 本周得分：七天柱状 */
    WEEK,

    /** 数据源状态：断源/缺权限提示 */
    SOURCE_HEALTH,

    /** 单个指标卡：绑 sourceKey + metricKey，M6b 实现 */
    METRIC,
}

/**
 * 页签种类。
 *
 * 内置页签走代码里的固定页面，用户页签走卡片容器——
 * 两者的渲染路径完全不同，所以必须在类型上分开，
 * 而不是靠「名字碰巧不重复」来区分。
 */
enum class TabKind {
    /** 内置页签：内容由代码决定，用户不能删改 */
    BUILTIN,

    /** 用户页签：内容是一组卡片，可增删改名 */
    USER,
}

/**
 * 内置页签的路由名与展示名。
 *
 * 路由是字符串而不是枚举，因为 `MainActivity` 需要按它分发；
 * 用户页签没有路由（内容全是卡片），靠 `TabKind` 区分。
 */
object BuiltinTabs {
    const val ROUTE_PANEL = "panel"
    const val ROUTE_RULES = "rules"
    const val ROUTE_TREND = "trend"
    const val ROUTE_REMIND = "remind"
    const val ROUTE_NOTICE = "notice"
    const val ROUTE_PLUGIN = "plugin"
    const val ROUTE_DEBUG = "debug"
    const val ROUTE_SETTINGS = "settings"

    /** 面板页签的 tabId。默认布局的卡片全部挂在这里 */
    const val TAB_ID_PANEL = "builtin_panel"

    /**
     * 内置页签的固定清单，顺序即首次安装时的默认顺序。
     *
     * 必须和 `MainActivity` 里的分发表保持一致：
     * 这里多一项而那边没有分支，用户会点到一个空白页。
     */
    val ALL: List<BuiltinTab> = listOf(
        BuiltinTab(TAB_ID_PANEL, ROUTE_PANEL, "面板"),
        BuiltinTab("builtin_rules", ROUTE_RULES, "规则"),
        BuiltinTab("builtin_trend", ROUTE_TREND, "趋势"),
        BuiltinTab("builtin_remind", ROUTE_REMIND, "提醒"),
        BuiltinTab("builtin_notice", ROUTE_NOTICE, "通知源"),
        BuiltinTab("builtin_plugin", ROUTE_PLUGIN, "插件"),
        BuiltinTab("builtin_debug", ROUTE_DEBUG, "自检"),
        BuiltinTab("builtin_settings", ROUTE_SETTINGS, "设置"),
    )

    fun byRoute(route: String): BuiltinTab? = ALL.firstOrNull { it.route == route }

    fun byTabId(tabId: String): BuiltinTab? = ALL.firstOrNull { it.tabId == tabId }
}

/**
 * 内置页签定义。
 *
 * @param tabId 入库用的稳定标识，不能随改名变化
 * @param route `MainActivity` 分发用的路由
 * @param defaultName 首次安装时的展示名；用户改名后以库里为准
 */
data class BuiltinTab(
    val tabId: String,
    val route: String,
    val defaultName: String,
)
