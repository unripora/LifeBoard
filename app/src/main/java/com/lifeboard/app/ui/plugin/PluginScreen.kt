package com.lifeboard.app.ui.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lifeboard.shared.platform.LocalPlatform
import com.lifeboard.shared.ui.plugin.PluginScreen as SharedPluginScreen

/**
 * 插件页（Android）。
 *
 * 界面本身已经下沉到 `:shared` 的 [SharedPluginScreen]——那一份代码桌面端也在用。
 * 这里只做一件事：从 `LocalPlatform` 取依赖传进去。
 *
 * 不再需要 `rememberLauncherForActivityResult`：共享页面点击「选择插件文件」时
 * 直接调 `deps.filePicker.pickFile()`，Android 侧的实现（`AndroidFilePicker`）
 * 内部持有 Activity 注册的 launcher 来完成弹窗与收结果。
 */
@Composable
fun PluginScreen(
    modifier: Modifier = Modifier,
    onOpenDetail: (pluginId: String) -> Unit = {},
) {
    SharedPluginScreen(
        deps = LocalPlatform.current,
        modifier = modifier,
        onOpenDetail = onOpenDetail,
    )
}
