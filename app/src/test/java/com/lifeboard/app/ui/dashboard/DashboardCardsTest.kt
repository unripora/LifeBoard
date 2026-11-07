package com.lifeboard.app.ui.dashboard

import com.lifeboard.server.dashboard.BuiltinTabs
import com.lifeboard.server.dashboard.CardType
import com.lifeboard.server.dashboard.DashboardCards
import com.lifeboard.server.dashboard.DashboardLayout
import com.lifeboard.server.db.DashboardCardEntity
import com.lifeboard.server.db.DashboardTabEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加卡候选与页签内容（M6b2a）。
 *
 * 这一层是纯策略，界面与 [`DashboardViewModel`] 都靠它判断「能不能加」。
 * 放在 JVM 单测里，是因为它不碰数据库也不需要设备——
 * 而真机测试在本项目里跑不了，只写 instrumented 等于没写。
 */
class DashboardCardsTest {

    private fun card(type: CardType, tabId: String = "user_1", order: Int = 0) =
        DashboardCardEntity(
            cardId = "c_${type.name}_$order",
            type = type.name,
            order = order,
            tabId = tabId,
        )

    @Test
    fun metricCardIsNotOfferableYet() {
        // METRIC 卡必须先绑定数据源与指标，M6b2b 才做选择流程。
        // 提前把它列出来，用户点下去只会得到一张永远空着的卡
        assertFalse(CardType.METRIC in DashboardCards.OFFERABLE)
    }

    @Test
    fun offerableCoversDefaultLayout() {
        // 默认布局里的四种卡都必须是可添加的，否则「删掉再加回来」走不通
        assertEquals(DashboardLayout.DEFAULT_CARDS.toSet(), DashboardCards.OFFERABLE.toSet())
    }

    @Test
    fun addableExcludesAlreadyPresent() {
        val existing = listOf(CardType.SCORE, CardType.WEEK)
        assertEquals(
            listOf(CardType.TODO, CardType.SOURCE_HEALTH),
            DashboardCards.addableTo(existing),
        )
    }

    @Test
    fun addableKeepsDeclarationOrder() {
        // 顺序即选择器里的顺序，不能随已有卡片而变，否则每次打开都换一遍位置
        assertEquals(DashboardCards.OFFERABLE, DashboardCards.addableTo(emptyList()))
    }

    @Test
    fun addableIsEmptyWhenAllPresent() {
        assertTrue(DashboardCards.addableTo(DashboardCards.OFFERABLE).isEmpty())
    }

    @Test
    fun onlyPanelBuiltinAcceptsCards() {
        assertTrue(DashboardCards.isCardTab(BuiltinTabs.TAB_ID_PANEL))
        // 其余内置页签是整页，加卡会得到一条永远显示不出来的记录
        assertFalse(DashboardCards.isCardTab("builtin_rules"))
        assertFalse(DashboardCards.isCardTab("builtin_settings"))
        // 用户页签 id 由 createTab 生成，形如 user_<时间戳>
        assertTrue(DashboardCards.isCardTab("user_1730000000000"))
    }

    @Test
    fun unknownTypeStringIsDroppedNotCrashed() {
        assertNull(DashboardCards.parseType("NOT_A_CARD"))
        assertEquals(CardType.TODO, DashboardCards.parseType("TODO"))
    }

    @Test
    fun userTabContentKeepsStoredOrderAndDropsGarbage() {
        val tab = DashboardTabEntity(tabId = "user_1", name = "我的", order = 1, isBuiltin = false)
        val cards = listOf(
            card(CardType.WEEK, order = 0),
            // 认不出的类型（升级/降级留下的行）要跳过而不是整页报错
            DashboardCardEntity("x", "GONE_TYPE", order = 1, tabId = "user_1"),
            card(CardType.TODO, order = 2),
        )
        assertEquals(
            listOf(CardType.WEEK, CardType.TODO),
            DashboardCards.contentOf(tab, cards),
        )
    }

    @Test
    fun builtinPanelContentComesFromDefaultLayout() {
        val panel = DashboardTabEntity(
            tabId = BuiltinTabs.TAB_ID_PANEL,
            name = "面板",
            order = 0,
            isBuiltin = true,
        )
        // 内置页签不看卡片表
        assertEquals(DashboardLayout.DEFAULT_CARDS, DashboardCards.contentOf(panel, emptyList()))
    }

    @Test
    fun builtinPageTabHasNoCardContent() {
        val rules = DashboardTabEntity(
            tabId = "builtin_rules",
            name = "规则",
            order = 1,
            isBuiltin = true,
        )
        assertTrue(DashboardCards.contentOf(rules, listOf(card(CardType.TODO))).isEmpty())
    }
}
