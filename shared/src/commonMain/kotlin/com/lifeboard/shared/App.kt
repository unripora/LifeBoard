package com.lifeboard.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.lifeboard.shared.navigation.DashboardHost
import com.lifeboard.shared.navigation.PageCatalog
import com.lifeboard.shared.navigation.PageHost
import com.lifeboard.shared.navigation.Shortcut
import com.lifeboard.shared.navigation.TabActions
import com.lifeboard.shared.navigation.TabLayout
import com.lifeboard.shared.ui.theme.ColorPalette
import com.lifeboard.shared.ui.theme.GlassBackground
import com.lifeboard.shared.ui.theme.LifeBoardTheme
import com.lifeboard.shared.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * 应用根界面。
 *
 * 只做三件事：套主题、套玻璃底、接上页签壳。页签行的逻辑全在
 * [DashboardHost] 里——那一份两端共用，各端只在入口交一份
 * [PageCatalog] 说明「这一端能画哪些页」。
 *
 * M0 时这里是两行占位文本（"共享 UI 已渲染"）。现在真接上页签壳了，
 * 留着占位会让人以为桌面端已经能用，实际点任何地方都没反应。
 *
 * @param catalog 这一端能画的页面
 * @param tabsFlow 页签布局流。桌面端读真实数据库，所以这一份不是写死的
 * @param tabActions 页签增删改的落库入口。为 null 时顶部不出现「+」，
 *   页签管理弹窗也打不开（M3-3.2c 之前的桌面端就是这样）
 * @param onEditTask 打开任务编辑器
 * @param onNewTask 新建任务
 * @param pageContent 画页面内容
 * @param shortcutHandler 把按键翻译成 [Shortcut]。桌面端提供真实映射；
 *   Android 没有物理键盘，用默认的空实现
 * @param showTaskIndicator 是否在页签行右端画任务指示环。Android 端在自己的
 *   `TopAppBar` 里已经画了一颗，这里再画就是两颗，所以默认关闭、由桌面端显式打开
 */
@Composable
fun App(
    catalog: PageCatalog,
    tabsFlow: Flow<TabLayout>,
    pageContent: @Composable (route: String?, host: PageHost) -> Unit,
    tabActions: TabActions? = null,
    onEditTask: (Long) -> Unit = {},
    onNewTask: () -> Unit = {},
    shortcutHandler: (KeyEvent) -> Shortcut? = { null },
    showTaskIndicator: Boolean = false,
) {
    // 快捷键处理器由页签壳注册进来（M4-4.7）。
    //
    // 存成 State 而不是普通变量：页签壳每帧都会重新注册一个引用最新状态的
    // 闭包，用普通变量改了不会触发重组，根节点仍会拿到旧闭包——表现就是
    // 「按 Ctrl+Tab 总是跳到同一个页签」。
    var shortcutSink by remember { mutableStateOf<((Shortcut) -> Unit)?>(null) }

    // 外观：Android 侧是 Activity 级的 AppearanceState（用户可改色系/明暗）。
    // 桌面端等设置页搬过来（3.2d）再从 SettingsStore 读，现在用默认值。
    LifeBoardTheme(palette = ColorPalette.WATER_LILIES, mode = ThemeMode.SYSTEM) {
        // 快捷键在根节点用 onPreviewKeyEvent 拦（M4-4.7）：preview 阶段
        // 先于子节点收到事件，输入框里按 Ctrl+Tab 也能切页签。
        // 放在窗口层的 onPreviewKeyEvent 也行，但那要把 sink 提到窗口作用域，
        // 而 sink 的生命周期本就跟着界面，放这里更贴合。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    dispatchShortcut(event, shortcutHandler, shortcutSink)
                },
        ) {
            GlassBackground {
                DashboardHost(
                    catalog = catalog,
                    layoutFlow = tabsFlow,
                    pageContent = pageContent,
                    onEditTask = onEditTask,
                    onNewTask = onNewTask,
                    // 桌面端还没有「恢复默认页签」的后端方法，先给空实现：
                    // 那个入口只在页签表空掉时才出现，正常路径下不会碰到
                    onRestoreDefaults = {},
                    tabActions = tabActions,
                    onShortcut = { shortcutSink = it },
                    // 由调用方决定：Android 在自己 TopAppBar 里已经画了一颗，
                    // 桌面端没有顶栏才需要这一颗
                    showTaskIndicator = showTaskIndicator,
                )
            }
        }
    }
}

/**
 * 把按键翻译成 [Shortcut] 并交给页签壳。
 *
 * 抽成独立函数是为了让「键位映射」与「状态在哪」分开：映射是纯函数，
 * 状态由 [shortcutSink] 承载。测试映射逻辑时不必造一整棵界面树。
 */
internal fun dispatchShortcut(
    event: KeyEvent,
    handler: (KeyEvent) -> Shortcut?,
    sink: ((Shortcut) -> Unit)?,
): Boolean {
    val shortcut = handler(event) ?: return false
    // 有键位但没有接收方（页签壳还没挂上）时不吞掉事件：
    // 返回 false 让系统继续处理，避免启动瞬间按键无端失效
    val target = sink ?: return false
    target(shortcut)
    return true
}
