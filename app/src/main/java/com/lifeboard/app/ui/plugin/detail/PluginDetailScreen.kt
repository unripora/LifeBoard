package com.lifeboard.app.ui.plugin.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.theme.GlassCard

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
 * 插件详情页（M4.4）。
 *
 * 全屏覆盖层，自带背景与顶栏，返回由自己的 [BackHandler] 接管——
 * 与 [com.lifeboard.app.ui.panel.TaskEditScreen] 同一模式，不引入 Navigation 库。
 *
 * 本页只展示**宿主掌握的**通用信息与通用操作。插件自定义区块（状态图、按钮等）
 * 属于声明式 UI 契约，后置到 M5.7 由小米健康插件驱动设计后再补进这里。
 *
 * **不提供登录入口**：登录是多轮状态机，列表页已有一套完整实现。
 * 这里只读展示登录态，并引导用户回列表页操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(
    pluginId: String,
    onClose: () -> Unit,
    vm: PluginDetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var danger by remember { mutableStateOf<Danger?>(null) }

    LaunchedEffect(pluginId) { vm.load(pluginId) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 卸载/撤销后插件已不属于列表，直接退回列表页，不要留在一个空壳页面上
    LaunchedEffect(state.closed) { if (state.closed) onClose() }

    BackHandler(onBack = onClose)

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                info.loginText.ifBlank { "未登录" },
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
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
