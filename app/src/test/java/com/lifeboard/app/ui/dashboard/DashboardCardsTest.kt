package com.lifeboard.app.ui.dashboard

import com.lifeboard.shared.dashboard.BuiltinTabs
import com.lifeboard.shared.dashboard.CardType
import com.lifeboard.shared.dashboard.DashboardCards
import com.lifeboard.shared.dashboard.DashboardLayout
import com.lifeboard.shared.repository.model.DashboardCard
import com.lifeboard.shared.repository.model.DashboardTab
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
        DashboardCard(
            cardId = "c_${type.name}_$order",
            type = type.name,
            order = order,
            tabId = tabId,
        )

    private fun metricCard(sourceKey: String, metricKey: String?, tabId: String = "user_1") =
        DashboardCard(
            cardId = "c_METRIC_$sourceKey",
            type = CardType.METRIC.name,
            sourceKey = sourceKey,
            metricKey = metricKey,
            order = 0,
            tabId = tabId,
        )

    private fun tab(tabId: String, name: String, order: Int, isBuiltin: Boolean) =
        DashboardTab(
            tabId = tabId,
            name = name,
            order = order,
            isBuiltin = isBuiltin,
        )

    @Test
    fun metricAndChartAreOfferableAndLast() {
        // 指标卡与图表卡都可以加，且都不是「选了就完」的选项：
        // 前者要再选数据源与指标，后者要再配图表，所以排在最后两位
        assertTrue(CardType.METRIC in DashboardCards.OFFERABLE)
        assertTrue(CardType.CHART in DashboardCards.OFFERABLE)
        assertEquals(
            listOf(CardType.METRIC, CardType.CHART),
            DashboardCards.OFFERABLE.takeLast(2),
        )
    }

    @Test
    fun offerableStartsWithDefaultLayout() {
        // 默认布局里的四种卡必须都能加回来，否则「删掉再加」走不通
        assertTrue(DashboardCards.OFFERABLE.containsAll(DashboardLayout.DEFAULT_CARDS))
    }

    @Test
    fun addableExcludesAlreadyPresent() {
        val existing = listOf(CardType.SCORE, CardType.WEEK)
        assertEquals(
            listOf(CardType.TODO, CardType.SOURCE_HEALTH, CardType.METRIC, CardType.CHART),
            DashboardCards.addableTo(existing),
        )
    }

    @Test
    fun metricAndChartStayAddableAfterOneIsPlaced() {
        // 指标卡绑的是具体指标，图表卡绑的是一整份配置，同一个页签里都可以摆多张
        // （步数 + 睡眠 + 屏幕使用）。之前按类型排除，导致加了一张之后再也加不了第二张
        val existing = DashboardCards.OFFERABLE
        assertEquals(
            listOf(CardType.METRIC, CardType.CHART),
            DashboardCards.addableTo(existing),
        )
    }

    @Test
    fun addableKeepsDeclarationOrder() {
        // 顺序即选择器里的顺序，不能随已有卡片而变，否则每次打开都换一遍位置
        assertEquals(DashboardCards.OFFERABLE, DashboardCards.addableTo(emptyList()))
    }

    @Test
    fun addableNeverFullyEmptyBecauseMetricAlwaysAvailable() {
        // 加卡按钮的显示条件是 addable.isNotEmpty()，指标卡恒在意味着入口不会消失
        assertTrue(DashboardCards.addableTo(DashboardCards.OFFERABLE).isNotEmpty())
    }

    @Test
    fun chartCardIsNeverTreatedAsDuplicate() {
        // 图表卡的身份在 configJson 里，这一层拿不到配置就无从比较。
        // 不按类型一刀切禁止：用户可能确实想并排看两张不同的图表
        val existing = listOf(card(CardType.CHART))
        assertFalse(DashboardCards.isDuplicate(CardType.CHART, null, null, existing))
    }

    @Test
    fun nonMetricDuplicateIsDetectedByType() {
        val existing = listOf(card(CardType.SCORE))
        assertTrue(DashboardCards.isDuplicate(CardType.SCORE, null, null, existing))
        assertFalse(DashboardCards.isDuplicate(CardType.TODO, null, null, existing))
    }

    @Test
    fun sameMetricBindingIsDuplicate() {
        val existing = listOf(metricCard("PLUGIN_p:STEPS", "STEPS"))
        assertTrue(
            DashboardCards.isDuplicate(CardType.METRIC, "PLUGIN_p:STEPS", "STEPS", existing),
        )
    }

    @Test
    fun differentMetricBindingIsNotDuplicate() {
        // 同一个插件的两个指标是两个不同的 sourceKey，不算重复
        val existing = listOf(metricCard("PLUGIN_p:STEPS", "STEPS"))
        assertFalse(
            DashboardCards.isDuplicate(CardType.METRIC, "PLUGIN_p:SLEEP", "SLEEP", existing),
        )
    }

    @Test
    fun blankBindingIsNormalizedBeforeCompare() {
        // 内置源的 metricKey 存的是 null，读出来可能是空串，两者必须等价
        val existing = listOf(metricCard("SCREEN_TIME", null))
        assertTrue(DashboardCards.isDuplicate(CardType.METRIC, "SCREEN_TIME", "", existing))
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
        val tab = tab(tabId = "user_1", name = "我的", order = 1, isBuiltin = false)
        val cards = listOf(
            card(CardType.WEEK, order = 0),
            // 认不出的类型（升级/降级留下的行）要跳过而不是整页报错
            DashboardCard("x", "GONE_TYPE", order = 1, tabId = "user_1"),
            card(CardType.TODO, order = 2),
        )
        assertEquals(
            listOf(CardType.WEEK, CardType.TODO),
            DashboardCards.contentOf(tab, cards),
        )
    }

    @Test
    fun builtinPanelContentComesFromDefaultLayout() {
        val panel = tab(
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
        val rules = tab(
            tabId = "builtin_rules",
            name = "规则",
            order = 1,
            isBuiltin = true,
        )
        assertTrue(DashboardCards.contentOf(rules, listOf(card(CardType.TODO))).isEmpty())
    }
}
