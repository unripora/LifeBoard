package com.lifeboard.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lifeboard.desktop.tray.DesktopTray
import com.lifeboard.desktop.window.WindowStateStore
import com.lifeboard.shared.App
import com.lifeboard.shared.dashboard.BuiltinTabs
import com.lifeboard.shared.dashboard.DashboardLayout
import com.lifeboard.shared.dashboard.DashboardViewModel
import com.lifeboard.shared.log.LogViewModel
import com.lifeboard.shared.navigation.MissingPageNotice
import com.lifeboard.shared.navigation.PageCatalog
import com.lifeboard.shared.navigation.PageRoute
import com.lifeboard.shared.navigation.PageSlot
import com.lifeboard.shared.navigation.TabActions
import com.lifeboard.shared.navigation.TabLayout
import com.lifeboard.shared.navigation.shortcutOf
import com.lifeboard.shared.panel.PanelScope
import com.lifeboard.shared.panel.PanelScreen
import com.lifeboard.shared.panel.TaskEditScreen
import com.lifeboard.shared.platform.AppCatalog
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.PlatformProvider
import com.lifeboard.shared.remind.RemindTestScreen
import com.lifeboard.shared.repository.DashboardStore
import com.lifeboard.shared.repository.model.DashboardTab
import com.lifeboard.shared.rules.RuleEditScreen
import com.lifeboard.shared.rules.RuleEditUiState
import com.lifeboard.shared.rules.RulesController
import com.lifeboard.shared.rules.RulesScreen
import com.lifeboard.shared.rules.RulesUiState
import com.lifeboard.shared.rules.buildEditState
import com.lifeboard.shared.rules.buildNewRuleState
import com.lifeboard.shared.rules.toRecord
import com.lifeboard.shared.settings.AppearanceState
import com.lifeboard.shared.settings.SettingsScreen
import com.lifeboard.shared.trend.TrendScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 窗口最小尺寸。
 *
 * 用 AWT 的 [java.awt.Dimension]（物理像素）而不是 Compose 的 `Dp`：
 * `window.minimumSize` 收的是 AWT 尺寸，写 `dp` 还得自己换算屏幕缩放比例。
 *
 * 取 960×640 的依据是界面本身的下限：页签行 8 个内置页签在 960 宽时
 * 正好不需要横向滚动就能全看到，面板的两列卡片也还能保住各自的宽度。
 */
private val MIN_WINDOW_SIZE_AWT = java.awt.Dimension(960, 640)

fun main() = application {
    // 装配只在入口做一次：之后所有界面通过 LocalPlatform 取能力，不再各自 new。
    val deps = DesktopPlatformDependencies.create()

    // 启动初始化（M4-4.1）：重排提醒、恢复自动刷新周期。
    //
    // 为什么必须在这里做而不能等界面里 lazy：桌面端的定时器活在进程内，
    // 上次退出时全丢了。不重排不会有任何报错，只会「该响的时候没响」——
    // 这类静默失效最难被发现。
    //
    // 为什么不用 `LaunchedEffect` 或 `remember`：`application { }` 的 lambda
    // 是 `ApplicationScope.() -> Unit`，不是 `@Composable`，两者在这儿都不合法。
    // 这里需要的语义本来就是「进程启动时跑一次」，起一条独立协程就是最直接的表达；
    // 失败只打日志，不挡界面——提醒没排上不该让用户连数据都看不了。
    DesktopStartup.runOnce()

    // 关闭行为：隐藏到托盘，而不是退出（M11）。
    //
    // 原来的 `onCloseRequest` 直接 `shutdown() + exitApplication()`，后果是
    // 「关掉窗口 = 提醒全部失效」。用户关窗通常只是「先收起来」，
    // 不该等于「把今天剩下的提醒都取消掉」。
    //
    // 用一个可变标记在 `Window` 参数与 content lambda 之间传递：
    // `onCloseRequest` 是 `Window` 的入参，在 `FrameWindowScope` 之外求值，
    // 拿不到 `window` 本体，只能通过这样一条共享引用去操作它
    val windowRef = java.util.concurrent.atomic.AtomicReference<java.awt.Window?>(null)
    val trayWanted = remember { java.util.concurrent.atomic.AtomicBoolean(true) }

    // 窗口几何持久化（M11）：上次拖成多大、放在哪，这次就还回去。
    // 写死 1280×800 的话，用户每次启动都要重新调窗口，这是每天都遇到的摩擦
    val windowStateStore = remember { WindowStateStore(deps.settings) }
    val windowState = rememberWindowState()

    // 首帧把存下的几何套上去。不能在 `rememberWindowState(...)` 里直接传参：
    // 那个参数只在窗口创建时生效一次，而 `restore()` 需要读 Preferences，
    // 不该放进 `remember` 的构造里（会在组合期做 IO）
    LaunchedEffect(Unit) {
        val restored = windowStateStore.restore()
        windowState.size = restored.size
        windowState.position = restored.position
        windowState.placement = restored.placement
    }

    /** 托盘实例。在 content lambda 首帧里创建并挂上，退出时用它卸载 */
    val trayHolder = java.util.concurrent.atomic.AtomicReference<DesktopTray?>(null)

    /** 真正退出：停调度 → 存窗口几何 → 卸托盘 → 关窗口。顺序不能反 */
    val exitApp = remember {
        {
            DesktopPlatformDependencies.shutdown()
            // 退出前存几何。放在这里而不是 `onCloseRequest`：
            // 关窗只是隐藏，隐藏时存下的尺寸不代表用户最终想留下的窗口大小，
            // 真正的「最终状态」是他点退出那一刻
            runCatching { windowStateStore.save(windowState) }
            trayHolder.get()?.uninstall()
            exitApplication()
        }
    }

    // 托盘与窗口互相引用：托盘菜单要操作窗口，而窗口的 onCloseRequest 要判断
    // 托盘是否可用。两边都用延迟取值（lambda 在用户操作时才执行），
    // 所以这个先有鸡还是先有蛋的问题不用特别处理

    /**
     * 把主窗口唤到前台。
     *
     * 托盘菜单「显示 LifeBoard」和「点击提醒通知」都走这里（M12）——
     * 两者要做的事完全一样，分开写迟早会出现「一边恢复了最小化、另一边没有」。
     *
     * 必须跑在 EDT 上：AWT 的窗口操作有线程亲和性，从协程线程直接调
     * 会抛 `IllegalStateException` 或被静默忽略。
     */
    val showMainWindow = {
        javax.swing.SwingUtilities.invokeLater {
            windowRef.get()?.let { w ->
                w.isVisible = true
                // 先从最小化恢复，否则「显示」只是让任务栏多一个缩起来的窗口
                if (w is java.awt.Frame) w.extendedState = java.awt.Frame.NORMAL
                w.toFront()
                w.requestFocus()
            }
        }
    }

    val tray = remember {
        DesktopTray(
            tooltip = "LifeBoard",
            onShow = showMainWindow,
            onExit = { exitApp() },
        )
    }

    // 通知点击回调（M12）：把窗口唤回前台。
    // 注册在这里而不是 `DesktopNotifier` 内部：那个类不该知道窗口的存在，
    // 否则它就不再是「换掉也不影响别处」的通知适配器
    SideEffect {
        deps.notifier.setOnNotificationClick { showMainWindow() }
    }

    Window(
        onCloseRequest = {
            // 托盘可用才隐藏；不可用时必须回退到退出，否则窗口一藏就再也叫不回来
            if (tray.available) {
                windowRef.get()?.isVisible = false
            } else {
                exitApp()
            }
        },
        title = "LifeBoard",
        state = windowState,
    ) {
        // 最小尺寸必须设在 AWT 窗口上，不能只改 rememberWindowState 的 size：
        // 后者只是初始值，用户拖边框仍能缩到任意小。窗口过小时 Compose 不会
        // 报错，只会把文字挤成一列一个字——看起来像「坏了」而不是「太小了」。
        // 用 SideEffect 而不是 LaunchedEffect：这是一次性的赋值，不该起协程
        SideEffect {
            windowRef.set(window)
            if (trayWanted.getAndSet(false)) {
                trayHolder.set(tray)
                tray.install()
            }
            window.minimumSize = MIN_WINDOW_SIZE_AWT
            // 窗口图标（任务栏 + 标题栏左上角）：`Window` 的 `icon` 参数只在
            // 早期版本的 API 里有，这里直接设在 AWT 窗口上更稳。
            // 读不到就不设，交给 AWT 用默认图标——不画假图标
            windowIcon()?.let { window.iconImage = it }
        }
        PlatformProvider(deps) {
            DesktopRoot(deps)
        }
    }
}

/**
 * 窗口图标。
 *
 * 与 `DesktopAppIconProvider` 读的是同一张图（`resources/icon.png`，
 * 即 Android 的 `app/src/img/logo.png`）。缓存一份：`SideEffect` 在每次
 * 重组时都会执行，反复解一张 580KB 的 PNG 纯属浪费。
 */
private val cachedWindowIcon: java.awt.Image? by lazy {
    runCatching {
        // 用 `object : Any(){}` 而不是 `Main::class`：`main` 是文件级函数，
        // 没有对应的类可以取，匿名对象的 classLoader 能定位到同一个 jar
        val ref = object : Any() {}
        java.awt.Toolkit.getDefaultToolkit()
            .getImage(ref.javaClass.getResource("/icon.png"))
    }.getOrNull()
}

private fun windowIcon(): java.awt.Image? = cachedWindowIcon

/**
 * 启动初始化的幂等入口。
 *
 * 抽成对象而不是在 `main` 里直接 `launch`，是为了挡住一件事：
 * Compose Desktop 的 `application { }` 在开发模式下可能被执行多次
 * （热重载、某些 IDE 配置下重启窗口）。重复 `rescheduleAll()` 本身是幂等的
 * （先 `cancelAll` 再重排），但重复起协程会让「启动初始化」变成「每次重绘都初始化」，
 * 那就把成本花在了不该花的地方。
 */
private object DesktopStartup {

    @Volatile
    private var started = false

    fun runOnce() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { DesktopPlatformDependencies.bootstrap() }
                    .onFailure {
                        System.err.println("[LifeBoard] 启动初始化失败：${it.message}")
                        it.printStackTrace()
                    }
            }
        }
    }
}

/**
 * 窗口内容。
 *
 * 单独抽出来是因为 `application { }` 的 lambda 不是 `@Composable`——
 * 它是 `ApplicationScope.() -> Unit`，只有 `Window` 的 content lambda 才是。
 * 在 `main` 里直接 `remember` 会报「@Composable 调用只能出现在 @Composable 函数里」。
 */
@Composable
private fun DesktopRoot(deps: PlatformDependencies) {
    // 页签的增删改走与 Android 端同一个 ViewModel：它已经在 commonMain 里，
    // 两端交的只是各自的 Store 与 Backend。
    // 同一个实例也用来提供「恢复默认页签」——页签被删空时那是唯一的自愈出口
    val dashboardViewModel = remember(deps) { DashboardViewModel(deps.dashboardStore, deps.dashboard) }
    val tabActions = rememberTabActions(dashboardViewModel)

    // 外观状态与日志 ViewModel 提到 App 之外：设置页是它们的唯一使用者，
    // 但要让「改配色 → 整个界面换色」与「离开设置页再回来日志还在」都成立，
    // 它们必须活得比设置页长
    val appearance = remember(deps) { AppearanceState(deps.settings) }
    val logViewModel = remember(deps) {
        LogViewModel(
            store = deps.logStore,
            appInfo = deps.appInfo,
            exporter = deps.textExporter,
        )
    }

    // 规则页的状态（M4-4.6）。桌面端没有 ViewModelStoreOwner，
    // 用窗口作用域的 scope 承载，窗口关掉就一起结束
    val rulesScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    var rulesState by remember { mutableStateOf(RulesUiState()) }
    var rulesEditing by remember { mutableStateOf<RuleEditUiState?>(null) }
    val rulesController = remember(deps, rulesScope) {
        RulesController(
            ruleStore = deps.panelScope.ruleStore,
            backend = deps.rules,
            scope = rulesScope,
            onState = { rulesState = it },
        ).also { it.start() }
    }

    // 目录只建一份：pageContent 里也要用同一个实例，
    // 否则每帧都新建一份 map（虽然便宜，但没必要）
    val catalog = remember(appearance, logViewModel, rulesState, rulesEditing) {
        DesktopPages.catalog(appearance, logViewModel, deps) {
            DesktopRulesPage(
                state = rulesState,
                editing = rulesEditing,
                controller = rulesController,
                apps = rememberApps(deps),
                onEditingChange = { rulesEditing = it },
            )
        }
    }

    // 任务编辑器（新建 / 编辑）。桌面端没有独立的导航栈，用一个覆盖层实现：
    // 打开时盖在页签壳之上，关掉就回到原页面，页签选中状态不受影响。
    //
    // `editingTaskId = null` 表示关闭，`0` 表示新建——不能用 `null` 兼任两种含义，
    // 否则「新建」永远打不开。
    var editingTaskId by remember { mutableStateOf<Long?>(null) }

    App(
        catalog = catalog,
        tabsFlow = rememberTabLayout(deps.dashboardStore),
        tabActions = tabActions,
        onEditTask = { editingTaskId = it },
        onNewTask = { editingTaskId = 0L },
        // 兜底页上的「恢复默认页签」：桌面端后端能力已有（DesktopDashboardBackend.ensureDefaults），
        // 之前传空实现，用户删光页签后就只剩删库一条路
        onRestoreDefaults = dashboardViewModel::restoreDefaults,
        pageContent = { route, host ->
            when (val slot = catalog.render(route.orEmpty())) {
                is PageSlot.Resolved -> slot.route.Content(host)
                // 用户自建页签的 route 是 null：它们在桌面端也走面板，
                // 靠 host.tabId 区分是哪一个页签的卡片
                is PageSlot.Missing ->
                    if (route == null) {
                        PanelScreen(
                            tabId = host.tabId,
                            onEditTask = host::editTask,
                            onNewTask = host::createTask,
                        )
                    } else {
                        MissingPageNotice(slot.route)
                    }
            }
        },
        shortcutHandler = ::shortcutOf,
        // 桌面端没有顶栏，任务指示环只能画在页签行右端。
        // 不打开的话插件同步那十几秒界面毫无动静，用户分不清在干活还是卡死
        showTaskIndicator = true,
        // 传给根节点的主题。少了这一行，设置页里点「色系」会写库成功、
        // 但整棵树取色仍用默认值，看上去就是点了没反应
        appearance = appearance,
    )

    // 编辑层。放在 [App] 之后，Compose 的绘制顺序就是「后声明的画在上面」
    editingTaskId?.let { taskId ->
        TaskEditOverlay(
            taskId = taskId,
            panelScope = deps.panelScope,
            onClose = { editingTaskId = null },
        )
    }
}

/**
 * 任务编辑覆盖层。
 *
 * 一个铺满窗口的 `Surface`，把底下的页签壳完全盖住：编辑任务时用户不该
 * 边改边点别的页签，那样很容易在「改了一半」的状态下切走并迷路。
 *
 * 用 `Surface` 而不是 `Box`：它自带不透明背景与主题色，否则遮罩下的页签行
 * 会透出来和表单文字叠在一起。
 */
@Composable
private fun TaskEditOverlay(
    taskId: Long,
    panelScope: PanelScope,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        TaskEditScreen(
            taskId = taskId,
            panelScope = panelScope,
            onDone = onClose,
        )
    }
}

/**
 * 桌面端能画的页面。
 *
 * 已是真页面的：**面板**（3.2b）、**设置**（3.2d，含关于与日志两个子页）。
 * 设置页跑的是与 Android 同一份 `SettingsScreen`——外观、刷新周期、读屏开关
 * 的取舍逻辑都在共享代码里，桌面端只提供各自的平台实现。
 *
 * 其余页面等 3.2e 之后搬完才有；在那之前挂 [NotMigratedYet] 占位。
 *
 * **不注册 `notice` / `debug`**：那两页依赖 Android 的通知监听与 IPC 自检，
 * 桌面端根本没有对应能力。页签仍留在库里（从手机同步过来时不该丢），
 * 点进去会看到 `MissingPageNotice` 的说明。
 */
private object DesktopPages {

    /**
     * 页目录。
     *
     * 每调用一次新建一份：`PageCatalog.of` 只是把 route 与渲染 lambda 放进 map，
     * 开销极小，而做成对象级单例就得把 `appearance` / `logViewModel` 存成全局，
     * 那反而要多管一份生命周期。
     */
    fun catalog(
        appearance: AppearanceState,
        logViewModel: LogViewModel,
        deps: PlatformDependencies,
        rulesContent: @Composable () -> Unit = { NotMigratedYet("规则") },
    ): PageCatalog = PageCatalog.of(
        BuiltinTabs.ROUTE_PANEL to PageRoute { host ->
            PanelScreen(
                tabId = host.tabId,
                onEditTask = host::editTask,
                onNewTask = host::createTask,
            )
        },
        BuiltinTabs.ROUTE_RULES to PageRoute { rulesContent() },
        // 趋势页跑共享实现：统计口径（得分、达成率、连击）与 Android 完全同一份代码。
        // 两边各写一份的话，算出来的数字一旦不一致，用户会先怀疑数据而不是界面。
        BuiltinTabs.ROUTE_TREND to PageRoute {
            // 屏幕时长块也走共享实现：桌面端 M11 补了窗口焦点记账，
            // 所以这里能传真实后端而不是 null
            TrendScreen(
                panelScope = deps.panelScope,
                screenTime = deps.screenTime,
                // 桌面端没有「使用情况访问」这种系统授权页，
                // 传 null 后「去开启」按钮不渲染——比给一个点了没反应的按钮诚实
                onOpenScreenTimePermission = null,
            )
        },
        // 数据指标页（M13）：原生 / 自定义 / 插件三组，展开即显示当天值。
        // 与「添加指标卡」共用同一份候选产出，所以两处的分组与条目永远一致
        BuiltinTabs.ROUTE_METRICS to PageRoute {
            com.lifeboard.shared.metrics.MetricsScreen(scope = deps.panelScope)
        },
        // 提醒自检页同样共享，桌面后端是 `DesktopRemindBackend`。
        // 不传 onOpenPermission：桌面没有「通知权限设置页」这种东西，
        // 传 null 后界面会自己隐藏「去开启」按钮，而不是给个点了没反应的按钮
        BuiltinTabs.ROUTE_REMIND to PageRoute {
            RemindTestScreen(backend = deps.remind)
        },
        // 插件页与 Android 跑同一份 `PluginScreen`：安装、信任确认、登录、同步
        // 的取舍都在共享代码里，桌面端只提供 FileDialog 版的文件选择器。
        // 详情页（M5 下沉到 :shared）由 [DesktopPluginPage] 叠在列表之上。
        BuiltinTabs.ROUTE_PLUGIN to PageRoute {
            com.lifeboard.desktop.plugin.DesktopPluginPage(deps = deps)
        },
        BuiltinTabs.ROUTE_SETTINGS to PageRoute {
            SettingsScreen(appearance = appearance, logViewModel = logViewModel)
        },
    )
}

/**
 * 页签布局流。
 *
 * 桌面端读的是真实数据库（`~/.lifeboard/lifeboard.db`），不是写死的清单——
 * 否则桌面端改的页签顺序和手机端对不上，同步时互相覆盖。
 *
 * ## 必须订阅 `observeTabs()`，不能只发一次快照
 *
 * 早先这里用的是 `flow { emit(一次) }`：初始化写完布局就结束了，
 * 之后「+」新增、改名、删除、显示开关全都写进了数据库，但界面再也收不到
 * 新值——页签行一动不动，用户看到的就是「点了完全没反应」。
 * 页签表本来就有 `observeTabs()` 的响应式订阅（Android 端靠它生效），
 * 这里接上去即可，不需要任何轮询或手动刷新。
 *
 * 注意 `ensureInitialized` 要在订阅**之前**跑完：它的职责是「表为空时写默认值」，
 * 先订阅会在写入前先收到一个空列表，界面那一帧会闪一下「页签列表为空」的兜底页。
 * 用 `flow { }` 把初始化放在 `emitAll` 之前，但**不要**在初始化前 emit 空列表——
 * 未就绪的那一帧由 `DashboardHost` 用内存默认布局顶住（`ready = false`），
 * 不经过这个流也能正确渲染。
 */
@Composable
private fun rememberTabLayout(store: DashboardStore): Flow<TabLayout> = remember(store) {
    flow {
        // 首帧：让界面知道「还没读完」，用内存默认布局顶住，不会闪空白
        emit(TabLayout())
        DashboardLayout.ensureInitialized(store)
        // 接上实时订阅：之后每一次写库都会推到这里，页签行自己跟着变
        emitAll(store.observeTabs().map { TabLayout(tabs = it, ready = true) })
    }
}

/**
 * 页签增删改的落库入口。
 *
 * 直接复用 `DashboardViewModel`——那个类在 `commonMain`，Android 端用的是
 * 同一份，所以两端「新建页签用什么 id、改名怎么校验、删除要不要连卡片一起删」
 * 这些规则不会各自演化。
 *
 * 这里没用 `viewModel { }`：桌面端没有 Activity 级的 `ViewModelStoreOwner`，
 * 硬造一个反而要为退出清理负责。改用 `remember` + 一个跟随窗口生命周期的
 * 协程作用域承载，语义更直白——窗口关了就一起结束。
 *
 * [vm] 由调用方持有：同一实例还要供「恢复默认页签」使用，
 * 在这里再 new 一个会让两处操作各自持有一份状态。
 */
@Composable
private fun rememberTabActions(vm: DashboardViewModel): TabActions {
    return remember(vm) {
        object : TabActions {
            override fun create(name: String, onCreated: (String) -> Unit) =
                vm.createTab(name, onCreated)

            override fun rename(tabId: String, name: String) = vm.renameTab(tabId, name)

            override fun delete(tabId: String) = vm.deleteTab(tabId)

            // 用 moveTabTo 而不是 moveTab：这里的 to 是**拖动落点**，可能跨好几行；
            // moveTab 收的是相对位移，两者下标语义不同，混用会让拖动落错位置
            override fun move(tabId: String, to: Int) = vm.moveTabTo(tabId, to)

            override fun setEnabled(tabId: String, enabled: Boolean) =
                vm.setTabEnabled(tabId, enabled)
        }
    }
}

/**
 * 应用清单（M4-4.6）。
 *
 * 会话内只拉一次：扫开始菜单并解几百个快捷方式要约 1~2 秒，
 * 每次打开选择器都重扫会让界面明显发顿。清单本身也不会在运行期间变——
 * 用户装了新程序想让它出现，重启应用即可。
 */
@Composable
private fun rememberApps(deps: PlatformDependencies): List<AppCatalog.AppEntry> {
    var apps by remember(deps) { mutableStateOf<List<AppCatalog.AppEntry>>(emptyList()) }
    LaunchedEffect(deps) {
        apps = runCatching { deps.appCatalog.list() }.getOrDefault(emptyList())
    }
    return apps
}

/**
 * 桌面端规则页：列表与编辑两态。
 *
 * 用同一个路由内部切换而不是两个路由，是因为「编辑完回到列表」要保留列表滚动位置；
 * 拆成两个页签会在返回时重建列表、滚回顶部。
 */
@Composable
private fun DesktopRulesPage(
    state: RulesUiState,
    editing: RuleEditUiState?,
    controller: RulesController,
    apps: List<AppCatalog.AppEntry>,
    onEditingChange: (RuleEditUiState?) -> Unit,
) {
    val draft = editing
    if (draft == null) {
        RulesScreen(
            state = state,
            onEdit = { rule ->
                controller.loadEditState(rule) { onEditingChange(it) }
            },
            onToggle = controller::toggleRule,
            onDelete = controller::deleteRule,
            onCreate = { packageName -> onEditingChange(buildNewRuleState(packageName)) },
            onClearToday = controller::clearToday,
            onRefreshPermissions = controller::refresh,
        )
    } else {
        RuleEditScreen(
            initial = draft,
            sources = state.sources,
            apps = apps,
            onSave = { updated ->
                controller.saveRule(updated.toRecord())
                onEditingChange(null)
            },
            onCancel = { onEditingChange(null) },
            onDelete = { id ->
                controller.deleteRule(id)
                onEditingChange(null)
            },
            onCreateSource = controller::createCustomSource,
        )
    }
}

/**
 * 「这一页还没搬过来」的占位。
 *
 * 与 `MissingPageNotice` 区分开：那一个是「**这一端**没有这个能力」（通知源、
 * 自检），这一个只是「代码还没搬完」。措辞不同是因为用户的下一步不同——
 * 前者只能换设备用，后者等更新就好。
 */
@Composable
private fun NotMigratedYet(page: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "$page：这一页还没搬进共享界面（M3-3.2b~3.2d）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp),
        )
    }
}
