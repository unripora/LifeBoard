package com.lifeboard.shared.dashboard

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

    /**
     * 自定义图表卡：可叠多条序列，配置全部存在 `DashboardCard.configJson`。
     *
     * 不复用 `sourceKey + metricKey`——那两个字段只能表达一条序列，
     * 而图表卡要能同时画「步数 + 睡眠」，语义对不上。
     */
    CHART,
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
 * ## 为什么这些定义放在 `shared` 而不是 `:server`
 *
 * 页签清单、路由名、默认布局是**产品决策**，不是 Android 实现细节：
 * 桌面端要按同一份清单建页签，只是渲染的具体页面不同。留在 `:server`
 * 会让桌面端为了拿一份常量而依赖整个 Android 层。
 *
 * 路由是字符串而不是枚举，因为分发由各端自己提供，而用户页签根本没有路由；
 * `TabKind` 则在类型上把两者分开，避免靠「名字碰巧不重复」来区分。
 */
object BuiltinTabs {
    const val ROUTE_PANEL = "panel"
    const val ROUTE_RULES = "rules"
    const val ROUTE_TREND = "trend"
    const val ROUTE_METRICS = "metrics"
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
     * 这份清单**只是「有哪些内置页签」**，不代表当前这一端都能渲染它们：
     * 桌面端没有无障碍与通知监听，`notice` / `debug` 那两个页面注册不出来，
     * 但页签行本身仍在库里（用户从手机同步过来时不会丢页签）。
     * 渲染不出来的页签由各端的页面注册表兜底成空态，见 `PageCatalog`。
     */
    val ALL: List<BuiltinTab> = listOf(
        BuiltinTab(TAB_ID_PANEL, ROUTE_PANEL, "面板"),
        BuiltinTab("builtin_rules", ROUTE_RULES, "规则"),
        BuiltinTab("builtin_trend", ROUTE_TREND, "趋势"),
        BuiltinTab("builtin_metrics", ROUTE_METRICS, "指标"),
        BuiltinTab("builtin_remind", ROUTE_REMIND, "提醒"),
        BuiltinTab("builtin_notice", ROUTE_NOTICE, "通知源"),
        BuiltinTab("builtin_plugin", ROUTE_PLUGIN, "插件"),
        BuiltinTab("builtin_debug", ROUTE_DEBUG, "自检"),
        BuiltinTab("builtin_settings", ROUTE_SETTINGS, "设置"),
    )

    /**
     * 已下线但可能还留在老用户库里的页签。
     *
     * 「远程」曾经是内置页签；会话做成插件的 `conversation` 区块之后，
     * 这个入口只剩一份空态，留着只会让用户点进一个永远连不上的页面。
     *
     * 必须显式清理而不是「从 [ALL] 里删掉就算完」：`toItem` 认不出的内置 tabId
     * 会退化成用户页签（卡片容器），用户会看到一个名字叫「远程」、里面能加卡片
     * 的怪东西。清理在 `DashboardLayout.ensureInitialized` 里做，
     * 只在库里确实有这一行时才写——避免每次启动都开一次写事务。
     */
    val RETIRED_TAB_IDS: Set<String> = setOf("builtin_remote")

    fun byRoute(route: String): BuiltinTab? = ALL.firstOrNull { it.route == route }

    fun byTabId(tabId: String): BuiltinTab? = ALL.firstOrNull { it.tabId == tabId }
}

/**
 * 内置页签定义。
 *
 * @param tabId 入库用的稳定标识，不能随改名变化
 * @param route 各端页面注册表分发用的路由
 * @param defaultName 首次安装时的展示名；用户改名后以库里为准
 */
data class BuiltinTab(
    val tabId: String,
    val route: String,
    val defaultName: String,
)
