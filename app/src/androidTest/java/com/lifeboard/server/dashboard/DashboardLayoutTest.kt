package com.lifeboard.server.dashboard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.dashboard.BuiltinTabs
import com.lifeboard.shared.dashboard.DashboardLayout
import com.lifeboard.shared.repository.DashboardStore
import com.lifeboard.shared.repository.model.DashboardCard
import com.lifeboard.shared.repository.model.DashboardTab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪表盘布局的默认值生成。
 *
 * 这段代码最容易出两种错，测试主要盯这两点：
 *
 * 1. **不幂等**：每次启动都写一遍，用户删掉的卡片会自己长回来
 * 2. **顺序错乱**：默认布局与改造前的 `PanelScreen` 不一致，
 *    老用户升级后首屏顺序变了
 */
@RunWith(AndroidJUnit4::class)
class DashboardLayoutTest {

    private lateinit var context: Context
    private lateinit var store: DashboardStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        store = DatabaseProviders.dashboardStore(context)
        clearLayout()
    }

    @After
    fun tearDown() {
        clearLayout()
    }

    private fun clearLayout() = runBlocking {
        store.getTabs().forEach { store.deleteTab(it.tabId) }
        Unit
    }

    @Test
    fun ensureInitialized_createsAllBuiltinTabs() = runBlocking {
        val tabs = DashboardLayout.ensureInitialized(store)

        assertEquals(
            "内置页签必须与 BuiltinTabs.ALL 一一对应，否则会出现点不到的空白页",
            BuiltinTabs.ALL.size,
            tabs.size,
        )
        // 顺序即默认展示顺序
        assertEquals(
            BuiltinTabs.ALL.map { it.tabId },
            tabs.map { it.tabId },
        )
        assertTrue("内置页签都要标成 builtin", tabs.all { it.isBuiltin })
    }

    @Test
    fun ensureInitialized_createsDefaultCardsInPanelOrder() = runBlocking {
        DashboardLayout.ensureInitialized(store)

        val cards = store.getCardsByTab(BuiltinTabs.TAB_ID_PANEL)

        // 顺序必须与 M6a 之前 PanelScreen 的内联顺序一致：
        // 得分 → 待办 → 本周 → 数据源状态
        assertEquals(
            listOf("SCORE", "TODO", "WEEK", "SOURCE_HEALTH"),
            cards.map { it.type },
        )
        assertTrue(
            "默认卡片全部挂在面板页签，否则升级后首屏是空的",
            cards.all { it.tabId == BuiltinTabs.TAB_ID_PANEL },
        )
    }

    @Test
    fun ensureInitialized_isIdempotent() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        val first = store.getCardsByTab(BuiltinTabs.TAB_ID_PANEL).size

        // 模拟用户调整：删掉一张卡
        store.deleteCard("default_week")

        DashboardLayout.ensureInitialized(store)
        val second = store.getCardsByTab(BuiltinTabs.TAB_ID_PANEL)

        assertEquals("第二次调用不该新增卡片", first - 1, second.size)
        assertEquals(
            "用户删掉的卡片不能被重建",
            listOf("SCORE", "TODO", "SOURCE_HEALTH"),
            second.map { it.type },
        )
    }

    @Test
    fun ensureInitialized_doesNotDuplicateOnRepeatedCalls() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        DashboardLayout.ensureInitialized(store)
        DashboardLayout.ensureInitialized(store)

        assertEquals(
            "页签表不能被重复插入",
            BuiltinTabs.ALL.size,
            store.countTabs(),
        )
        assertEquals(4, store.countCards())
    }

    @Test
    fun deletingTab_cascadesItsCards() = runBlocking {
        DashboardLayout.ensureInitialized(store)

        store.saveTab(
            DashboardTab(
                tabId = "user_test",
                name = "测试页",
                order = 99,
                isBuiltin = false,
            ),
        )
        store.saveCard(
            DashboardCard(
                cardId = "card_on_user_tab",
                type = "SCORE",
                order = 0,
                tabId = "user_test",
            ),
        )

        store.deleteTab("user_test")

        assertTrue(
            "删页签必须级联清卡片，否则留下指向不存在页签的孤儿行",
            store.getCardsByTab("user_test").isEmpty(),
        )
    }

    @Test
    fun builtinTabs_routeAndTabIdAreUnique() {
        val routes = BuiltinTabs.ALL.map { it.route }
        val ids = BuiltinTabs.ALL.map { it.tabId }

        assertEquals("路由重复会导致分发到错页面", routes.size, routes.toSet().size)
        assertEquals("tabId 重复会导致持久化的选中态串台", ids.size, ids.toSet().size)
        assertNotNull(BuiltinTabs.byRoute(BuiltinTabs.ROUTE_PANEL))
        assertNotNull(BuiltinTabs.byTabId(BuiltinTabs.TAB_ID_PANEL))
    }
}
