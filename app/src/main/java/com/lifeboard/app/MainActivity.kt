package com.lifeboard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.dashboard.DashboardDefaults
import com.lifeboard.app.ui.dashboard.DashboardViewModel
import com.lifeboard.app.ui.dashboard.MAX_TAB_NAME_LENGTH
import com.lifeboard.app.ui.dashboard.TabManagerDialog
import com.lifeboard.app.ui.dashboard.TabRowUi
import com.lifeboard.app.ui.dashboard.validateTabName
import com.lifeboard.app.ui.debug.IpcDebugScreen
import com.lifeboard.app.ui.log.LogScreen
import com.lifeboard.app.ui.notice.NoticeDebugScreen
import com.lifeboard.app.ui.panel.PanelScreen
import com.lifeboard.app.ui.panel.TaskEditScreen
import com.lifeboard.app.ui.panel.TrendScreen
import com.lifeboard.app.ui.plugin.PluginScreen
import com.lifeboard.app.ui.plugin.detail.PluginDetailScreen
import com.lifeboard.app.ui.remind.RemindTestScreen
import com.lifeboard.app.ui.rules.RulesScreen
import com.lifeboard.app.ui.settings.AboutScreen
import com.lifeboard.app.ui.settings.AppearanceState
import com.lifeboard.app.ui.settings.SettingsScreen
import com.lifeboard.app.ui.task.TaskIndicatorButton
import com.lifeboard.app.ui.task.TaskListDialog
import com.lifeboard.app.ui.theme.GlassBackground
import com.lifeboard.app.ui.theme.LifeBoardTheme
import com.lifeboard.server.dashboard.BuiltinTab
import com.lifeboard.server.dashboard.BuiltinTabs
import com.lifeboard.server.dashboard.TabKind
import com.lifeboard.server.db.DashboardTabEntity
import com.lifeboard.server.remind.RemindScheduler

/**
 * 应用主界面。
 *
 * 七个页签：面板（待办与得分）、规则（采集规则）、趋势（周/月表现）、
 * 提醒（自检）、通知源（采集调试）、自检（IPC 通路）、设置（外观）。
 * 任务编辑是一个覆盖全屏的子页面，不占页签。
 */
class MainActivity : ComponentActivity() {

    /** 通知权限申请结果回调，结果由系统写回权限表，这里无需额外处理 */
    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        setContent {
            // 外观状态提到 Activity 级：设置页改动后主界面立即重组换色，无需重启
            val appearance = remember { AppearanceState(this) }
            LifeBoardTheme(palette = appearance.palette, mode = appearance.mode) {
                MainScaffold(appearance)
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(appearance: AppearanceState) {
    val context = LocalContext.current
    // 选中态存 tabId 而不是下标：页签顺序可被用户调整，
    // 用下标会在「在最后一个页签时新增一个页签」之类的场景跳到别的页面
    var selectedTabId by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_TAB, BuiltinTabs.TAB_ID_PANEL)
                ?: BuiltinTabs.TAB_ID_PANEL,
        )
    }
    // 0 表示不在编辑页；非 0 为编辑中的任务 id；NEW_TASK_ID 表示新建
    var editingTaskId by remember { mutableStateOf<Long?>(null) }
    // 每次打开编辑页自增，作为 Compose key 强制重建编辑器，避免复用上次状态
    var editorSession by remember { mutableIntStateOf(0) }

    // 插件详情页：非 null 表示正在看该插件。同样用自增 session 做 key，
    // 因为两次点同一个插件时 pluginId 不变，LaunchedEffect 不会重跑
    var detailPluginId by remember { mutableStateOf<String?>(null) }
    var detailSession by remember { mutableIntStateOf(0) }
    var showLogs by remember { mutableStateOf(false) }
    var logSession by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }

    /** 面板上「重新判定数据源健康度」的触发键 */
    var healthRefreshKey by remember { mutableIntStateOf(0) }

    val dashVm: DashboardViewModel = viewModel()
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
    val tabs = if (dashState.ready) {
        dashState.tabs.map { it.toTabItem() }
    } else {
        DashboardDefaults.tabs.map { it.toTabItem() }
    }

    fun selectTab(tabId: String) {
        selectedTabId = tabId
        context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_TAB, tabId).apply()
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
                // 所以 else 兜底回面板，不能留空屏
                when (current.route) {
                    BuiltinTabs.ROUTE_PANEL -> PanelScreen(
                        tabId = current.tabId,
                        onEditTask = {
                            editorSession++
                            editingTaskId = it
                        },
                        onNewTask = {
                            editorSession++
                            editingTaskId = NEW_TASK_ID
                        },
                        refreshKey = healthRefreshKey,
                    )
                    BuiltinTabs.ROUTE_RULES -> RulesScreen()
                    BuiltinTabs.ROUTE_TREND -> TrendScreen()
                    BuiltinTabs.ROUTE_REMIND -> RemindTestScreen()
                    BuiltinTabs.ROUTE_NOTICE -> NoticeDebugScreen()
                    BuiltinTabs.ROUTE_PLUGIN -> PluginScreen(
                        onOpenDetail = { pluginId ->
                            detailSession++
                            detailPluginId = pluginId
                        },
                    )
                    BuiltinTabs.ROUTE_DEBUG -> IpcDebugScreen()
                    BuiltinTabs.ROUTE_SETTINGS -> SettingsScreen(
                        appearance = appearance,
                        onOpenLogs = {
                            logSession++
                            showLogs = true
                        },
                        onOpenAbout = { showAbout = true },
                    )
                    // 用户页签（route 为空）与一切未知路由都落到卡片容器
                    else -> PanelScreen(
                        tabId = current.tabId,
                        onEditTask = {
                            editorSession++
                            editingTaskId = it
                        },
                        onNewTask = {
                            editorSession++
                            editingTaskId = NEW_TASK_ID
                        },
                        refreshKey = healthRefreshKey,
                    )
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
                    PluginDetailScreen(
                        pluginId = detail,
                        onClose = { detailPluginId = null },
                    )
                }
            }
        }

        if (showLogs) {
            // 与插件详情页同一种覆盖层；每次打开都重建，保证看到的是当下最新的日志
            key(logSession) {
                GlassBackground {
                    LogScreen(onClose = { showLogs = false })
                }
            }
        }

        if (showAbout) {
            GlassBackground {
                AboutScreen(onClose = { showAbout = false })
            }
        }

        if (showTabManager) {
            TabManagerDialog(
                tabs = tabs.map { TabRowUi(tabId = it.tabId, label = it.label, builtin = it.builtin) },
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
 * 「+」点开的新建页签弹窗。
 *
 * 只问名字。重命名、删除、排序这些低频操作留在 [TabManagerDialog]，
 * 用一个入口跳过去——新建是高频动作，不该每次都先经过一个管理列表。
 */
@Composable
private fun NewTabDialog(
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    // 与 TabManagerDialog 共用同一套校验：这里不让叫的名字，
    // 换个入口也不能叫出来（validateTabName 返回 null 表示合法）
    val valid = validateTabName(name) != null
    val tooLong = name.length > MAX_TAB_NAME_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建页签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("页签名称") },
                    isError = tooLong,
                    supportingText = {
                        // 不截断输入，只说明原因：直接吞字符会让人以为是输入法的问题
                        Text(
                            if (tooLong) {
                                "超出 $MAX_TAB_NAME_LENGTH 个字符，请缩短后再提交"
                            } else {
                                "${name.trim().length}/$MAX_TAB_NAME_LENGTH"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onManage, contentPadding = PaddingValues(0.dp)) {
                    Text("管理现有页签", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid) {
                        onCreate(name.trim())
                        onDismiss()
                    }
                },
                enabled = valid,
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 把库里的页签行还原成界面用的 [TabItem]。
 *
 * 内置页签的 route 由 [BuiltinTabs] 反查得出——库里只存 tabId 与展示名，
 * 不存 route：route 是代码里的分发约定，跟着代码走，不该成为用户数据。
 * 反查不到说明是内置页签但代码已经不认它了（升级后删掉了某个内置页），
 * route 留空，分发的 else 分支会把它当卡片容器处理并给出空态，而不是崩溃。
 */
private fun DashboardTabEntity.toTabItem(): TabItem {
    val builtin = BuiltinTabs.byTabId(tabId)
    return TabItem(
        tabId = tabId,
        label = name.ifBlank { builtin?.defaultName ?: "未命名" },
        route = builtin?.route ?: "",
        kind = if (isBuiltin) TabKind.BUILTIN else TabKind.USER,
        builtin = builtin,
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
