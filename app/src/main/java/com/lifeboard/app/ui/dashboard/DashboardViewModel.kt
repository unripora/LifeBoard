package com.lifeboard.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.lifeboard.server.dashboard.CardType
import com.lifeboard.server.dashboard.DashboardCards
import com.lifeboard.server.dashboard.DashboardLayout
import com.lifeboard.server.db.DashboardCardEntity
import com.lifeboard.server.db.DashboardTabEntity
import com.lifeboard.server.db.LifeBoardDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 仪表盘布局状态。
 *
 * [ready] 为 false 表示默认布局还没落库：界面这时候要拿内存里的默认值顶上，
 * 否则首帧会是一排空页签（或者干脆没有页签，直接闪一下空白）。
 */
data class DashboardUiState(
    val tabs: List<DashboardTabEntity> = emptyList(),
    val ready: Boolean = false,
) {
    /** 页签表已就绪但里面一个页签都没有——只可能是用户全删了 */
    val empty: Boolean get() = ready && tabs.isEmpty()
}

/**
 * 仪表盘布局的读写入口（M6b1）。
 *
 * ## 首帧为什么不直接等数据库
 *
 * `ensureInitialized` 要开事务写默认布局，在 UI 线程同步等它就是主线程 IO，
 * 启动会卡一下；而界面又必须先画出来。所以先给一帧内存默认值（[DashboardDefaults]），
 * 数据库就绪后再整体替换。两边的默认顺序都来自 [DashboardLayout.DEFAULT_CARDS]，
 * 不会出现「闪一下然后顺序变了」。
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val db = LifeBoardDatabase.get(app)

    private val _ready = MutableStateFlow(false)

    val state: StateFlow<DashboardUiState> = combine(
        db.dashboardTabDao().observeAll(),
        _ready,
    ) { tabs, ready ->
        DashboardUiState(tabs = tabs, ready = ready)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    init {
        viewModelScope.launch {
            // 先等下默认布局写完再置 ready，避免「表还空着就已经算就绪」
            withContext(Dispatchers.IO) {
                runCatching { DashboardLayout.ensureInitialized(getApplication()) }
            }
            _ready.value = true
        }
    }

    /**
     * 观察某个页签的卡片。
     *
     * 未就绪时由调用方决定是否用默认卡片顶上，这里只如实返回库里的内容。
     */
    fun observeCards(tabId: String): kotlinx.coroutines.flow.Flow<List<DashboardCardEntity>> =
        db.dashboardCardDao().observeByTab(tabId)

    /**
     * 选择该页签要显示的卡片。
     *
     * 未就绪时返回默认卡片，避免首帧空着；就绪后即使为空也如实返回空，
     * 因为「用户把卡片删光了」和「还没读出来」是两件事，前者不该被默认值填回。
     */
    fun cardsFor(tabId: String, ready: Boolean, cards: List<DashboardCardEntity>): List<CardType> {
        if (!ready && cards.isEmpty() && tabId == com.lifeboard.server.dashboard.BuiltinTabs.TAB_ID_PANEL) {
            return DashboardDefaults.panelCards
        }
        return cards.mapNotNull { runCatching { CardType.valueOf(it.type) }.getOrNull() }
    }

    /**
     * 新增用户页签，成功后回调新页签 id。
     *
     * 名字不合法（空白或超长）时整条操作忽略，不写库。界面应该已经用
     * [validateTabName] 把按钮禁掉了，这里是最后一道防线，
     * 防止其他调用方绕过 UI 直接塞进非法名字。
     */
    fun createTab(name: String, onCreated: (String) -> Unit = {}) {
        val trimmed = validateTabName(name) ?: return
        viewModelScope.launch {
            val tabId = withContext(Dispatchers.IO) {
                // 用时间戳而不是自增：页签表主键是字符串，也没有自增列可用
                val id = "user_${System.currentTimeMillis()}"
                val order = (db.dashboardTabDao().getAll().maxOfOrNull { it.order } ?: -1) + 1
                db.dashboardTabDao().upsert(
                    DashboardTabEntity(tabId = id, name = trimmed, order = order, isBuiltin = false),
                )
                id
            }
            onCreated(tabId)
        }
    }

    /**
     * 改名。内置页签同样允许改名——改名不影响 route 分发，
     * [DashboardTabEntity.tabId] 才是身份。
     *
     * 名称规则与 [createTab] 完全一致，否则会出现「新建时不让叫的名字，改个名就能叫」。
     */
    fun renameTab(tabId: String, name: String) {
        val trimmed = validateTabName(name) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            db.dashboardTabDao().rename(tabId, trimmed)
        }
    }

    /**
     * 删除用户页签，连同它的卡片。
     *
     * 两张表一起动，必须在同一个事务里：只删页签会留下孤儿卡片，
     * 只删卡片则页签变成一个点进去什么都没有的空壳。
     */
    fun deleteTab(tabId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.withTransaction {
                db.dashboardCardDao().deleteByTab(tabId)
                db.dashboardTabDao().deleteById(tabId)
            }
        }
    }

    /**
     * 恢复默认布局。
     *
     * 只用于页签表被清空的异常情况：`ensureInitialized` 的判空条件是
     * 「页签表为空」，所以清空之后再调一次就会重新写入默认页签与默认卡片。
     */
    fun restoreDefaults() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { DashboardLayout.ensureInitialized(getApplication()) }
        }
    }

    /** 把页签挪到 [to] 位置，其余页签顺延。整体重写 order，避免出现重复序号 */
    fun moveTab(tabId: String, to: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val tabs = db.dashboardTabDao().getAll().toMutableList()
            val from = tabs.indexOfFirst { it.tabId == tabId }
            if (from < 0) return@launch
            val target = to.coerceIn(0, tabs.lastIndex)
            if (from == target) return@launch
            tabs.add(target, tabs.removeAt(from))
            db.withTransaction {
                tabs.forEachIndexed { index, tab ->
                    if (tab.order != index) db.dashboardTabDao().setOrder(tab.tabId, index)
                }
            }
        }
    }

    // ---- 卡片（M6b2a） ----

    /**
     * 往页签末尾加一张卡。
     *
     * 三道检查都在这里做，而不是只靠界面把按钮禁掉：
     * 1. 只有卡片页签可以放卡（内置整页放不了，加了也显示不出来）
     * 2. 类型必须在可选清单里
     * 3. 该页签还没有同类型的卡
     *
     * 第 3 条与库里的 (tabId, type) 唯一索引重复，但不是多余的：
     * 这里读一次再写，两次点击各跑一次仍然可能都通过读检查；
     * 索引会在写的时候把后一次挡住，返回 false。
     *
     * @param sourceKey 指标卡绑定的数据源；其他类型必须留空（它们读固定位置的数据）。
     *   留空时指标卡会显示「数据源已不存在」，所以界面必须传
     * @param metricKey 指标卡绑定的具体指标；内置源与自建源留空，
     *   让 [com.lifeboard.server.metric.MetricQueryService] 自己从源推出量纲
     */
    fun addCard(
        tabId: String,
        type: CardType,
        sourceKey: String? = null,
        metricKey: String? = null,
        onAdded: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!DashboardCards.isCardTab(tabId)) return@withContext false
                if (type !in DashboardCards.OFFERABLE) return@withContext false
                // 指标卡没有源就是一张永远显示「源不存在」的卡，不如不加
                if (type == CardType.METRIC && sourceKey.isNullOrBlank()) return@withContext false
                val existing = db.dashboardCardDao().getByTab(tabId)
                if (existing.any { DashboardCards.parseType(it.type) == type }) return@withContext false
                val card = DashboardCardEntity(
                    // 与页签一致用时间戳：卡片表主键是字符串，同一毫秒连点两张会撞，
                    // 所以拼上类型名把冲突概率压到足够低，真撞上了由唯一索引挡住
                    cardId = "card_${System.currentTimeMillis()}_${type.name.lowercase()}",
                    type = type.name,
                    // 非指标卡不存绑定：留着会让「这张卡绑了什么」这种问题在无关的卡上也有答案
                    sourceKey = sourceKey.takeIf { type == CardType.METRIC },
                    metricKey = metricKey.takeIf { type == CardType.METRIC },
                    order = (existing.maxOfOrNull { it.order } ?: -1) + 1,
                    tabId = tabId,
                )
                runCatching { db.dashboardCardDao().insert(card) > 0 }.getOrDefault(false)
            }
            onAdded(ok)
        }
    }

    /**
     * 删除一张卡。
     *
     * 不做「至少留一张」之类的保护：空页签本身就是合法状态，
     * 界面上也给了重新添加的入口。拦着不让删，用户反而以为按钮坏了。
     *
     * 调用方必须给 [tabId]：卡片行删掉之后就再也查不出它原本在哪个页签了，
     * 而删完要压紧该页签的 order，否则「下移」会跳过已被删除的序号，表现成点了没反应。
     */
    fun deleteCard(tabId: String, cardId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.withTransaction {
                db.dashboardCardDao().deleteById(cardId)
                db.dashboardCardDao().getByTab(tabId).forEachIndexed { index, row ->
                    if (row.order != index) db.dashboardCardDao().setOrder(row.cardId, index)
                }
            }
        }
    }

    /**
     * 卡片在页签内上移 / 下移。
     *
     * [delta] 只接受 ±1。整体重写 order 而不是交换两行的值：
     * 老数据里可能有重复或不连续的序号（比如删过卡），交换会把这些毛病留下来。
     */
    fun moveCard(tabId: String, cardId: String, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val cards = db.dashboardCardDao().getByTab(tabId)
            val from = cards.indexOfFirst { it.cardId == cardId }
            if (from < 0) return@launch
            reorder(tabId, from, (from + delta).coerceIn(0, cards.lastIndex))
        }
    }

    /**
     * 把卡片挪到页签内的第 [to] 个位置。拖拽松手时用这个。
     *
     * 与 [moveCardToTab] 差一个词但做的事完全不同：那个是跨页签、改 tabId，
     * 这个是同页签内改顺序。名字接近是因为两件事在用户眼里都是「移动卡片」。
     *
     * [to] 会按当前卡片数夹紧：拖拽过程中列表可能刚好被别人改了
     * （比如另一处删了一张卡），越界的下标不该让整条操作静默失败。
     */
    fun moveCardTo(tabId: String, cardId: String, to: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val cards = db.dashboardCardDao().getByTab(tabId)
            val from = cards.indexOfFirst { it.cardId == cardId }
            if (from < 0) return@launch
            reorder(tabId, from, to)
        }
    }

    /**
     * 页签内把第 [from] 张卡挪到第 [to] 个位置，然后把整页的 order 重写成 0..n-1。
     *
     * 每次都重新读一次库里最新的顺序、按 cardId 定位，而不是相信界面传来的下标：
     * 拖拽从按下到松手之间可能过了好几百毫秒，期间别的入口（按钮排序、
     * 另一个页签的操作）都可能改过库。按 id 找位置，改完的顺序才是对的。
     *
     * 重写而不是只改这两行：order 只要出现重复或空洞，
     * 「上移 / 下移」就会表现成点了没反应。
     */
    private suspend fun reorder(tabId: String, from: Int, to: Int) {
        val cards = db.dashboardCardDao().getByTab(tabId).toMutableList()
        if (from !in cards.indices) return
        val target = to.coerceIn(0, cards.lastIndex)
        if (from == target) return
        cards.add(target, cards.removeAt(from))
        db.withTransaction {
            cards.forEachIndexed { index, card ->
                if (card.order != index) db.dashboardCardDao().setOrder(card.cardId, index)
            }
        }
    }

    /**
     * 把卡片挪到另一个页签的末尾。
     *
     * 目标页签已经有同类型的卡时整条操作放弃：跨页签搬一张「今日得分」过去，
     * 结果是被唯一索引挡住、卡片凭空消失，比直接不让搬更让人困惑。
     * 界面应该先把不可搬的目标页签置灰。
     */
    fun moveCardToTab(cardId: String, fromTabId: String, toTabId: String) {
        if (fromTabId == toTabId) return
        if (!DashboardCards.isCardTab(toTabId)) return
        viewModelScope.launch(Dispatchers.IO) {
            db.withTransaction {
                val card = db.dashboardCardDao().getByTab(fromTabId)
                    .firstOrNull { it.cardId == cardId } ?: return@withTransaction
                val target = db.dashboardCardDao().getByTab(toTabId)
                if (target.any { it.type == card.type }) return@withTransaction
                db.dashboardCardDao().upsert(
                    card.copy(tabId = toTabId, order = (target.maxOfOrNull { it.order } ?: -1) + 1),
                )
                // 源页签补上空洞，否则那边的「下移」会跳过一格
                db.dashboardCardDao().getByTab(fromTabId).forEachIndexed { index, row ->
                    if (row.order != index) db.dashboardCardDao().setOrder(row.cardId, index)
                }
            }
        }
    }
}

/** 页签名最大长度。中英文都按字符数算，不区分宽窄 */
const val MAX_TAB_NAME_LENGTH = 20

/**
 * 校验并归一化页签名，不合法返回 null。
 *
 * 新建和改名共用这一份，避免两条路径的规则慢慢分叉。
 * 长度不在这里截断——截断会让用户敲的字无声消失，
 * 界面应该按 [MAX_TAB_NAME_LENGTH] 禁用按钮并说明原因。
 */
fun validateTabName(raw: String): String? =
    raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TAB_NAME_LENGTH }

/**
 * 内存里的默认布局。
 *
 * 只用于数据库就绪前的那一两帧，以及布局被读成空时的兜底。
 * 顺序直接取自 [DashboardLayout.DEFAULT_CARDS] 与
 * [com.lifeboard.server.dashboard.BuiltinTabs.ALL]，不在这里再抄一份，
 * 否则将来改默认布局要改两个地方，而漏掉的那处只在冷启动时才看得见。
 */
object DashboardDefaults {

    /** 默认页签，id 与入库时一致 */
    val tabs: List<DashboardTabEntity> = com.lifeboard.server.dashboard.BuiltinTabs.ALL
        .mapIndexed { index, tab ->
            DashboardTabEntity(
                tabId = tab.tabId,
                name = tab.defaultName,
                order = index,
                isBuiltin = true,
            )
        }

    /** 面板的默认卡片，顺序与入库时的默认值一致 */
    val panelCards: List<CardType> = DashboardLayout.DEFAULT_CARDS
}
