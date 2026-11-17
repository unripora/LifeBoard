package com.lifeboard.shared.repository

import com.lifeboard.shared.repository.model.DashboardCard
import com.lifeboard.shared.repository.model.DashboardTab
import kotlinx.coroutines.flow.Flow

/**
 * 仪表盘布局的**数据访问**契约：页签与卡片。
 *
 * 为什么合成一个接口：卡片带 `tabId` 外键，删除页签必须级联清理卡片。
 * 拆成两个接口后，「先删卡再删页签」的顺序约束只能靠调用方自觉，
 * 漏掉一步就会插入孤儿行或留下一批永远不显示的记录。
 */
interface DashboardStore {

    fun observeTabs(): Flow<List<DashboardTab>>

    suspend fun getTabs(): List<DashboardTab>

    suspend fun getTab(tabId: String): DashboardTab?

    suspend fun countTabs(): Int

    /**
     * 首次使用时写入默认布局：**只在页签表为空时**写 [tabs] 与 [cards]。
     *
     * 判空与写入必须在同一个事务里，否则进程在中间被杀会留下半套布局；
     * 而每次启动无条件写，则会把用户删掉的卡片重新塞回来。
     *
     * @return 写入完成后的全部页签（按 order 升序）
     */
    suspend fun ensureInitialized(
        tabs: List<DashboardTab>,
        cards: List<DashboardCard>,
    ): List<DashboardTab>

    suspend fun saveTab(tab: DashboardTab)

    suspend fun saveTabs(tabs: List<DashboardTab>)

    /** 删除页签，同时删掉它的全部卡片 */
    suspend fun deleteTab(tabId: String)

    suspend fun renameTab(tabId: String, name: String)

    suspend fun setTabOrder(tabId: String, order: Int)

    suspend fun setTabEnabled(tabId: String, enabled: Boolean)

    fun observeCardsByTab(tabId: String): Flow<List<DashboardCard>>

    suspend fun getCardsByTab(tabId: String): List<DashboardCard>

    suspend fun countCards(): Int

    suspend fun saveCard(card: DashboardCard)

    suspend fun saveCards(cards: List<DashboardCard>)

    /**
     * 插入一张新卡，被约束挡住时返回 -1。
     *
     * 与 [saveCard] 的区别：这里**不覆盖**已有卡。
     * 卡片 id 由调用方新造，若用覆盖语义，重复插入只会悄悄替换掉一张已有的卡。
     *
     * 注意 `IGNORE` 对**外键冲突不生效**——插进不存在的页签会直接抛异常，
     * 调用方仍需处理。
     */
    suspend fun insertCard(card: DashboardCard): Long

    suspend fun deleteCard(cardId: String)

    suspend fun deleteCardsByTab(tabId: String): Int

    suspend fun setCardOrder(cardId: String, order: Int)

    suspend fun setCardEnabled(cardId: String, enabled: Boolean)

    suspend fun moveCardToTab(cardId: String, tabId: String)
}
