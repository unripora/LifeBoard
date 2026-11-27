package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.TableColumn
import com.lifeboard.plugin.api.TableNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginTableRowsTest {
    @Test
    fun columnTitlesFormHeaderAndFirstDataRowRemainsData() {
        val node = TableNode(
            columns = listOf(TableColumn("名称"), TableColumn("数值")),
            rows = listOf(listOf("首条记录", "42"), listOf("次条记录")),
        )
        val rows = tableDisplayRows(node)
        assertEquals(3, rows.size)
        assertEquals(listOf("名称", "数值"), rows.first().cells)
        assertTrue(rows.first().isHeader)
        assertEquals(node.rows, rows.drop(1).map { it.cells })
        assertTrue(rows.drop(1).none { it.isHeader })
        assertFalse(rows[1].isHeader)
    }

    @Test
    fun emptyTableStillShowsDeclaredColumnTitles() {
        val rows = tableDisplayRows(TableNode(listOf(TableColumn("名称")), emptyList()))
        assertEquals(listOf("名称"), rows.single().cells)
        assertTrue(rows.single().isHeader)
    }
}
