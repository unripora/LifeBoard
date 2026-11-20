package com.lifeboard.shared.ui.plugin

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.ui.common.DataUriImage
import com.lifeboard.shared.ui.theme.GlassCard
import kotlinx.coroutines.launch

/**
 * 插件页，两端共用。
 *
 * 这里承担三件事：把插件包装进来、让用户对签名做信任决定、以及撤销。
 * **安装入口必须是系统文件选择器**：插件目录是应用私有路径，
 * 普通用户没法把文件丢进去，"放到某个目录"不能作为唯一安装方式。
 *
 * ## 与 Android 原版的差异
 *
 * 选文件不再用 `rememberLauncherForActivityResult` —— 那是 Android 专有的。
 * 改为点击时直接调 [PlatformDependencies.filePicker]：Android 的实现内部
 * 起 Activity Result，桌面端起 `FileDialog`，界面这一层不必知道自己跑在哪。
 *
 * 状态机在 [PluginViewModel] 里，这里只负责渲染与把用户动作转成方法调用。
 *
 * @param onOpenDetail 点卡片进详情页。桌面端可传空实现（详情页尚未落地）
 */
@Composable
fun PluginScreen(
    deps: PlatformDependencies,
    modifier: Modifier = Modifier,
    onOpenDetail: (pluginId: String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // remember(deps)：deps 换掉时（理论上只在测试里发生）重建状态机，
    // 否则会拿着旧宿主去刷一个已经不存在的插件列表
    val vm = remember(deps, scope) { PluginViewModel(deps, scope) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refresh() }

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

        // 引擎缺失时到此为止：不画安装区、不列插件。
        // 原因见 PluginUiState.engineUnavailable——本机跑不了 JS，
        // 摆出「选择插件文件」只会让用户白选一次再失败
        if (state.engineUnavailable) {
            EngineUnavailableNotice()
            return@Column
        }

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
                        // 不传 MIME，两端都不过滤。
                        //
                        // `jsplugin` 不是已注册的扩展名，没有对应的标准 MIME。
                        // Android 的 SAF 收到不认识的类型会把文件置灰——用户明明选对了包，
                        // 列表里却点不动，比不做过滤难排查得多。桌面用的是扩展名过滤器，
                        // 同样会因为不认识 `jsplugin` 把包藏起来。
                        // 包合法与否本来就由安装器解析判定，这里不做预筛更可靠。
                        onClick = {
                            scope.launch {
                                deps.filePicker.pickFile()?.let { vm.onFilePicked(it) }
                            }
                        },
                        // 导入进行中禁用：连续点击会并发解压到同一个插件目录
                        enabled = !state.importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.importing) "正在导入…" else "选择插件文件")
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
                        // 坏包本身没法用，但文件还躺在安装目录里，占着同名位置。
                        // 不给删除入口的话，用户既装不上同名的修复版，也去不掉这条记录，
                        // 只能卸载整个应用——这是必须堵掉的死路。
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.deleteBroken(row) }) { Text("删除") }
                        }
                    }
                }
            }

            if (state.installed.isEmpty() && state.pending.isEmpty() && state.broken.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "还没有安装任何插件。点上面的按钮选一个插件包导入。",
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
            onSubmitEmpty = vm::restartLogin,
            onCancel = vm::cancelLogin,
        )
    }

    confirmAction?.let { action ->
        ConfirmDialog(
            action = action,
            onConfirm = { clearData ->
                when (action) {
                    is ConfirmAction.ClearCredentials -> vm.clearCredentials(action.row)
                    is ConfirmAction.Uninstall -> vm.uninstall(action.row, clearData)
                }
                confirmAction = null
            },
            onCancel = { confirmAction = null },
        )
    }
}

/**
 * 本机跑不了 JS 插件时的说明页。
 *
 * 措辞刻意不出现「ABI」「liblifeboard-js.so」这类术语：用户能做的只有两件事
 * ——换一台设备，或者就这么用（其余功能不受影响）。把技术原因写进说明
 * 只会增加困惑，真正需要排查的人看 logcat 里的 `UnsatisfiedLinkError` 就够了。
 */
@Composable
private fun EngineUnavailableNotice() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("本机不支持插件", style = MaterialTheme.typography.titleMedium)
        Text(
            "插件需要一个只针对部分机型编译的组件，你的设备不在支持范围内。" +
                "待办、屏幕使用、手动打卡等功能不受影响，可以正常使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                // loginText 为空 = 没去问登录态（插件未信任时不去碰它）。
                // 这时不能退回成「未登录」：用户明明登录成功了，界面却写未登录，
                // 只会让人以为是登录失败，而真正的原因是插件还没被信任。
                row.loginText.ifBlank { "登录状态待确认" },
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
            } else if (row.needsLogin && !row.authStateUnknown) {
                // authStateUnknown 时不给「登出」：登录态都没问出来，
                // 这个按钮对应的动作是未知的，点了会去清一份可能正在用的凭据
                OutlinedButton(onClick = { vm.logout(row) }) { Text("登出") }
            }
            OutlinedButton(onClick = { vm.collectNow(row) }) { Text("立即同步") }
            OutlinedButton(onClick = { vm.revokeTrust(row) }) { Text("停止使用") }
            OutlinedButton(onClick = { onConfirm(ConfirmAction.ClearCredentials(row)) }) { Text("清除凭据") }
            OutlinedButton(onClick = { onConfirm(ConfirmAction.Uninstall(row)) }) { Text("卸载") }
        }
    }
}

/**
 * 危险操作的二次确认。
 *
 * 卸载给两个出口而不是一个开关：数据保留与否是**用户此刻就要决定的事**，
 * 用一个默认勾选框很容易被忽略，而两种选择的后果差别很大
 * （重装后是否还要重新登录、历史曲线还在不在）。
 */
@Composable
private fun ConfirmDialog(
    action: ConfirmAction,
    /** clearData 只对卸载有意义；清除凭据时忽略 */
    onConfirm: (clearData: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val (title, body) = when (action) {
        is ConfirmAction.ClearCredentials -> "清除凭据" to
            "将删除 ${action.row.displayName} 保存的登录凭据，无法恢复。\n\n" +
            "与「登出」的区别：登出由插件自己执行，清什么由插件决定；" +
            "这里由宿主直接把凭据全部删掉，插件没实现登出也能清干净。" +
            "插件本身与已采集的数据不受影响。"
        is ConfirmAction.Uninstall -> "卸载插件" to
            "将停止使用并删除 ${action.row.displayName} 的插件包，之后可以重新安装。\n\n" +
            "「仅卸载」会保留已采集的数据、插件私有文件与登录凭据，重装后可以直接接着用；" +
            "「卸载并清除数据」会把这些一起删掉，重装后需要重新登录。"
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Row {
                if (action is ConfirmAction.Uninstall) {
                    TextButton(onClick = { onConfirm(false) }) { Text("仅卸载") }
                    TextButton(onClick = { onConfirm(true) }) {
                        Text("卸载并清除数据", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { onConfirm(false) }) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                }
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
            // 完整 64 位十六进制太长，行内展示前 32 位，够用户与自己手里的值比对前缀
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
    onSubmitEmpty: () -> Unit,
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
            Row {
                // 停在验证码 / 二次验证那一步时，必须给一条回到账号密码的路。
                //
                // 「取消」只是关掉对话框，插件里那一轮中间状态还在，
                // 再点登录又会回到同一个验证码界面——用户就卡死在这一步了。
                // 重新开始的语义由插件定义：这里只把空表单提交上去。
                if (login.canRestart) {
                    TextButton(
                        onClick = onSubmitEmpty,
                        enabled = !login.busy,
                    ) { Text("重新输入账号") }
                }
                TextButton(onClick = onCancel, enabled = !login.busy) { Text("取消") }
            }
        },
    )
}
