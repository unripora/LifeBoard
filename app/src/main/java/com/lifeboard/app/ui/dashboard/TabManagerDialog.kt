package com.lifeboard.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 页签管理弹窗（M6b1）。
 *
 * 只做页签本身：新增、改名、删除、排序。卡片的新增与拖拽是 M6b2，
 * 不放在这里——两件事混在一个弹窗里，用户看不出「删页签」会连带删掉卡片。
 *
 * ## 内置页签为什么允许改名但不允许删
 *
 * 改名只动 `dashboard_tab.name`，route 分发靠 `tabId` 反查，改名字不影响任何页面。
 * 删除则会让内置页面失去唯一入口（比如设置页），用户再也进不去。
 * 所以删除按钮只出现在用户页签上，这不是隐藏功能，是这类页签本就没有删除语义。
 */
@Composable
fun TabManagerDialog(
    tabs: List<TabRowUi>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (TabRowUi) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    // null = 不在编辑；非 null = 正在改名的那一行
    var renaming by remember { mutableStateOf<TabRowUi?>(null) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<TabRowUi?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("页签管理") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    TabRow(
                        tab = tab,
                        isFirst = index == 0,
                        isLast = index == tabs.lastIndex,
                        onMoveUp = { onMove(tab.tabId, index - 1) },
                        onMoveDown = { onMove(tab.tabId, index + 1) },
                        onRename = { renaming = tab; newName = tab.label },
                        onDelete = { pendingDelete = tab },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "新增页签",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    isError = newName.length > MAX_TAB_NAME_LENGTH,
                    label = { Text("页签名") },
                    supportingText = { Text(tabNameHint(newName)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        onCreate(newName)
                        newName = ""
                    },
                    enabled = validateTabName(newName) != null,
                ) { Text("添加") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )

    renaming?.let { tab ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名页签") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    isError = newName.length > MAX_TAB_NAME_LENGTH,
                    label = { Text("页签名") },
                    supportingText = { Text(tabNameHint(newName)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(tab.tabId, newName)
                        renaming = null
                    },
                    enabled = validateTabName(newName) != null,
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { tab ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除页签") },
            text = { Text("「${tab.label}」以及它上面的卡片都会被删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(tab)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 输入框下方的说明文字。
 *
 * 不做输入截断，只说明原因：直接吞掉超出的字符，用户会以为是自己没打上，
 * 或者以为输入法有问题。
 */
private fun tabNameHint(raw: String): String = when {
    raw.isBlank() -> "输入页签名称，最多 $MAX_TAB_NAME_LENGTH 个字符"
    raw.length > MAX_TAB_NAME_LENGTH ->
        "超出 $MAX_TAB_NAME_LENGTH 个字符，请缩短后再提交"
    else -> "${raw.trim().length}/$MAX_TAB_NAME_LENGTH"
}

/**
 * 管理弹窗里的单行：页签名 + 排序 / 改名 / 删除
 */
@Composable
private fun TabRow(
    tab: TabRowUi,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(tab.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (tab.builtin != null) "内置页签" else "自定义页签",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
        }
        TextButton(onClick = onMoveUp, enabled = !isFirst) { Text("上移") }
        TextButton(onClick = onMoveDown, enabled = !isLast) { Text("下移") }
        TextButton(onClick = onRename) { Text("改名") }
        // 内置页签没有删除语义：删掉之后内置页面就没有入口了
        if (tab.builtin == null) {
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

/**
 * 管理弹窗用的页签视图。
 *
 * 刻意不复用 `MainActivity` 的私有 `TabItem`：那个类带 route 与分发语义，
 * 是主界面的内部模型；弹窗只需要「能显示的页签 + 能不能删」，两边的字段
 * 将来大概率各自演化，耦在一起反而要互相迁就。
 */
data class TabRowUi(
    val tabId: String,
    val label: String,
    /** 内置页签有值，用户页签为 null */
    val builtin: com.lifeboard.server.dashboard.BuiltinTab?,
)
