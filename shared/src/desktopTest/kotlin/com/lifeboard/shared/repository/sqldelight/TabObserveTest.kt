package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.shared.db.DatabaseDriverFactory
import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.openDatabase
import com.lifeboard.shared.repository.model.DashboardTab
import com.lifeboard.shared.repository.sqldelight.SqldelightDashboardStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 页签写入必须能被界面看见。
 *
 * 这一组测试钉住的是「点了没反应」类缺陷的根因：
 *
 * 桌面端早先用 `flow { emit(一次快照) }` 读页签，写完库再也不发新值。
 * 于是新增、改名、删除、显示开关、排序全都「写库成功、界面不动」——
 * 用户看到的就是按钮坏了。修法只有一条：订阅 `observeTabs`。
 *
 * 这里不测界面，只测「写库之后订阅方**能收到**新值」这个前提。
 * 前提成立界面才会刷新；前提不成立，界面怎么写都没用。
 */
class TabObserveTest {

    private val dbFile = File.createTempFile("tab-observe-", ".db").apply { delete() }

    private lateinit var db: LifeBoardDb
    private lateinit var store: SqldelightDashboardStore

    private fun open(): SqldelightDashboardStore {
        db = openDatabase(DatabaseDriverFactory(migrate = true), dbFile.absolutePath)
        store = SqldelightDashboardStore(db)
        return store
    }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    /** 与 `DashboardViewModel.createTab` 一致：手工造 id 与 order 再 saveTab */
    private suspend fun addTab(name: String): String {
        val id = "user_${System.currentTimeMillis()}_$name"
        val order = (store.getTabs().maxOfOrNull { it.order } ?: -1) + 1
        store.saveTab(DashboardTab(tabId = id, name = name, order = order, isBuiltin = false))
        return id
    }

    @Test
    fun `新增页签后订阅方能收到新列表`() = runBlocking {
        val store = open()
        store.ensureInitialized(emptyList(), emptyList())
        val before = store.getTabs().size

        addTab("测试页签")

        val after = withTimeout(5_000) { store.observeTabs().first { it.size > before } }
        assertEquals(before + 1, after.size, "新页签应出现在订阅结果里")
        assertTrue(after.any { it.name == "测试页签" }, "列表里应有刚建的页签名")
    }

    @Test
    fun `改名后订阅方能收到新名字`() = runBlocking {
        val store = open()
        store.ensureInitialized(emptyList(), emptyList())
        val id = addTab("原标题")

        store.renameTab(id, "新标题")

        val updated = withTimeout(5_000) {
            store.observeTabs().first { list -> list.any { it.tabId == id && it.name == "新标题" } }
        }
        assertEquals("新标题", updated.first { it.tabId == id }.name, "改名后订阅结果应立刻是新名字")
    }

    @Test
    fun `删除页签后订阅方能收到缩短的列表`() = runBlocking {
        val store = open()
        store.ensureInitialized(emptyList(), emptyList())
        val id = addTab("待删除")
        val before = store.getTabs().size

        store.deleteTab(id)

        val after = withTimeout(5_000) { store.observeTabs().first { list -> list.none { it.tabId == id } } }
        assertEquals(before - 1, after.size, "删除后列表应少一条")
    }

    @Test
    fun `显示开关写库后订阅方能收到 enabled 变化`() = runBlocking {
        val store = open()
        store.ensureInitialized(emptyList(), emptyList())
        val id = addTab("可见性")

        store.setTabEnabled(id, false)

        val after = withTimeout(5_000) {
            store.observeTabs().first { list -> list.any { it.tabId == id && !it.enabled } }
        }
        assertTrue(after.first { it.tabId == id }.enabled.not(), "关掉显示后订阅结果应是 enabled=false")
    }

    @Test
    fun `排序写库后订阅方能收到新顺序`() = runBlocking {
        val store = open()
        store.ensureInitialized(emptyList(), emptyList())
        val id = addTab("要排到最前")
        // 原来 order 是最大值，改到 0 之后应该排第一
        val head = (store.getTabs().minOfOrNull { it.order } ?: 0) - 1

        store.setTabOrder(id, head)

        val after = withTimeout(5_000) {
            store.observeTabs().first { list -> list.firstOrNull()?.tabId == id }
        }
        assertEquals(id, after.first().tabId, "排序后该页签应排在最前")
    }
}
