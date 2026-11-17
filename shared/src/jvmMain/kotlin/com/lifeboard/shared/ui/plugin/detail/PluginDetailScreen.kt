package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.ui.theme.GlassCard

/** 详情页里需要二次确认的危险操作 */
private sealed interface Danger {
    val label: String

    data object Revoke : Danger {
        override val label = "停止使用"
    }

    data object ClearCredentials : Danger {
        override val label = "清除凭据"
    }

    data object Uninstall : Danger {
        override val label = "卸载"
    }
}

/**
 * 插件详情页。
 *
 * 全屏覆盖层，自带背景与顶栏，返回由顶栏按钮与各平台的返回手势/按键处理。
 * 不引 Navigation 库——两个平台各只有一个覆盖层，为它引一套路由不划算。
 *
 * ## ViewModel 为什么由调用方传入
 *
 * 放进 commonMain 之后就不能依赖 `androidx.lifecycle`：桌面端没有这个库，
 * `viewModel()` / `collectAsStateWithLifecycle()` 都用不了。
 * 改成调用方持有 VM 并传进来，代价是调用方要在离开时调 [PluginDetailViewModel.close]。
 *
 * 本页只展示**宿主掌握的**通用信息与通用操作，插件自定义区块由
 * [UiBlockList] 渲染。
 *
 * **不提供登录入口**：登录是多轮状态机，列表页已有一套完整实现。
 * 这里只读展示登录态，并引导用户回列表页操作。
 *
 * @param onClose 关闭详情页回到列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(
    vm: PluginDetailViewModel,
    onClose: () -> Unit,
    /** 各平台的返回处理：Android 注册返回键，桌面端可空实现 */
    backHandler: @Composable (onBack: () -> Unit) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var danger by remember { mutableStateOf<Danger?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 卸载/撤销后插件已不属于列表，直接退回列表页，不要留在一个空壳页面上
    LaunchedEffect(state.closed) { if (state.closed) onClose() }

    backHandler(onClose)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(state.info?.displayName ?: "插件详情")
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        // 用文字「←」而不是 Icons.AutoMirrored.Filled.ArrowBack：
                        // `material-icons-core` 不在共享层依赖里（桌面端也没有），
                        // 与仪表盘「+」、设置页「✓」的处理保持一致
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 内容超出一屏必须能滚动：桌面端没有触摸滑动，靠鼠标滚轮走
                // 这里的滚动容器。否则「全部数据」与「操作」两块既看不到也点不到
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val info = state.info
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(16.dp))
                info == null -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "这个插件已经不在列表里了，可能刚被卸载。返回后重新扫描即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    InfoSection(info)
                    // 全部数据必须在插件自定义区块**之前**：这是宿主对「采集到了什么」的
                    // 权威汇总，插件自己的区块是它挑出来讲的细节。顺序反了的话，
                    // 用户会以为插件没展示的数据就不存在
                    AllDataBlock(
                        groups = state.rawGroups,
                        range = state.rawRange,
                        availableRange = state.availableRange,
                        onSelectRange = vm::selectRange,
                    )
                    // 插件自定义区块放在通用信息与通用操作之间：
                    // 上面的信息是宿主对插件的认定，下面的操作是宿主提供的兜底，
                    // 插件内容夹在中间，既不会冒充宿主信息，也不会盖住危险操作
                    if (state.blocks.isNotEmpty()) {
                        UiBlockList(
                            blocks = state.blocks,
                            busy = state.busy,
                            onAction = vm::onAction,
                        )
                    }
                    OperationSection(
                        info = info,
                        onDanger = { danger = it },
                    )
                }
            }
        }
    }

    danger?.let { action ->
        DangerDialog(
            action = action,
            displayName = state.info?.displayName.orEmpty(),
            onConfirm = {
                when (action) {
                    Danger.Revoke -> vm.revokeTrust()
                    Danger.ClearCredentials -> vm.clearCredentials()
                    Danger.Uninstall -> vm.uninstall()
                }
                danger = null
            },
            onCancel = { danger = null },
        )
    }
}

@Composable
private fun InfoSection(info: PluginDetailInfo) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(info.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                statusText(info),
                style = MaterialTheme.typography.labelSmall,
                color = if (info.healthy == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        LabeledValue("包名", info.packageName)
        LabeledValue("版本", "v${info.versionName}")
        LabeledValue("数据指标", "${info.metricCount} 项")

        if (info.healthMessage.isNotBlank()) {
            LabeledValue("异常原因", info.healthMessage, error = true)
        }

        // 登录态只读：这里不提供登录按钮，多轮登录流程统一在列表页完成
        if (info.needsLogin) {
            LabeledValue(
                "登录状态",
                // 空串 = 没去问（未信任时不去碰插件），别冒充成「未登录」
                info.loginText.ifBlank { "登录状态待确认" },
                error = info.needsLoginAction,
            )
            if (info.needsLoginAction) {
                Text(
                    "需要在插件列表页完成登录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (info.signatureHashes.isNotEmpty()) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "签名指纹",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            info.signatureHashes.forEach {
                // 完整 64 位十六进制太长，展示前 32 位够与来源方比对前缀
                Text(
                    it.take(32),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "插件与 LifeBoard 以同一身份运行，可读取应用内数据并发起网络请求。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OperationSection(info: PluginDetailInfo, onDanger: (Danger) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("操作", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        // 按钮换行而不是横排：桌面端窗口窄时「停止使用/清除凭据/卸载」会被
        // 挤出右侧边界直接消失，FlowRow 让它们在放不下时自动折到下一行
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (info.trusted) {
                OutlinedButton(onClick = { onDanger(Danger.Revoke) }) { Text("停止使用") }
            }
            if (info.needsLogin) {
                OutlinedButton(onClick = { onDanger(Danger.ClearCredentials) }) { Text("清除凭据") }
            }
            OutlinedButton(onClick = { onDanger(Danger.Uninstall) }) { Text("卸载") }
        }

        Text(
            "「停止使用」停止调度但保留数据与凭据；「卸载」删除插件包，凭据与私有数据同样保留。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String, error: Boolean = false) {
    // 上下排而不是「标签左、值右」：
    // 横向排时值要右对齐，只能靠 weight/fillMaxWidth 抢剩余宽度，
    // 而这层外面是 Surface → Column → 纵向滚动，宽度约束一旦不是紧的，
    // 值就会被排到看不见的地方（实测整行只剩标签，卡片右侧一片空白）。
    // 上下排则完全不依赖横向约束，多长的值都能正常显示。
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            // 值比标签重要，用正文色 + 稍大的字号，扫一眼就能读到
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun DangerDialog(
    action: Danger,
    displayName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val body = when (action) {
        Danger.Revoke ->
            "将停止 ${displayName} 的采集，之后不再拉取数据。" +
                "已采集的数据、卡片与保存的凭据都保留，重新信任后可继续使用。"
        Danger.ClearCredentials ->
            "将删除 ${displayName} 保存的登录凭据，无法恢复。" +
                "下次同步前需要回到列表页重新登录，插件本身和数据不受影响。"
        Danger.Uninstall ->
            "将停止使用并删除 ${displayName} 的插件包，之后可以重新安装。" +
                "已保存的凭据和插件私有数据会保留。"
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(action.label) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(action.label, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

private fun statusText(info: PluginDetailInfo): String = when {
    !info.trusted -> "已停止"
    info.healthy == null -> "尚未采集"
    info.healthy -> "正常"
    else -> "异常"
}
