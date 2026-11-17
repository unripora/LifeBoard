package com.lifeboard.desktop.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.ui.plugin.PluginScreen
import com.lifeboard.shared.ui.plugin.detail.PluginDetailScreen
import com.lifeboard.shared.ui.plugin.detail.PluginDetailViewModel

/**
 * 桌面端的插件页：列表 + 详情覆盖层。
 *
 * ## 为什么要包一层
 *
 * [PluginScreen] 在共享层，它只负责「点卡片时告诉我 pluginId」。详情页要一份
 * [PluginDetailViewModel]，而它自己开 scope、必须在离开时 `close()`——
 * Android 侧由 `viewModel()` 管生命周期，桌面端没有那个东西，只能由这一层持有。
 *
 * 详情页盖在列表上方而不是替换掉它：离开详情页时列表的滚动位置与已加载内容
 * 都还在，不必重扫一遍插件目录。
 */
@Composable
fun DesktopPluginPage(deps: PlatformDependencies) {

    // 当前正在看详情的插件；null 表示停在列表页
    var openedPluginId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        PluginScreen(
            deps = deps,
            onOpenDetail = { openedPluginId = it },
        )

        val pluginId = openedPluginId
        if (pluginId != null) {
            // remember(pluginId)：换插件时重建 VM，避免上一个插件的信息残留。
            // DisposableEffect 保证离开时一定 close，否则那个自持的 scope
            // 会一直挂着，插件目录热重启时还会收到通知
            val vm = remember(pluginId) { PluginDetailViewModel(deps) }
            DisposableEffect(vm) {
                vm.load(pluginId)
                onDispose { vm.close() }
            }
            Box(
                // 详情页盖在列表之上，必须自己给背景：
                // 它的 Scaffold 是 containerColor = Transparent（Android 侧由
                // 外面的 GlassBackground 出背景），桌面端不铺这一层的话，
                // 列表的标题、卡片会和详情页的文字叠在一起
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                PluginDetailScreen(
                    vm = vm,
                    onClose = { openedPluginId = null },
                )
            }
        }
    }
}
