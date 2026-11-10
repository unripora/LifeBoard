package com.lifeboard.server.dashboard

import com.lifeboard.server.db.DashboardCardEntity
import com.lifeboard.server.db.DashboardTabEntity

/**
 * 页签能放哪些卡、能放几张。
 *
 * 这里是纯策略，不碰数据库：界面拿它算出「添加卡片」里该列出什么，
 * `DashboardViewModel` 拿它兜住绕过界面的调用。两边共用一份规则，
 * 才不会出现「界面上没有这个选项，但库里被塞进去了」。
 */
object DashboardCards {

    /**
     * 内置页签固定绑定的内容。
     *
     * 不放进 `DashboardLayout.DEFAULT_CARDS`，因为那一份是「首次安装往卡片表里写什么」，
     * 而这里是「这个内置页签的页面本身就是这些内容」。两者将来只会越来越不同：
     * 面板以后可以删卡片，规则页永远是规则页。
     */
    private val BUILTIN_CONTENT: Map<String, List<CardType>> = mapOf(
        BuiltinTabs.TAB_ID_PANEL to DashboardLayout.DEFAULT_CARDS,
        // 其余内置页签是整页而不是卡片容器，内容不归这个模型表达，
        // 所以这里不列它们——查不到就是空，界面按「这一页没有卡片」处理
    )

    /**
     * 可作为卡片放进页签的内容，也就是「添加卡片」里列出的东西。
     *
     * ## 为什么只有这几项
     *
     * 内置页签里有五个是完整页面（规则、趋势、提醒、通知源、插件……），
     * 它们不是卡片：把整个页面塞进一张卡既没有排版意义，也做不到「两张卡并排」。
     * 所以可选清单只有面板上原有的四种卡片，加上 M6b2b 的指标卡。
     *
     * 指标卡要额外选数据源与指标，界面在选中它之后再弹一次选择——
     * 这就是它排在最后的原因：它不是一个「选了就完」的选项。
     */
    val OFFERABLE: List<CardType> = listOf(
        CardType.SCORE,
        CardType.TODO,
        CardType.WEEK,
        CardType.SOURCE_HEALTH,
        CardType.METRIC,
    )

    /** 卡片类型的展示名。放这里而不是界面层：弹窗与卡片标题要用同一个称呼 */
    fun labelOf(type: CardType): String = when (type) {
        CardType.SCORE -> "今日得分"
        CardType.TODO -> "今日待办"
        CardType.WEEK -> "本周得分"
        CardType.SOURCE_HEALTH -> "数据源状态"
        CardType.METRIC -> "指标"
    }

    /**
     * 某个页签里还没被占用的卡片类型。
     *
     * 已经放过的类型不再列出——一张「今日得分」卡在同一个页签里放两张没有任何意义，
     * 数据库那边也有 (tabId, type) 的局部唯一索引兜着（指标卡除外，见 [DashboardCardEntity]）。
     *
     * **指标卡永远可加**，只要还有可选的数据源。它绑的是某个源的具体指标，
     * 一个页签里摆「步数 / 睡眠 / 屏幕使用」是正常需求；
     * 「同一个指标放两张」由 [isDuplicate] 挡，而不是在这里按类型排除。
     *
     * @param existing 该页签已有的卡片类型（未能识别的类型应已被过滤掉）
     */
    fun addableTo(existing: List<CardType>): List<CardType> =
        OFFERABLE.filter { it == CardType.METRIC || it !in existing }

    /**
     * 这张卡是否与页签里已有的某张卡重复。
     *
     * 判重口径随类型而变：
     * - 指标卡：**同一个数据源 + 同一个指标**才算重复。同一个指标放两张，
     *   读出来是同一个数字，纯属重复；不同指标则完全合法
     * - 其余类型：同类型即重复，它们读的是固定位置的数据，没有更细的身份
     *
     * 与数据库的唯一索引是两层保障，但**含义不同**：索引只管到 type，
     * 这里能管到「指标卡绑的是哪个指标」。所以指标卡的真正去重只在这里生效。
     *
     * @param type 要加的卡片类型
     * @param sourceKey 要加的卡片绑定的数据源（仅指标卡有）
     * @param metricKey 要加的卡片绑定的指标（仅指标卡有）
     * @param existing 该页签已有的卡片行
     */
    fun isDuplicate(
        type: CardType,
        sourceKey: String?,
        metricKey: String?,
        existing: List<DashboardCardEntity>,
    ): Boolean {
        if (type != CardType.METRIC) {
            return existing.any { parseType(it.type) == type }
        }
        // 指标卡没有源就是一张永远显示「源不存在」的卡，调用方应先挡掉；
        // 走到这里时按源与指标一起比
        val normalizedSource = sourceKey?.takeIf { it.isNotBlank() }
        val normalizedMetric = metricKey?.takeIf { it.isNotBlank() }
        return existing.any { row ->
            parseType(row.type) == CardType.METRIC &&
                row.sourceKey?.takeIf { it.isNotBlank() } == normalizedSource &&
                row.metricKey?.takeIf { it.isNotBlank() } == normalizedMetric
        }
    }

    /**
     * 这个页签上可以放卡片吗。
     *
     * 内置页签里只有面板是卡片容器，其余都是完整页面，往它们身上加卡
     * 会得到一条永远显示不出来的记录。[BuiltinTabs.byTabId] 认不出来的 id
     * 就是用户自己建的页签（见 `DashboardViewModel.createTab`），它们只能是卡片容器。
     */
    fun isCardTab(tabId: String): Boolean =
        BuiltinTabs.byTabId(tabId)?.let { it.tabId == BuiltinTabs.TAB_ID_PANEL } ?: true

    /**
     * 按库里的页签行还原它要显示的内容。
     *
     * 内置页签回答的是「这个页面显示什么」，用户页签回答的是「这个页签摆了哪些卡」，
     * 两者都归约成同一个 `List<CardType>`，界面就不用分两条渲染路径。
     *
     * 内置页签不看卡片表：面板的卡片由 `PanelScreen` 自己按 tabId 读，
     * 规则页之类的页面里就算混进了卡片行也一并不显示——库里可能因为历史原因
     * 留着这样的行，但它不该改变这个页签「是一整页」的事实。
     *
     * @param storedCards 用户页签自己卡片表里的行（按 order 排好）
     */
    fun contentOf(tab: DashboardTabEntity, storedCards: List<DashboardCardEntity>): List<CardType> {
        if (tab.isBuiltin) return BUILTIN_CONTENT[tab.tabId] ?: emptyList()
        return storedCards.mapNotNull { parseType(it.type) }
    }

    /**
     * 把库里存的类型名解析回枚举。
     *
     * 解析不了返回 null 而不是抛：类型名是数据，改动枚举（或从更高的版本降级回来）
     * 都会留下对不上的行。丢掉比崩溃好，下一次保存布局时会把它自然清掉。
     */
    fun parseType(raw: String): CardType? = runCatching { CardType.valueOf(raw) }.getOrNull()
}

/**
 * 这张卡绑定的数据源。
 *
 * 只有 METRIC 卡有绑定；其他四张卡读的是固定位置的数据（今日判定、待办表、daily_record），
 * 所以它们在这一层就没有「数据源」这个概念，而不是绑一个写死的值。
 */
data class CardBinding(
    val sourceKey: String,
    val metricKey: String?,
)

/** 取一张卡片的数据源绑定，没有绑定返回 null */
fun DashboardCardEntity.bindingOrNull(): CardBinding? {
    val source = sourceKey?.takeIf { it.isNotBlank() } ?: return null
    return CardBinding(sourceKey = source, metricKey = metricKey?.takeIf { it.isNotBlank() })
}
