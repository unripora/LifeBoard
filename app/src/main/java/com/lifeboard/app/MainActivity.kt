package com.lifeboard.app

import android.Manifest
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.core.content.ContextCompat
import com.lifeboard.app.ui.debug.IpcDebugScreen
import com.lifeboard.app.ui.mihealth.MiHealthLoginScreen
import com.lifeboard.app.ui.notice.NoticeDebugScreen
import com.lifeboard.app.ui.panel.PanelScreen
import com.lifeboard.app.ui.panel.TaskEditScreen
import com.lifeboard.app.ui.panel.TrendScreen
import com.lifeboard.app.ui.remind.RemindTestScreen
import com.lifeboard.app.ui.rules.RulesScreen
import com.lifeboard.app.ui.settings.AppearanceState
import com.lifeboard.app.ui.settings.SettingsScreen
import com.lifeboard.app.ui.theme.GlassBackground
import com.lifeboard.app.ui.theme.LifeBoardTheme
import com.lifeboard.server.collect.DailyAggregateWorker
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

private enum class Tab(val label: String) {
    PANEL("面板"),
    RULES("规则"),
    TREND("趋势"),
    REMIND("提醒"),
    NOTICE("通知源"),
    DEBUG("自检"),
    SETTINGS("设置"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(appearance: AppearanceState) {
    var selected by remember { mutableIntStateOf(0) }
    // 0 表示不在编辑页；非 0 为编辑中的任务 id；NEW_TASK_ID 表示新建
    var editingTaskId by remember { mutableStateOf<Long?>(null) }
    // 每次打开编辑页自增，作为 Compose key 强制重建编辑器，避免复用上次状态
    var editorSession by remember { mutableIntStateOf(0) }
    // 小米账号登录页同样作为覆盖层，不与编辑页叠加
    var showMiHealthLogin by remember { mutableStateOf(false) }

    /** 小米登录页关闭后自增，用于让面板重新判定数据源健康度 */
    var healthRefreshKey by remember { mutableIntStateOf(0) }
    val tabs = Tab.entries

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
                TabRow(
                    selectedTabIndex = selected,
                    containerColor = Color.Transparent,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selected == index,
                            onClick = { selected = index },
                            text = {
                                Text(
                                    tab.label,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
                when (tabs[selected]) {
                    Tab.PANEL -> PanelScreen(
                        onEditTask = {
                            editorSession++
                            editingTaskId = it
                        },
                        onNewTask = {
                            editorSession++
                            editingTaskId = NEW_TASK_ID
                        },
                        onOpenMiHealthLogin = { showMiHealthLogin = true },
                        refreshKey = healthRefreshKey,
                    )
                    Tab.RULES -> RulesScreen()
                    Tab.TREND -> TrendScreen()
                    Tab.REMIND -> RemindTestScreen()
                    Tab.NOTICE -> NoticeDebugScreen()
                    Tab.DEBUG -> IpcDebugScreen()
                    Tab.SETTINGS -> SettingsScreen(
                        appearance = appearance,
                        onOpenMiHealthLogin = { showMiHealthLogin = true },
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

        if (showMiHealthLogin) {
            // 同样是第二层覆盖页，带自己的返回处理
            GlassBackground {
                MiHealthLoginScreen(
                    onDone = {
                        showMiHealthLogin = false
                        // 登录可能刚完成，面板上的数据源状态需要重新判定，
                        // 否则会一直显示「未登录小米账号」
                        healthRefreshKey++
                    },
                )
            }
        }
    }
}

/** 新建任务的哨兵 id，与"编辑已有任务"区分 */
private const val NEW_TASK_ID = 0L
