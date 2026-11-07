package com.lifeboard.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 页签名归一化（M6b1）。
 *
 * `validateTabName` 同时给「新建」和「改名」用，这两条路径的规则必须一模一样，
 * 否则会出现「新建时不让叫的名字，改个名就能叫」。
 *
 * 这里测真实实现，不再在 androidTest 里复制一份同规则的副本来互锁——
 * 副本只能证明两份代码一致，证明不了生产代码是对的。
 */
class TabNameValidationTest {

    @Test
    fun blankNamesAreRejected() {
        listOf("", " ", "   ", "\t", "\n", " \t\n ").forEach { raw ->
            assertNull("空白名字应判非法：${raw.replace("\n", "\\n")}", validateTabName(raw))
        }
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("待办", validateTabName("  待办  "))
        assertEquals("todo", validateTabName("\ttodo\n"))
    }

    @Test
    fun lengthLimitIsInclusive() {
        val max = MAX_TAB_NAME_LENGTH
        assertEquals("正好 $max 个字符合法", "a".repeat(max), validateTabName("a".repeat(max)))
        assertNull("$max+1 个字符必须被拒", validateTabName("a".repeat(max + 1)))
    }

    @Test
    fun chineseCountsByCharacterNotByte() {
        // 「汉」的 UTF-8 编码是 3 字节，若按字节算，7 个字就超 20 了。
        // 用户看到的是字数，所以只能按字符数限
        val max = MAX_TAB_NAME_LENGTH
        assertEquals("汉".repeat(max), validateTabName("汉".repeat(max)))
        assertNull(validateTabName("汉".repeat(max + 1)))
    }

    @Test
    fun trimHappensBeforeLengthCheck() {
        // 首尾空白不该把名字顶出上限：去掉空白后正好合规，就该放行
        val max = MAX_TAB_NAME_LENGTH
        assertEquals("a".repeat(max), validateTabName("  ${"a".repeat(max)}  "))
    }

    @Test
    fun innerSpacesArePreserved() {
        // 只去首尾，中间的空白是用户起名的一部分
        assertEquals("我的 页签", validateTabName(" 我的 页签 "))
    }

    // ---- 布局状态判定 ----

    @Test
    fun dashboardIsNotEmptyWhileStillLoading() {
        // ready=false 且 tabs 为空 = 还没从库里读出来，不是「页签被删光了」。
        // 这两种情况混在一起，冷启动就会闪一下「恢复默认页签」的异常页
        val loading = DashboardUiState(tabs = emptyList(), ready = false)
        assertEquals(false, loading.empty)
    }

    @Test
    fun dashboardIsEmptyOnlyAfterReady() {
        val wiped = DashboardUiState(tabs = emptyList(), ready = true)
        assertEquals(true, wiped.empty)
    }

    @Test
    fun dashboardWithTabsIsNeverEmpty() {
        val tabs = DashboardDefaults.tabs
        assertEquals(false, DashboardUiState(tabs = tabs, ready = true).empty)
        assertEquals(false, DashboardUiState(tabs = tabs, ready = false).empty)
    }

    @Test
    fun defaultsCoverAllBuiltinTabs() {
        // 首帧兜底用的默认页签必须和落库时的内置页签一一对应，
        // 否则冷启动会先画一批页签、就绪后突然换一批
        assertEquals(
            com.lifeboard.server.dashboard.BuiltinTabs.ALL.map { it.tabId },
            DashboardDefaults.tabs.map { it.tabId },
        )
        assertEquals(
            DashboardDefaults.tabs.indices.toList(),
            DashboardDefaults.tabs.map { it.order },
        )
        assertEquals("默认页签都该标成内置", true, DashboardDefaults.tabs.all { it.isBuiltin })
    }
}
