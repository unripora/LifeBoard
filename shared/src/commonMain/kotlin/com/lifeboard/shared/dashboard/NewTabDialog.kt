package com.lifeboard.shared.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「+」点开的新建页签弹窗。
 *
 * 只问名字。重命名、删除、排序这些低频操作留在 [TabManagerDialog]，
 * 用一个入口跳过去——新建是高频动作，不该每次都先经过一个管理列表。
 *
 * M3-3.2c 从 `:app` 搬进 `commonMain`：没有任何平台依赖，
 * 与 [TabManagerDialog] 共用同一套校验（[validateTabName] / [MAX_TAB_NAME_LENGTH]）。
 */
@Composable
fun NewTabDialog(
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    // 与 TabManagerDialog 共用同一套校验：这里不让叫的名字，
    // 换个入口也不能叫出来（validateTabName 返回 null 表示合法）
    val valid = validateTabName(name) != null
    val tooLong = name.length > MAX_TAB_NAME_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建页签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("页签名称") },
                    isError = tooLong,
                    supportingText = {
                        // 不截断输入，只说明原因：直接吞字符会让人以为是输入法的问题
                        Text(
                            if (tooLong) {
                                "超出 $MAX_TAB_NAME_LENGTH 个字符，请缩短后再提交"
                            } else {
                                "${name.trim().length}/$MAX_TAB_NAME_LENGTH"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onManage, contentPadding = PaddingValues(0.dp)) {
                    Text("管理现有页签", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid) {
                        onCreate(name.trim())
                        onDismiss()
                    }
                },
                enabled = valid,
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
