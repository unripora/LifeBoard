package com.lifeboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lifeboard.app.ui.debug.IpcDebugScreen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    val tabs = Tab.entries

    val editing = editingTaskId
    if (editing != null) {
        // 任务编辑是全屏子页面，不走下面的 Scaffold，所以必须自带背景容器。
        // 否则它会铺在 Activity 默认窗口底色（白）上，深色模式下就是一片白底白字。
        GlassBackground {
            TaskEditScreen(
                taskId = editing,
                onDone = { editingTaskId = null },
            )
        }
        return
    }

    // 玻璃主题下的页面底色：官方 background + 一层淡主色渐变，内容面板以半透明叠在其上
    GlassBackground {
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
                        onEditTask = { editingTaskId = it },
                        onNewTask = { editingTaskId = NEW_TASK_ID },
                    )
                    Tab.RULES -> RulesScreen()
                    Tab.TREND -> TrendScreen()
                    Tab.REMIND -> RemindTestScreen()
                    Tab.NOTICE -> NoticeDebugScreen()
                    Tab.DEBUG -> IpcDebugScreen()
                    Tab.SETTINGS -> SettingsScreen(appearance)
                }
            }
        }
    }
}

/** 新建任务的哨兵 id，与"编辑已有任务"区分 */
private const val NEW_TASK_ID = 0L
