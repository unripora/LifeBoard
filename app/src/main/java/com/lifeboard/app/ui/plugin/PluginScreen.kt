package com.lifeboard.app.ui.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.common.DataUriImage
import com.lifeboard.app.ui.theme.GlassCard

/**
 * 插件页。
 *
 * 这里承担三件事：把 `.ext` 装进来、让用户对签名做信任决定、以及撤销。
 * **安装入口必须是系统文件选择器**：插件目录是应用私有路径，
 * 普通用户没法把文件丢进去，"放到某个目录"不能作为唯一安装方式。
 */
@Composable
fun PluginScreen(
    modifier: Modifier = Modifier,
    onOpenDetail: (pluginId: String) -> Unit = {},
) {
    val vm: PluginViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 用 */* 而不是自定义的 .ext 后缀：`ext` 不是已注册的 MIME，
    // 多数文档提供方会把它当未知类型直接过滤掉，用户就选不到自己的插件包。
    // 真正「是不是插件」由安装服务解析 APK 判断，不靠扩展名。
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.onFilePicked(uri) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 卸载和清除凭据都点一下立即生效，而清除凭据不可逆：
    // 误点之后用户只能重新登录一次，所以这两步必须停下来问一句
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        SnackbarHost(snackbar)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("安装插件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "插件与 LifeBoard 以同一身份运行，能读取应用内数据并发起网络请求。" +
                            "只安装你信任来源的插件。装进来不会自动启用，需要你确认签名后才会加载。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("选择插件文件")
                    }
                }
            }

            if (state.installed.isNotEmpty()) {
                item {
                    Text(
                        "已启用",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.installed, key = { it.archiveName }) { row ->
                    InstalledCard(row, vm, onOpenDetail) { confirmAction = it }
                }
            }

            if (state.pending.isNotEmpty()) {
                item {
                    Text(
                        "待处理",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.pending, key = { "pending_" + it.archiveName }) { row ->
                    PendingCard(row, vm)
                }
            }

            if (state.broken.isNotEmpty()) {
                item {
                    Text(
                        "无法加载",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.broken, key = { "broken_" + it.archiveName }) { row ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(row.packageName.ifBlank { row.archiveName })
                        Text(
                            row.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (state.installed.isEmpty() && state.pending.isEmpty() && state.broken.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "还没有安装任何插件。插件以 .ext 文件分发（其实是改了后缀的 APK），" +
                                "点上面的按钮从文件管理或下载目录里选一个。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    state.pendingInstall?.let { pending ->
        OverwriteDialog(pending, onConfirm = vm::confirmOverwrite, onCancel = vm::cancelOverwrite)
    }

    state.login?.let { login ->
        LoginDialog(
            login = login,
            onValueChange = vm::updateLoginValue,
            onSubmit = vm::submitLoginForm,
            onCancel = vm::cancelLogin,
        )
    }

    confirmAction?.let { action ->
        ConfirmDialog(
            action = action,
            onConfirm = {
                when (action) {
                    is ConfirmAction.ClearCredentials -> vm.clearCredentials(action.row)
                    is ConfirmAction.Uninstall -> vm.uninstall(action.row)
                }
                confirmAction = null
            },
            onCancel = { confirmAction = null },
        )
    }
}

/** 需要用户再确认一次的危险操作 */
private sealed interface ConfirmAction {
    val row: PluginRow

    /** 不可逆：删除后插件必须重新登录 */
    data class ClearCredentials(override val row: PluginRow) : ConfirmAction

    data class Uninstall(override val row: PluginRow) : ConfirmAction
}

@Composable
private fun InstalledCard(
    row: PluginRow,
    vm: PluginViewModel,
    onOpenDetail: (pluginId: String) -> Unit,
    onConfirm: (ConfirmAction) -> Unit,
) {
    // 整卡可点进详情页。按钮行单独放在下方且不加 clickable，
    // 点按钮不会因为事件冒泡顺带把详情页打开
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail(row.pluginId) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                statusText(row),
                style = MaterialTheme.typography.labelSmall,
                color = if (row.healthy == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            "${row.packageName} · v${row.versionName} · ${row.metricCount} 项数据",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.healthMessage.isNotBlank()) {
            Text(
                row.healthMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        SignatureBlock(row.signatureHashes)

        if (row.needsLogin) {
            Text(
                row.loginText.ifBlank { "未登录" },
                style = MaterialTheme.typography.labelSmall,
                color = if (row.needsLoginAction) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.needsLoginAction) {
                OutlinedButton(onClick = { vm.startLogin(row) }) { Text("登录") }
            } else if (row.needsLogin) {
                OutlinedButton(onClick = { vm.logout(row) }) { Text("登出") }
            }
            OutlinedButton(onClick = { vm.revokeTrust(row) }) { Text("停止使用") }
            OutlinedButton(onClick = { onConfirm(ConfirmAction.ClearCredentials(row)) }) { Text("清除凭据") }
            OutlinedButton(onClick = { onConfirm(ConfirmAction.Uninstall(row)) }) { Text("卸载") }
        }
    }
}

@Composable
private fun ConfirmDialog(
    action: ConfirmAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val (title, body) = when (action) {
        is ConfirmAction.ClearCredentials -> "清除凭据" to
            "将删除 ${action.row.displayName} 保存的登录凭据，无法恢复。" +
            "下次同步前需要重新登录。插件本身和数据不受影响。"
        is ConfirmAction.Uninstall -> "卸载插件" to
            "将停止使用并删除 ${action.row.displayName} 的插件包，之后可以重新安装。" +
            "已保存的凭据和插件私有数据会保留。"
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    if (action is ConfirmAction.ClearCredentials) "清除" else "卸载",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
private fun PendingCard(row: PendingRow, vm: PluginViewModel) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(row.packageName.ifBlank { row.archiveName }, style = MaterialTheme.typography.titleSmall)
        Text(
            "v${row.versionName} · ${row.reason}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.unsigned) {
            Text(
                "没有签名，无法建立信任，只能删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            SignatureBlock(row.signatureHashes)
            Text(
                "确认这个指纹与你拿到插件的地方一致，再点信任。" +
                    "信任后插件会以应用身份运行，能读取本应用数据。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!row.unsigned) {
                OutlinedButton(onClick = { vm.trust(row) }) { Text("信任并使用") }
            }
            OutlinedButton(onClick = { vm.deletePending(row) }) { Text("删除") }
        }
    }
}

@Composable
private fun SignatureBlock(hashes: List<String>) {
    if (hashes.isEmpty()) return
    Text("签名指纹", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    hashes.forEach {
        Text(
            // 完整 64 位十六进制太长，行内展示前 16 位，够用户与自己手里的值比对前缀
            it.take(32),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusText(row: PluginRow): String = when {
    !row.trusted -> "已停止"
    row.healthy == null -> "尚未采集"
    row.healthy -> "正常"
    else -> "异常"
}

@Composable
private fun OverwriteDialog(
    pending: PendingInstall,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("覆盖已安装的插件") },
        text = {
            Column {
                Text("${pending.packageName} 已经装过 v${pending.existingVersionName}。")
                Text("将换成 v${pending.versionName}，插件数据与凭据会保留。")
                if (pending.signatureHashes.isNotEmpty()) {
                    Text("新包签名：", style = MaterialTheme.typography.labelSmall)
                    Text(
                        pending.signatureHashes.first().take(32),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("覆盖安装") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/**
 * 通用登录表单。
 *
 * 字段完全由插件给出，宿主不知道「账号」和「验证码」有什么区别，
 * 只按 [com.lifeboard.plugin.api.InputField.secret] 决定要不要掩码。
 * 这样小米健康能登录，别的插件换了字段也不用改这里。
 */
@Composable
private fun LoginDialog(
    login: LoginUi,
    onValueChange: (String, String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!login.busy) onCancel() },
        title = { Text("登录 ${login.displayName}") },
        text = {
            Column {
                Text(login.prompt, style = MaterialTheme.typography.labelSmall)

                // 验证码图必须显示在输入框上方：用户要先看图才能填
                login.captchaImage?.let { image ->
                    DataUriImage(
                        dataUri = image,
                        contentDescription = "验证码",
                        errorText = "验证码图片加载失败，请取消后重试",
                    )
                }

                login.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                login.fields.forEach { field ->
                    OutlinedTextField(
                        value = login.values[field.name].orEmpty(),
                        onValueChange = { onValueChange(field.name, it) },
                        label = { Text(field.displayName) },
                        placeholder = field.hint.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                        // 密码和验证码都要掩码：屏幕上明文显示等于泄露
                        visualTransformation = if (field.secret) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        singleLine = true,
                        enabled = !login.busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !login.busy) {
                Text(if (login.busy) "登录中…" else "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !login.busy) { Text("取消") }
        },
    )
}
