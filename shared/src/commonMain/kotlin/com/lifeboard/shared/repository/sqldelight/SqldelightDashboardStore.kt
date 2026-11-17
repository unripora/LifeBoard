package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.shared.db.DashboardQueries
import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.repository.DashboardStore
import com.lifeboard.shared.repository.model.DashboardCard
import com.lifeboard.shared.repository.model.DashboardTab
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toModel
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [DashboardStore] 的 SQLDelight 实现。
 *
 * 两处不能改的语义：
 * - `insertCard` 用 `INSERT OR IGNORE`：主键冲突时返回 -1，**不覆盖**已有卡。
 *   若用 REPLACE，重复插入会悄悄替换掉一张已有的卡，用户完全不知道发生了什么
 * - `deleteTab` 显式删两次：外键虽有 CASCADE，但两端 SQLite 的
 *   `foreign_keys` 开关状态不完全可控，显式删一遍是廉价的保险
 */
class SqldelightDashboardStore(db: LifeBoardDb) : DashboardStore {

    private val q: DashboardQueries = db.dashboardQueries

    override fun observeTabs(): Flow<List<DashboardTab>> =
        q.observeAllTabs().asListFlow().map { rows -> rows.map { it.toModel() } }

    override suspend fun getTabs(): List<DashboardTab> =
        q.selectAllTabs().executeAsList().map { it.toModel() }

    override suspend fun getTab(tabId: String): DashboardTab? =
        q.selectTabById(tabId).executeAsOneOrNull()?.toModel()

    override suspend fun countTabs(): Int =
        q.countTabs().executeAsOne().toInt()

    override suspend fun ensureInitialized(
        tabs: List<DashboardTab>,
        cards: List<DashboardCard>,
    ): List<DashboardTab> {
        q.transaction {
            // 判空与写入必须同事务：先 count 再 insert 分两步做，
            // 进程被杀在中间会留下半套布局，而下次启动因为「表非空」再也不会补
            if (q.countTabs().executeAsOne() == 0L) {
                tabs.forEach { tab ->
                    val row = SqldelightMappers.run { tab.toRow() }
                    q.insertTab(
                        tabId = row.tabId,
                        name = row.name,
                        order = row.order,
                        isBuiltin = row.isBuiltin,
                        enabled = row.enabled,
                    )
                }
                cards.forEach { card ->
                    val row = SqldelightMappers.run { card.toRow() }
                    q.insertCard(
                        cardId = row.cardId,
                        type = row.type,
                        sourceKey = row.sourceKey,
                        metricKey = row.metricKey,
                        order = row.order,
                        tabId = row.tabId,
                        configJson = row.configJson,
                        enabled = row.enabled,
                    )
                }
            }
        }
        return getTabs()
    }

    override suspend fun saveTab(tab: DashboardTab) {
        val row = SqldelightMappers.run { tab.toRow() }
        q.insertTab(
            tabId = row.tabId,
            name = row.name,
            order = row.order,
            isBuiltin = row.isBuiltin,
            enabled = row.enabled,
        )
    }

    override suspend fun saveTabs(tabs: List<DashboardTab>) {
        q.transaction {
            tabs.forEach { tab ->
                val row = SqldelightMappers.run { tab.toRow() }
                q.insertTab(
                    tabId = row.tabId,
                    name = row.name,
                    order = row.order,
                    isBuiltin = row.isBuiltin,
                    enabled = row.enabled,
                )
            }
        }
    }

    override suspend fun deleteTab(tabId: String) {
        q.transaction {
            // 先删卡片再删页签：反过来会让中间态出现「卡片指向不存在页签」的孤儿行
            q.deleteCardsByTab(tabId)
            q.deleteTabById(tabId)
        }
    }

    override suspend fun renameTab(tabId: String, name: String) {
        q.renameTab(name, tabId)
    }

    override suspend fun setTabOrder(tabId: String, order: Int) {
        q.setTabOrder(order.toLong(), tabId)
    }

    override suspend fun setTabEnabled(tabId: String, enabled: Boolean) {
        q.setTabEnabled(if (enabled) 1L else 0L, tabId)
    }

    override fun observeCardsByTab(tabId: String): Flow<List<DashboardCard>> =
        q.observeCardsByTab(tabId).asListFlow().map { rows -> rows.map { it.toModel() } }

    override suspend fun getCardsByTab(tabId: String): List<DashboardCard> =
        q.selectCardsByTab(tabId).executeAsList().map { it.toModel() }

    override suspend fun countCards(): Int =
        q.countCards().executeAsOne().toInt()

    override suspend fun saveCard(card: DashboardCard) {
        val row = SqldelightMappers.run { card.toRow() }
        q.insertCard(
            cardId = row.cardId,
            type = row.type,
            sourceKey = row.sourceKey,
            metricKey = row.metricKey,
            order = row.order,
            tabId = row.tabId,
            configJson = row.configJson,
            enabled = row.enabled,
        )
    }

    override suspend fun saveCards(cards: List<DashboardCard>) {
        q.transaction {
            cards.forEach { card ->
                val row = SqldelightMappers.run { card.toRow() }
                q.insertCard(
                    cardId = row.cardId,
                    type = row.type,
                    sourceKey = row.sourceKey,
                    metricKey = row.metricKey,
                    order = row.order,
                    tabId = row.tabId,
                    configJson = row.configJson,
                    enabled = row.enabled,
                )
            }
        }
    }

    override suspend fun insertCard(card: DashboardCard): Long {
        val row = SqldelightMappers.run { card.toRow() }
        // 先查存在性：IGNORE 命中时 last_insert_rowid() 仍是上一次的值，不能靠它判断
        val existed = q.selectCardById(row.cardId).executeAsOneOrNull() != null
        val inserted = if (existed) {
            false
        } else {
            q.insertCardIgnore(
                cardId = row.cardId,
                type = row.type,
                sourceKey = row.sourceKey,
                metricKey = row.metricKey,
                order = row.order,
                tabId = row.tabId,
                configJson = row.configJson,
                enabled = row.enabled,
            )
            // 外键冲突会直接抛异常；走到这里说明插入成功
            true
        }
        return if (inserted) 0L else -1L
    }

    override suspend fun deleteCard(cardId: String) {
        q.deleteCardById(cardId)
    }

    override suspend fun deleteCardsByTab(tabId: String): Int =
        q.deleteCardsByTab(tabId).await().toInt()

    override suspend fun setCardOrder(cardId: String, order: Int) {
        q.setCardOrder(order.toLong(), cardId)
    }

    override suspend fun setCardEnabled(cardId: String, enabled: Boolean) {
        q.setCardEnabled(if (enabled) 1L else 0L, cardId)
    }

    override suspend fun moveCardToTab(cardId: String, tabId: String) {
        q.moveCardToTab(tabId, cardId)
    }
}
