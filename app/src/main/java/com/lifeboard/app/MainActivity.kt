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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lifeboard.app.ui.debug.IpcDebugScreen
import com.lifeboard.app.ui.notice.NoticeDebugScreen
import com.lifeboard.app.ui.panel.PanelScreen
import com.lifeboard.app.ui.panel.TaskEditScreen
import com.lifeboard.app.ui.panel.TrendScreen
import com.lifeboard.app.ui.plugin.PluginScreen
import com.lifeboard.app.ui.remind.RemindTestScreen
import com.lifeboard.app.ui.rules.RulesScreen
import com.lifeboard.app.ui.settings.AppearanceState
import com.lifeboard.app.ui.settings.SettingsScreen
import com.lifeboard.app.ui.theme.GlassBackground
import com.lifeboard.app.ui.theme.LifeBoardTheme
import com.lifeboard.server.collect.DailyAggregateWorker
import com.lifeboard.server.dashboard.BuiltinTabs
import com.lifeboard.server.dashboard.DashboardLayout
import com.lifeboard.server.dashboard.TabKind
import com.lifeboard.server.mihealth.MiHealthMigration
import com.lifeboard.server.plugin.PluginManager
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
        // 进入应用即确保每日聚合任务已注册，避免首次安装后忘记调度
        DailyAggregateWorker.schedule(this)
        // 扫描已安装的 .ext 插件并注册进采集注册表。
        // 放在主线程之外：读 APK 元数据与实例化 dex 都有 IO，不能卡启动。
        // 失败不影响启动——插件是可选能力，坏了也不能让应用起不来。
        Thread {
            runCatching { PluginManager.refresh(applicationContext) }
            // 小米健康在 M5 从内置源改成了 .ext 插件。
            // 内置实现产出过的历史行已经没有生产者，留着会让面板出现
            // 一张点开永远不更新的卡片，所以一次性清掉，之后靠标记不再执行。
            // runBlocking 是安全的：这里是独立后台线程，不会阻塞 UI
            runCatching {
                kotlinx.coroutines.runBlocking {
                    MiHealthMigration.cleanupLegacyRows(applicationContext)
                    // M6a：首次启动写入内置页签与默认卡片。
                    // 幂等，已存在则直接返回，不会覆盖用户的调整
                    DashboardLayout.ensureInitialized(applicationContext)
                }
            }
        }.start()
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

    /** 面板上「重新判定数据源健康度」的触发键 */
    var healthRefreshKey by remember { mutableIntStateOf(0) }

    val tabs = remember {
        BuiltinTabs.ALL.map {
            TabItem(tabId = it.tabId, label = it.defaultName, route = it.route, kind = TabKind.BUILTIN)
        }
    }

    fun selectTab(tabId: String) {
        selectedTabId = tabId
        context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_TAB, tabId).apply()
    }

    // 存的 tabId 可能已经不存在（页签被删、或来自降级后的旧版本），
    // 一律回退到面板，否则会停在一个空白页
    val selectedIndex = tabs.indexOfFirst { it.tabId == selectedTabId }.takeIf { it >= 0 } ?: 0
    val current = tabs[selectedIndex]

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
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // 8 个页签在窄屏上放不下，必须可横向滚动：
                // 用 TabRow 会等分压缩，标签被截成两三个字就看不出是什么了
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    containerColor = Color.Transparent,
                    edgePadding = 8.dp,
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
                // 按 route 分发而不是穷尽枚举：用户页签没有枚举值，
                // 而且删掉内置页签（M6b）后也必须有个去处，
                // 所以 else 兜底回面板，不能留空屏
                when (current.route) {
                    BuiltinTabs.ROUTE_PANEL -> PanelScreen(
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
                    BuiltinTabs.ROUTE_PLUGIN -> PluginScreen()
                    BuiltinTabs.ROUTE_DEBUG -> IpcDebugScreen()
                    BuiltinTabs.ROUTE_SETTINGS -> SettingsScreen(
                        appearance = appearance,
                    )
                    else -> PanelScreen(
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
    }
}

/** 新建任务的哨兵 id，与"编辑已有任务"区分 */
private const val NEW_TASK_ID = 0L

/** 界面层偏好（当前选中页签等），与专题设置分开存 */
private const val PREFS_UI = "lifeboard_ui"
private const val KEY_SELECTED_TAB = "selected_tab_id"
