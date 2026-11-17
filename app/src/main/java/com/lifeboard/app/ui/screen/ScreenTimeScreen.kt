package com.lifeboard.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lifeboard.server.collect.SettingsNavigator
import com.lifeboard.server.platform.AndroidScreenTimeBackend
import com.lifeboard.shared.screentime.ScreenTimeContent as SharedScreenTimeContent
import com.lifeboard.shared.screentime.ScreenTimeUiState as SharedScreenTimeUiState

@Composable
private fun rememberScreenTimeViewModel(): ScreenTimeViewModel = viewModel(
    factory = viewModelFactory {
        initializer {
            val app = checkNotNull(this[APPLICATION_KEY]) { "缺少 Application，无法构造时长面板" }
            ScreenTimeViewModel(AndroidScreenTimeBackend(app))
        }
    },
)

/**
 * 屏幕时长面板（M1.4）。
 *
 * 未授权时优先展示授权卡片，把「去开启」按钮放在最显眼的位置；
 * 授权后才展示数据卡片，避免用户在无数据时误以为是程序坏了。
 */
@Composable
fun ScreenTimeScreen(vm: ScreenTimeViewModel = rememberScreenTimeViewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTimeContent(vm)
    }
}

/**
 * 屏幕时长内容区（不含滚动与外层 padding）。
 *
 * 拆出来是为了能被嵌进其它可滚动页面（如趋势页），避免嵌套滚动导致手势打架。
 *
 * ## M11：卡片本体已下沉
 *
 * 卡片、占比条、周柱状图现在都在 `shared.screentime`，桌面端跑的是同一份代码。
 * 这里只剩「Android 的依赖装配」：把系统授权页跳转（[SettingsNavigator.openUsageAccess]）
 * 传进去。共享层不能引用 `Intent`，所以这件事只能留在平台侧。
 */
@Composable
fun ScreenTimeContent(vm: ScreenTimeViewModel = rememberScreenTimeViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SharedScreenTimeContent(
        state = SharedScreenTimeUiState(
            loading = state.loading,
            hasPermission = state.hasPermission,
            todayMinutes = state.todayMinutes,
            weekMinutes = state.weekMinutes,
            weekDaily = state.weekDaily,
            appUsage = state.appUsage,
            lastMessage = state.lastMessage,
        ),
        onOpenPermission = { SettingsNavigator.openUsageAccess(context) },
        onRecheck = vm::refresh,
        onCollect = vm::collectNow,
    )
}
