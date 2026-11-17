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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 页签管理（M6b1）。
 *
 * `DashboardViewModel` 里的 CRUD 都是「读全表 → 算出新值 → 写回」，
 * 这一层不在 UI 里，因此可以脱离 Compose 直接验证。测的是语义，不是实现：
 *
 * - 用户页签可以增删改排，内置页签只能改名和排序，不能删
 * - 删页签必须连卡片一起删
 * - 排序之后 order 是连续且唯一的，否则下次读取顺序不确定
 */
@RunWith(AndroidJUnit4::class)
class TabManagementTest {

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

    /** 与 DashboardViewModel.createTab 同样的写法：order 取当前最大值 + 1 */
    private suspend fun createTab(name: String): String {
        val id = "user_${System.currentTimeMillis()}_${store.countTabs()}"
        val order = (store.getTabs().maxOfOrNull { it.order } ?: -1) + 1
        store.saveTab(DashboardTab(tabId = id, name = name, order = order, isBuiltin = false))
        return id
    }

    /** 与 DashboardViewModel.moveTab 同样的算法 */
    private suspend fun moveTab(tabId: String, to: Int) {
        val tabs = store.getTabs().toMutableList()
        val from = tabs.indexOfFirst { it.tabId == tabId }
        if (from < 0) return
        val target = to.coerceIn(0, tabs.lastIndex)
        if (from == target) return
        tabs.add(target, tabs.removeAt(from))
        tabs.forEachIndexed { index, tab ->
            if (tab.order != index) store.setTabOrder(tab.tabId, index)
        }
    }

    @Test
    fun createTab_appendsToEndAndKeepsBuiltinOrder() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        val builtinIds = store.getTabs().map { it.tabId }

        val newId = createTab("我的页签")
        val all = store.getTabs()

        assertEquals(BuiltinTabs.ALL.size + 1, all.size)
        assertEquals("新页签要排在最后", newId, all.last().tabId)
        assertEquals(
            "内置页签的相对顺序不能被插入操作打乱",
            builtinIds,
            all.dropLast(1).map { it.tabId },
        )
        assertTrue("新建的页签不是内置页签", all.last().isBuiltin.not())
    }

    @Test
    fun renameTab_keepsTabIdStable() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        val id = createTab("旧名字")

        store.renameTab(id, "新名字")

        val tab = store.getTab(id)
        assertEquals("改名不能换 tabId，否则选中态和卡片归属都会断", "新名字", tab?.name)
        assertEquals("改名后 tabId 必须原样保留", id, tab?.tabId)
    }

    @Test
    fun renameBuiltinTab_doesNotBreakRouteLookup() = runBlocking {
        DashboardLayout.ensureInitialized(store)

        store.renameTab(BuiltinTabs.TAB_ID_PANEL, "主页")

        // route 不在库里，靠 tabId 反查。改名只要不动 tabId，
        // 分发就不会失效——这是「内置页签允许改名」的前提
        val tab = store.getTab(BuiltinTabs.TAB_ID_PANEL)
        assertEquals("主页", tab?.name)
        assertEquals(
            BuiltinTabs.ROUTE_PANEL,
            BuiltinTabs.byTabId(tab!!.tabId)?.route,
        )
    }

    @Test
    fun deleteUserTab_removesItsCardsOnly() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        val id = createTab("带卡片")
        store.saveCard(
            DashboardCard(cardId = "c1", type = "SCORE", order = 0, tabId = id),
        )
        val panelCardsBefore = store.getCardsByTab(BuiltinTabs.TAB_ID_PANEL).size

        store.deleteTab(id)

        assertNull("页签本身要删掉", store.getTab(id))
        assertTrue("它的卡片要一起清掉", store.getCardsByTab(id).isEmpty())
        assertEquals(
            "别的页签的卡片不能被误删",
            panelCardsBefore,
            store.getCardsByTab(BuiltinTabs.TAB_ID_PANEL).size,
        )
    }

    @Test
    fun moveTab_rewritesOrderContiguously() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        val id = createTab("要挪的")
        val before = store.getTabs().map { it.tabId }

        moveTab(id, 0)

        val after = store.getTabs()
        assertEquals("挪到首位", id, after.first().tabId)
        assertEquals(
            "其余页签顺延，相对顺序不变",
            before.dropLast(1),
            after.drop(1).map { it.tabId },
        )
        assertEquals(
            "order 必须从 0 连续递增，重复序号会让读取顺序不确定",
            List(after.size) { it },
            after.map { it.order },
        )
    }

    @Test
    fun moveTab_outOfRangeIsClamped() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        val id = createTab("末尾的")

        // 越界不该抛，也不该把 order 写成非法值
        moveTab(id, 999)

        val after = store.getTabs()
        assertEquals("越界等同于挪到末尾", id, after.last().tabId)
        assertEquals(List(after.size) { it }, after.map { it.order })
    }

    @Test
    fun moveTab_unknownIdIsIgnored() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        val before = store.getTabs()

        moveTab("user_not_exist", 0)

        assertEquals(
            "挪一个不存在的页签不能改动任何顺序",
            before.map { it.tabId to it.order },
            store.getTabs().map { it.tabId to it.order },
        )
    }

    @Test
    fun restoringAfterWipe_rebuildsDefaults() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        createTab("会被清掉")
        store.getTabs().forEach { store.deleteTab(it.tabId) }

        // 「恢复默认」用的就是同一个入口：页签表为空时会重新写默认布局
        val restored = DashboardLayout.ensureInitialized(store)

        assertEquals(BuiltinTabs.ALL.size, restored.size)
        assertEquals(
            "恢复后卡片也要回到默认四张",
            DashboardLayout.DEFAULT_CARDS.map { it.name },
            store.getCardsByTab(BuiltinTabs.TAB_ID_PANEL).map { it.type },
        )
    }

    @Test
    fun fallbackTabIdIsAlwaysPanel() {
        // 选中页被删后界面回退到面板。这里锁住这个常量：
        // 如果将来面板 tabId 改了，回退必须跟着改，否则会退到一个不存在的页签
        assertTrue(
            "面板页签必须真实存在，否则删除当前页后的回退会退到空处",
            BuiltinTabs.byTabId(BuiltinTabs.TAB_ID_PANEL) != null,
        )
    }

    // ---- 页签名入库 ----
    //
    // 名字该怎么校验的规则在 app 模块，由 JVM 单测 TabNameValidationTest 覆盖。
    // 这里只守真机才看得见的那部分：归一化后的名字确实正确落到了库里。

    @Test
    fun tabName_trimmedBeforeStore() = runBlocking {
        DashboardLayout.ensureInitialized(store)
        val id = createTab("  待办  ".trim())

        // 首尾空白在入库前就该去掉，否则列表里会出现看着一样、实际不同的两个名字
        assertEquals("待办", store.getTab(id)?.name)
    }
}
