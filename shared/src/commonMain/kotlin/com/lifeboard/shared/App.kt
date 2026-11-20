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
import com.lifeboard.shared.settings.AppearanceState
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
 * @param onRestoreDefaults 页签表被删空时兜底页上的「恢复默认页签」。
 *   默认空实现只在真正没有该能力的端上才可接受——点了没反应等于把用户困在兜底页
 * @param pageContent 画页面内容
 * @param shortcutHandler 把按键翻译成 [Shortcut]。桌面端提供真实映射；
 *   Android 没有物理键盘，用默认的空实现
 * @param showTaskIndicator 是否在页签行右端画任务指示环。Android 端在自己的
 *   `TopAppBar` 里已经画了一颗，这里再画就是两颗，所以默认关闭、由桌面端显式打开
 * @param appearance 外观状态（明暗模式 + 色系）。主题必须挂在这一层：
 *   设置页改的是它，而重组的入口也只能是持有它的这棵界面树。
 *   以前这里写死 `WATER_LILIES + SYSTEM`，设置页点色系后偏好确实写进了库，
 *   但界面取色从不读它——表现就是「点了没反应」。
 *   为 null 时退回默认配色（老调用方与预览仍能渲染）
 */
@Composable
fun App(
    catalog: PageCatalog,
    tabsFlow: Flow<TabLayout>,
    pageContent: @Composable (route: String?, host: PageHost) -> Unit,
    tabActions: TabActions? = null,
    onEditTask: (Long) -> Unit = {},
    onNewTask: () -> Unit = {},
    onRestoreDefaults: () -> Unit = {},
    shortcutHandler: (KeyEvent) -> Shortcut? = { null },
    showTaskIndicator: Boolean = false,
    appearance: AppearanceState? = null,
) {
    // 快捷键处理器由页签壳注册进来（M4-4.7）。
    //
    // 存成 State 而不是普通变量：页签壳每帧都会重新注册一个引用最新状态的
    // 闭包，用普通变量改了不会触发重组，根节点仍会拿到旧闭包——表现就是
    // 「按 Ctrl+Tab 总是跳到同一个页签」。
    var shortcutSink by remember { mutableStateOf<((Shortcut) -> Unit)?>(null) }

    // 外观：读端传入的 AppearanceState。取不到时退回默认值，
    // 而不是在这里再建一个——自建的实例与设置页用的不是同一个对象，
    // 改了不会互相看见，等于把「点了没反应」换个地方复现
    val palette = appearance?.palette ?: ColorPalette.WATER_LILIES
    val mode = appearance?.mode ?: ThemeMode.SYSTEM
    LifeBoardTheme(palette = palette, mode = mode) {
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
                    // 恢复默认页签：桌面端已具备后端能力（DashboardViewModel.restoreDefaults），
                    // 由各端入口透传；只在这些页签真的被删空时才会被点到
                    onRestoreDefaults = onRestoreDefaults,
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
