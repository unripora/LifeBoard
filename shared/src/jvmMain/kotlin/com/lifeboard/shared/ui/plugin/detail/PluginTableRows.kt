package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.TableNode

internal data class TableDisplayRow(val cells: List<String>, val isHeader: Boolean)

/** 列定义提供表头，所有 rows 都是数据，不能吞掉或强调首条记录。 */
internal fun tableDisplayRows(node: TableNode): List<TableDisplayRow> =
    listOf(TableDisplayRow(node.columns.map { it.title }, isHeader = true)) +
        node.rows.map { TableDisplayRow(it, isHeader = false) }
