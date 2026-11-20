package com.lifeboard.app.ui.plugin

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.ui.plugin.BrokenRow
import com.lifeboard.shared.ui.plugin.PendingRow
import com.lifeboard.shared.ui.plugin.PluginRow
import com.lifeboard.shared.ui.plugin.PluginUiState
import com.lifeboard.shared.ui.plugin.PluginViewModel as SharedPluginViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * 插件页的 Android 外壳。
 *
 * ## 为什么现在只剩这么点代码
 *
 * 原来的 490 行里，没有一行真正依赖 Android：读列表走 `PluginManager`、
 * 登录走插件契约、健康度读 `source_health` 表——这些桌面端一模一样。
 * 逻辑已经搬进 [SharedPluginViewModel]，这里只留两件 Android 必须自己做的事：
 *
 * 1. **持有生命周期**：`:shared` 不依赖 `androidx.lifecycle`，
 *    所以由本类做 `ViewModel`，把 [viewModelScope] 交给共享实现，
 *    界面销毁时协程跟着取消。
 * 2. **拿到依赖**：`PlatformDependencies` 的装配在 `MainActivity` 完成
 *    （`FilePicker` 需要 Activity 的 launcher），只能由 Activity 注入进来。
 *
 * ## 为什么不再自己 refresh
 *
 * 共享实现不会在构造时自动拉数据——它不知道调用方的协程是不是已经就绪。
 * 拉取的时机交给界面（`LaunchedEffect(Unit)`），本类只负责转发。
 *
 * ## 关于 [application]
 *
 * 保留这个参数是为了让 `viewModelFactory` 的 `APPLICATION_KEY` 取用方式不变，
 * 逻辑上已不需要它：共享实现只依赖注入进来的契约，不碰 `Context`。
 */
class PluginViewModel(
    @Suppress("UNUSED_PARAMETER") application: Application,
    deps: PlatformDependencies,
) : ViewModel() {

    private val delegate = SharedPluginViewModel(
        deps = deps,
        scope = viewModelScope,
    )

    val state: StateFlow<PluginUiState> = delegate.state

    fun refresh() = delegate.refresh()

    fun confirmOverwrite() = delegate.confirmOverwrite()

    fun cancelOverwrite() = delegate.cancelOverwrite()

    fun trust(row: PendingRow) = delegate.trust(row)

    fun revokeTrust(row: PluginRow) = delegate.revokeTrust(row)

    fun uninstall(row: PluginRow, clearData: Boolean) = delegate.uninstall(row, clearData)

    fun deletePending(row: PendingRow) = delegate.deletePending(row)

    fun deleteBroken(row: BrokenRow) = delegate.deleteBroken(row)

    fun clearCredentials(row: PluginRow) = delegate.clearCredentials(row)

    fun collectNow(row: PluginRow) = delegate.collectNow(row)

    fun consumeMessage() = delegate.consumeMessage()

    fun startLogin(row: PluginRow) = delegate.startLogin(row)

    fun updateLoginValue(name: String, value: String) = delegate.updateLoginValue(name, value)

    fun submitLoginForm() = delegate.submitLoginForm()

    fun cancelLogin() = delegate.cancelLogin()

    fun restartLogin() = delegate.restartLogin()

    fun logout(row: PluginRow) = delegate.logout(row)
}
