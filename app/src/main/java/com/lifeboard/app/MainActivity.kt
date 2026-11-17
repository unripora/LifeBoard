package com.lifeboard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lifeboard.shared.dashboard.DashboardDefaults
import com.lifeboard.shared.dashboard.DashboardViewModel
import com.lifeboard.app.platform.AndroidAppIconProvider
import com.lifeboard.app.platform.AndroidTextFileExporter
import com.lifeboard.shared.dashboard.NewTabDialog
import com.lifeboard.shared.dashboard.TabManagerDialog
import com.lifeboard.shared.dashboard.TabRowUi
import com.lifeboard.app.ui.debug.IpcDebugScreen
import com.lifeboard.app.ui.rules.RulesScreen
import com.lifeboard.shared.log.LogViewModel
import com.lifeboard.app.ui.notice.NoticeDebugScreen
import com.lifeboard.shared.panel.PanelScreen
import com.lifeboard.app.ui.panel.TaskEditScreen
import com.lifeboard.app.ui.panel.TrendScreen
import com.lifeboard.app.ui.plugin.PluginScreen
import com.lifeboard.shared.ui.plugin.detail.PluginDetailScreen
import com.lifeboard.shared.ui.plugin.detail.PluginDetailViewModel
import com.lifeboard.app.ui.remind.RemindTestScreen
import com.lifeboard.shared.settings.AppearanceState
import com.lifeboard.shared.settings.SettingsScreen
import com.lifeboard.shared.task.TaskIndicatorButton
import com.lifeboard.shared.task.TaskListDialog
import com.lifeboard.shared.ui.theme.GlassBackground
import com.lifeboard.shared.ui.theme.LifeBoardTheme
import com.lifeboard.shared.platform.LocalPlatform
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.PlatformProvider
import com.lifeboard.shared.dashboard.BuiltinTab
import com.lifeboard.shared.dashboard.BuiltinTabs
import com.lifeboard.shared.dashboard.TabKind
import com.lifeboard.server.platform.AndroidDashboardBackend
import com.lifeboard.server.platform.AndroidNotifier
import com.lifeboard.server.platform.AndroidPlatformDependencies
import com.lifeboard.server.remind.RemindScheduler
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.navigation.MissingPageNotice
import com.lifeboard.shared.navigation.PageCatalog
import com.lifeboard.shared.navigation.PageHost
import com.lifeboard.shared.navigation.PageRoute
import com.lifeboard.shared.navigation.PageSlot
import com.lifeboard.shared.repository.model.DashboardTab

/**
 * 应用主界面。
 *
 * 七个页签：面板（待办与得分）、规则（采集规则）、趋势（周/月表现）、
 * 提醒（自检）、通知源（采集调试）、自检（IPC 通路）、设置（外观）。
 * 任务编辑是一个覆盖全屏的子页面，不占页签。
 */
class MainActivity : ComponentActivity() {

    /**
     * 本次 Activity 实例装配好的依赖（M12）。
     *
     * 只用 `onNewIntent` 派发通知点击。Activity 重建时会重新赋值，
     * 旧实例随之被回收，不会泄漏。
     */
    private var assembledDeps: PlatformDependencies? = null

    /** 通知权限申请结果回调，结果由系统写回权限表，这里无需额外处理 */
    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * 文件选择器的回调挂点。
     *
     * `registerForActivityResult` 必须在 Activity 进入 RESUMED 之前调用，
     * 所以这里是字段而不是在 `setContent` 里注册。`AndroidFilePicker`
     * 需要「弹窗 + 收结果」这一对动作，就把本字段的 launcher 传给它。
     */
    private var pendingPickCallback: ((android.net.Uri?) -> Unit)? = null

    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingPickCallback
            pendingPickCallback = null
            callback?.invoke(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 13+ 通知必须运行时申请，只在清单里声明是不够的：
        // 不申请的话所有提醒都发不出去，而界面上看起来一切正常
        ensureNotificationPermission()
        // 插件扫描、小米历史数据清理与聚合调度都收敛在 LifeBoardApp：
        // WorkManager 也会在没有 Activity 的情况下拉起进程，放在这里会让那种进程拿不到插件
        // 闹钟在进程被杀或跨天后会失效，每次打开应用都重排一次
        runCatching { RemindScheduler.rescheduleAll(this) }
        // 全应用唯一的依赖装配点：之后所有页面通过 LocalPlatform 取能力，
        // 不再各自 new 平台实现
        val deps = buildPlatformDependencies()
        // 存到字段上供 `onNewIntent` 使用：那时拿不到局部变量，
        // 而通过 CompositionLocal 取又需要已经建立好的 Composition
        assembledDeps = deps
        // 通知点击的回调（M12）。Android 侧的「唤回界面」由系统完成
        // （PendingIntent 会把 Activity 拉到前台），这里只需要记下点击了哪条。
        // 与桌面端注册的是同一个契约方法，两端行为语义一致
        (deps.notifier as? AndroidNotifier)?.setOnNotificationClick { payload ->
            android.util.Log.i("LifeBoard", "用户点击了提醒通知，payload=$payload")
        }
        // 冷启动时通知带来的 payload：`onCreate` 之后没有 `onNewIntent`，
        // 不在这里读就永远收不到
        handleNotificationIntent(intent)
        setContent {
            // 外观状态提到 Activity 级：设置页改动后主界面立即重组换色，无需重启。
            // 存储走注入的 SettingsStore（键名仍是 theme_mode / color_palette），
            // 老用户的既有偏好照常读得到
            val appearance = remember { AppearanceState(deps.settings) }
            LifeBoardTheme(palette = appearance.palette, mode = appearance.mode) {
                PlatformProvider(deps) {
                    MainScaffold(appearance)
                }
            }
        }
    }

    /**
     * 构造本 Activity 的依赖。
     *
     * 只在 Activity 创建时算一次；`Activity` 重建（旋屏、改语言）会重新装配，
     * 这是对的——旧实例可能已经被回收。依赖内部持有的都是 applicationContext，
     * 所以重建不会泄漏 Activity。
     */
    private fun buildPlatformDependencies() = AndroidPlatformDependencies.forActivity(
        context = this,
        launcher = { mimeTypes, onResult ->
            pendingPickCallback = onResult
            pickDocument.launch(mimeTypes)
        },
        // 「关于」页的应用图标。实现在 :app 里：需要 android.graphics 绘图，
        // 而 :server 没有也不该有画图逻辑
        appIcon = AndroidAppIconProvider(this),
        // 日志导出：写 cache/logs 后走 FileProvider 弹分享面板
        textExporter = AndroidTextFileExporter(this),
    )

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Activity 已在前台时用户点了通知（M12）。
     *
     * `Intent.FLAG_ACTIVITY_SINGLE_TOP` 会让这种情况走这里而不是 `onCreate`，
     * 所以两处都要读——只写 `onCreate` 的话「App 开着时点通知」就没有任何反馈。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须更新 Activity 持有的 Intent，否则后续 `getIntent()` 读到的还是旧的
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * 把通知点击带来的 payload 转交给 [AndroidNotifier] 上注册的回调。
     *
     * 这里只做派发，不直接打开某个页面：按当前产品决策（方案 A），
     * 点击通知的落地行为是「把 App 带到前台」，不做深链到具体任务。
     */
    private fun handleNotificationIntent(intent: Intent?) {
        val payload = intent?.getStringExtra(AndroidNotifier.EXTRA_PAYLOAD) ?: return
        // 依赖可能还没装配（冷启动时 `handleNotificationIntent` 在 `setContent` 之前跑）。
        // 那种情况下系统已经把界面拉到前台了，方案 A 的语义已经达成，直接跳过即可
        (assembledDeps?.notifier as? AndroidNotifier)?.dispatchClick(payload)
        // 取完就清掉，避免 Activity 重建时把同一次点击重复派发
        intent.removeExtra(AndroidNotifier.EXTRA_PAYLOAD)
    }
}

/**
 * 一个页签。
 *
 * `route` 是分发的唯一依据：内置页签走代码里的固定页面，
 * 用户页签（M6b）统一走卡片容器。用字符串 route 而不是枚举，
 * 是因为用户页签没有对应枚举值；`TabKind` 则在类型上把两者分开，
 * 避免靠「名字碰巧不重复」来区分。
 *
 * @param tabId 持久化用的稳定标识，重启后据此恢复选中的页签
 */
private data class TabItem(
    val tabId: String,
    val label: String,
    val route: String,
    val kind: TabKind,
    /** 内置页签的原始定义；用户页签为 null。管理弹窗据此决定能不能删 */
    val builtin: BuiltinTab? = null,
    /** 是否在顶部页签行里显示。关掉的页签仍在列表里，只是不画 */
    val enabled: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(appearance: AppearanceState) {
    val context = LocalContext.current
    // 页签选中态走注入的 SettingsStore 而不是直接摸 SharedPreferences：
    // 桌面端用的是 java.util.prefs，同一行代码两端都能跑
    val settings = LocalPlatform.current.settings
    // 选中态存 tabId 而不是下标：页签顺序可被用户调整，
    // 用下标会在「在最后一个页签时新增一个页签」之类的场景跳到别的页面
    var selectedTabId by remember {
        mutableStateOf(settings.getString(KEY_SELECTED_TAB, BuiltinTabs.TAB_ID_PANEL))
    }
    // 0 表示不在编辑页；非 0 为编辑中的任务 id；NEW_TASK_ID 表示新建
    var editingTaskId by remember { mutableStateOf<Long?>(null) }
    // 每次打开编辑页自增，作为 Compose key 强制重建编辑器，避免复用上次状态
    var editorSession by remember { mutableIntStateOf(0) }

    // 插件详情页：非 null 表示正在看该插件。同样用自增 session 做 key，
    // 因为两次点同一个插件时 pluginId 不变，LaunchedEffect 不会重跑
    var detailPluginId by remember { mutableStateOf<String?>(null) }
    var detailSession by remember { mutableIntStateOf(0) }

    /** 面板上「重新判定数据源健康度」的触发键 */
    var healthRefreshKey by remember { mutableIntStateOf(0) }

    // 日志页（M3-3.2d 起在 shared）：提到这里是为了在「设置 → 运行日志」里跨重组存活，
    // 从子页返回再进去不会丢搜索词与已加载的分页。
    // deps 要在 Composable 作用域里先取出来：initializer 的 lambda 不是 @Composable
    val deps = LocalPlatform.current
    val logVm: LogViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LogViewModel(
                    store = deps.logStore,
                    appInfo = deps.appInfo,
                    exporter = deps.textExporter,
                )
            }
        },
    )

    val dashVm: DashboardViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) { "缺少 Application，无法构造仪表盘" }
                DashboardViewModel(DatabaseProviders.dashboardStore(app), AndroidDashboardBackend(DatabaseProviders.dashboardStore(app)))
            }
        },
    )
    val dashState by dashVm.state.collectAsStateWithLifecycle()
    // 页签管理是压在顶栏上的一层弹窗
    var showTabManager by remember { mutableStateOf(false) }
    // 「+」打开的是一层更轻的弹窗：只输入名字，建完就用。
    // 增删改排序仍然在 TabManagerDialog 里，从「+」弹窗可以跳过去
    var showTabCreator by remember { mutableStateOf(false) }
    // 顶部任务指示器点开的列表
    var showTaskList by remember { mutableStateOf(false) }

    // 未就绪时用内存默认布局顶一帧：这里有顺序敏感的场景——
    // 冷启动第一帧如果用空列表，ScrollableTabRow 会闪一下空白再出现页签
    val allTabs = if (dashState.ready) {
        dashState.tabs.map { it.toTabItem() }
    } else {
        DashboardDefaults.tabs.map { it.toTabItem() }
    }
    // 关掉的页签不进顶部页签行。过滤放在这里而不是渲染时：下面的
    // selectedIndex 回退逻辑要按「实际看得见的页签」算，否则选中一个
    // 关掉的页签会显示成第一个可见页签却没写回偏好
    val tabs = allTabs.filter { it.enabled }

    fun selectTab(tabId: String) {
        selectedTabId = tabId
        settings.putString(KEY_SELECTED_TAB, tabId)
    }

    // 三种状态必须分开，不能都当成「布局坏了」：
    //
    // 1. 还没就绪 —— 上面的 tabs 已经是默认布局，正常画，什么都不提示
    // 2. 就绪且有页签 —— 正常画
    // 3. 就绪但页签表为空 —— 才是真异常，给恢复入口
    //
    // 之前用 tabs.isEmpty() 判断，未就绪时也会落进第 3 种，
    // 冷启动会闪一下「恢复默认页签」的异常页，像是数据坏了。
    if (dashState.empty) {
        EmptyDashboard(onRestore = { dashVm.restoreDefaults() })
        return
    }

    // 存的 tabId 可能已经不存在（页签被删、或来自降级后的旧版本），
    // 一律回退到面板，否则会停在一个空白页
    val selectedIndex = tabs.indexOfFirst { it.tabId == selectedTabId }.takeIf { it >= 0 } ?: 0
    val current = tabs[selectedIndex]

    // 内置页签的页面注册表。用户页签（route 为空）不注册，走卡片容器。
    //
    // 这里就是 3.4 的接线点：共享层的 DashboardHost 只认 route 字符串，
    // 具体是哪个 Composable 由这一份映射决定。桌面端交一份更短的清单——
    // 它没有通知监听与无障碍，notice / debug 两页注册不出来。
    //
    // 每个页面都通过闭包捕获它需要的东西（外观、浮层开关、tabId），
    // 所以 PageRoute 的签名里不需要出现任何平台类型。
    val catalog = remember(appearance, current.tabId, healthRefreshKey, logVm) {
        buildAndroidPages(
            appearance = appearance,
            logViewModel = logVm,
            tabId = current.tabId,
            refreshKey = healthRefreshKey,
            onEditTask = {
                editorSession++
                editingTaskId = it
            },
            onNewTask = {
                editorSession++
                editingTaskId = NEW_TASK_ID
            },
            onOpenPluginDetail = { pluginId ->
                detailSession++
                detailPluginId = pluginId
            },
        )
    }

    // 页面拿到的壳句柄。key 带 tabId：换了页签就该换句柄，
    // 否则页面里 remember 住它的地方会一直往旧页签跳
    val host = remember(catalog, current.tabId) {
        object : PageHost {
            override val tabId: String = current.tabId

            override fun navigateTo(route: String) {
                val target = tabs.firstOrNull { it.route == route } ?: return
                selectTab(target.tabId)
            }

            override fun editTask(taskId: Long) {
                editorSession++
                editingTaskId = taskId
            }

            override fun createTask() {
                editorSession++
                editingTaskId = NEW_TASK_ID
            }
        }
    }

    // 选中页被删掉后，持久化的 tabId 已经失效，把回退后的选择写回去，
    // 否则每次冷启动都要重新回退一次
    LaunchedEffect(selectedIndex, current.tabId) {
        if (current.tabId != selectedTabId) selectTab(current.tabId)
    }

    // 玻璃主题下的页面底色：官方 background + 一层淡主色渐变，内容面板以半透明叠在其上
    GlassBackground {
        // 编辑页是压在主界面之上的第二层：主界面始终留在页面栈里，
        // 只是被盖住。之前的写法是「条件渲染 + return」，编辑页没有上一层，
        // 系统返回键无人拦截就直接 finish() 退到桌面了。
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("LifeBoard") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    actions = {
                        // 任务指示器：空闲是静止圆环，有任务时旋转，点开看进度与历史
                        TaskIndicatorButton(onClick = { showTaskList = true })
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // 页签行 = 可横向滚动的页签 + 固定在右端的「+」。
                // 「+」不能放进 ScrollableTabRow 里面：那样它会跟着页签一起滚走，
                // 页签一多就找不到新增入口了
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 8 个页签在窄屏上放不下，必须可横向滚动：
                    // 用 TabRow 会等分压缩，标签被截成两三个字就看不出是什么了
                    ScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        containerColor = Color.Transparent,
                        edgePadding = 8.dp,
                        modifier = Modifier.weight(1f),
                    ) {
                        tabs.forEach { tab ->
                            Tab(
                                selected = current.tabId == tab.tabId,
                                onClick = { selectTab(tab.tabId) },
                                text = {
                                    Text(
                                        tab.label,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                            )
                        }
                    }
                    IconButton(onClick = { showTabCreator = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "新建页签",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 按 route 分发而不是穷尽枚举：用户页签没有枚举值，
                // 而且删掉内置页签（M6b）后也必须有个去处，
                // 所以 pageContent 里 route 为 null 时走卡片容器，不能留空屏。
                //
                // M3-3.4：具体页面由 [androidPages] 注册，这里只剩「查表 + 画」。
                // 注册表的作用是让共享层的 DashboardHost 不必 import 任何具体页面——
                // 否则页签壳就永远搬不出 :app。
                when (val slot = catalog.render(current.route.orEmpty())) {
                    is PageSlot.Resolved -> slot.route.Content(host)
                    is PageSlot.Missing ->
                        // route 为空说明是用户页签：它不是「缺失」，而是走卡片容器
                        if (current.route == null) {
                            PanelScreen(
                                tabId = current.tabId,
                                onEditTask = host::editTask,
                                onNewTask = host::createTask,
                                refreshKey = healthRefreshKey,
                            )
                        } else {
                            MissingPageNotice(slot.route)
                        }
                }
            }
        }

        val editing = editingTaskId
        if (editing != null) {
            // 覆盖在上一层之上的全屏页，自带背景与顶栏，返回/左滑由它自己的 BackHandler 接管。
            // key 用「编辑器打开次数」而不是 taskId：ViewModel 是 Activity 级的，
            // 连续两次点同一个任务时 taskId 不变，LaunchedEffect 不会重跑，
            // 页面会沿用上次的状态（甚至被残留的 done 立刻关掉）。
            key(editorSession) {
                GlassBackground {
                    TaskEditScreen(
                        taskId = editing,
                        onDone = { editingTaskId = null },
                    )
                }
            }
        }

        val detail = detailPluginId
        if (detail != null) {
            // 与编辑页同一种覆盖层：全屏、自带背景与顶栏、返回由页面自己的 BackHandler 接管。
            // key 用「打开次数」而不是 pluginId：连续两次点同一个插件时 pluginId 不变，
            // LaunchedEffect 不会重跑，页面会沿用上次读到的状态
            key(detailSession) {
                GlassBackground {
                    // 详情页已下沉到 :shared，与桌面端共用同一份实现。
                    // VM 由这里持有并在离开时 close()：它自持 scope，不像原来继承
                    // ViewModel 那样能靠 viewModelScope 自动取消
                    val detailVm = remember(detail) { PluginDetailViewModel(deps) }
                    DisposableEffect(detailVm) {
                        detailVm.load(detail)
                        onDispose { detailVm.close() }
                    }
                    PluginDetailScreen(
                        vm = detailVm,
                        onClose = { detailPluginId = null },
                        // 桌面端没有返回键，这份处理只有 Android 用得上
                        backHandler = { onBack -> BackHandler(onBack = onBack) },
                    )
                }
            }
        }

        if (showTabManager) {
            TabManagerDialog(
                // 管理弹窗列出**全部**页签（含已关掉的），否则关掉之后再也没法打开
                tabs = allTabs.map {
                    TabRowUi(
                        tabId = it.tabId,
                        label = it.label,
                        builtin = it.builtin,
                        enabled = it.enabled,
                    )
                },
                onDismiss = { showTabManager = false },
                onCreate = { name ->
                    dashVm.createTab(name) { newId -> selectTab(newId) }
                },
                onRename = { tabId, name -> dashVm.renameTab(tabId, name) },
                onDelete = { tab ->
                    dashVm.deleteTab(tab.tabId)
                    // 删的是当前页时立刻回到面板，不等列表刷新再回退，
                    // 否则中间那一帧会用已经删掉的 tabId 去找页面
                    if (tab.tabId == selectedTabId) selectTab(BuiltinTabs.TAB_ID_PANEL)
                },
                onMove = { tabId, to -> dashVm.moveTab(tabId, to) },
                onToggleEnabled = { tab, enabled ->
                    dashVm.setTabEnabled(tab.tabId, enabled)
                    // 关掉的正好是当前页：立刻切回面板。留在原页会变成
                    // 「顶部没有选中项、下面却在画它的内容」的割裂状态
                    if (!enabled && tab.tabId == selectedTabId) {
                        selectTab(BuiltinTabs.TAB_ID_PANEL)
                    }
                },
            )
        }

        if (showTabCreator) {
            NewTabDialog(
                onDismiss = { showTabCreator = false },
                onManage = {
                    showTabCreator = false
                    showTabManager = true
                },
                onCreate = { name ->
                    // 建完立刻切过去：新页签是空的，停在原页签会让人以为没建成
                    dashVm.createTab(name) { newId -> selectTab(newId) }
                },
            )
        }

        if (showTaskList) {
            TaskListDialog(onDismiss = { showTaskList = false })
        }
    }
}

/**
 * 把库里的页签行还原成界面用的 [TabItem]。
 *
 * 内置页签的 route 由 [BuiltinTabs] 反查得出——库里只存 tabId 与展示名，
 * 不存 route：route 是代码里的分发约定，跟着代码走，不该成为用户数据。
 * 反查不到说明是内置页签但代码已经不认它了（升级后删掉了某个内置页），
 * route 留空，分发的 else 分支会把它当卡片容器处理并给出空态，而不是崩溃。
 */
private fun DashboardTab.toTabItem(): TabItem {
    val builtin = BuiltinTabs.byTabId(tabId)
    return TabItem(
        tabId = tabId,
        label = name.ifBlank { builtin?.defaultName ?: "未命名" },
        route = builtin?.route ?: "",
        kind = if (isBuiltin) TabKind.BUILTIN else TabKind.USER,
        builtin = builtin,
        enabled = enabled,
    )
}

/**
 * 页签表被清空时的兜底页。
 *
 * 正常情况下到不了这里：内置页签不允许删除。留着是因为一旦真到了这一步，
 * 主界面会连一个页签都没有，用户除了重装没有别的路可以走。
 */
@Composable
private fun EmptyDashboard(onRestore: () -> Unit) {
    GlassBackground {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("没有可显示的页签", style = MaterialTheme.typography.titleMedium)
            Text(
                "内置页签不会被删掉，出现这个界面说明布局数据异常。可以恢复成默认布局。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRestore) { Text("恢复默认页签") }
        }
    }
}

/** 新建任务的哨兵 id，与"编辑已有任务"区分 */
private const val NEW_TASK_ID = 0L

/** 界面层偏好（当前选中页签等），与专题设置分开存 */
private const val PREFS_UI = "lifeboard_ui"
private const val KEY_SELECTED_TAB = "selected_tab_id"

/**
 * Android 端的内置页签注册表。
 *
 * ## 为什么是函数而不是常量
 *
 * 每个页面都要捕获它自己的入口参数：设置页要拿到 `appearance`、
 * 面板页要知道自己画的是哪个 `tabId`、插件页要能打开详情浮层。
 * 做成常量就只能把这些做成全局单例，那比显式传参糟得多。
 *
 * ## 为什么 `PageRoute` 的 lambda 里能直接写 Composable 调用
 *
 * `PageRoute.Content` 自己带 `@Composable`，所以 SAM 转换后的 lambda
 * 天然处于 Composable 上下文里。反过来，如果把它写成一个普通
 * `@Composable () -> Unit` 的映射，这个函数本身就必须是 `@Composable`，
 * 也就没法在 `remember` 里构造——每次重组都会重建整张表。
 *
 * ## 桌面端对应的清单
 *
 * 少 `notice`（通知监听）与 `debug`（IPC 自检）两项：那两页依赖
 * Android 特有的能力，桌面端注册不出来。缺的页签不删数据，
 * 由 `DashboardHost` 画「这一端没有此页」的占位。
 */
private fun buildAndroidPages(
    appearance: AppearanceState,
    logViewModel: LogViewModel,
    tabId: String,
    refreshKey: Int,
    onEditTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onOpenPluginDetail: (String) -> Unit,
): PageCatalog = PageCatalog.of(
    BuiltinTabs.ROUTE_PANEL to PageRoute {
        PanelScreen(
            tabId = tabId,
            onEditTask = onEditTask,
            onNewTask = onNewTask,
            refreshKey = refreshKey,
        )
    },
    BuiltinTabs.ROUTE_RULES to PageRoute { RulesScreen() },
    BuiltinTabs.ROUTE_TREND to PageRoute { TrendScreen() },
    // 数据指标页（M13）：与「添加指标卡」共用同一份候选产出（原生/自定义/插件三组），
    // 展开即显示当天值。scope 与 TaskEditViewModel 用的是同一个实现
    BuiltinTabs.ROUTE_METRICS to PageRoute {
        val context = LocalContext.current
        val scope = remember(context) {
            com.lifeboard.server.platform.AndroidPanelScope(context.applicationContext)
        }
        com.lifeboard.shared.metrics.MetricsScreen(scope = scope)
    },
    BuiltinTabs.ROUTE_REMIND to PageRoute { RemindTestScreen() },
    // 这两页是 Android 专属：通知监听与无障碍都只有 Android 有
    BuiltinTabs.ROUTE_NOTICE to PageRoute { NoticeDebugScreen() },
    BuiltinTabs.ROUTE_DEBUG to PageRoute { IpcDebugScreen() },
    BuiltinTabs.ROUTE_PLUGIN to PageRoute {
        PluginScreen(onOpenDetail = onOpenPluginDetail)
    },
    BuiltinTabs.ROUTE_SETTINGS to PageRoute {
        SettingsScreen(
            appearance = appearance,
            logViewModel = logViewModel,
        )
    },
)

