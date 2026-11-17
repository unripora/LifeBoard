package com.lifeboard.server.platform

import android.content.Context
import android.net.Uri
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.platform.AppIconProvider
import com.lifeboard.shared.platform.FilePicker
import com.lifeboard.shared.platform.PickedFile
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.TextFileExporter

/**
 * Android 端的依赖装配（M3-3.3）。
 *
 * ## 这个类是唯一的 Android 装配点
 *
 * 在此之前，11 个平台实现被 7 处 `viewModelFactory` 各自 `new` 出来，
 * 其中 `AndroidFilePicker` 因为需要 Activity 的 launcher，根本没法在
 * `viewModelFactory` 里构造——所以有些页面索性能不给选择器就不给。
 * 收敛到这里之后，装配只发生两次（见下），其余全部走注入。
 *
 * ## 为什么分两个入口
 *
 * [forProcess] 只需要 `Context`，能给的都给；
 * [AndroidFilePicker] 需要 `ActivityResultLauncher`，而它**必须在 Activity 创建时注册**
 * （晚了会抛 `LifecycleOwner is attempting to register while current state is RESUMED`），
 * 所以带选择器的那份只能在 `setContent` 里构造。
 *
 * 不做「先给个假 Picker 之后再替换」——`PlatformDependencies` 是不可变的，
 * 替换等于重建整棵树，反而把状态全丢了。
 */
object AndroidPlatformDependencies {

    /**
     * 构造一份不带文件选择器的依赖。
     *
     * 给「不在 Activity 里也能跑」的场景用：`Application.onCreate` 里做的一次性装配、
     * 单元测试、以及不需要选文件的页面。
     * 用到 [FilePicker] 的地方会拿到 [UnavailableFilePicker] 并立刻报错，
     * 而不是静默返回 null 让用户以为「我取消了」。
     */
    fun forProcess(context: Context): PlatformDependencies {
        val app = context.applicationContext
        return assemble(app, UnavailableFilePicker, UnavailableAppIcon, TextFileExporter.Unavailable)
    }

    /**
     * 构造完整依赖，`filePicker` 与 `appIcon` 由 Activity 提供。
     *
     * @param launcher 弹系统文件选择器。界面层用
     *   `registerForActivityResult(ActivityResultContracts.OpenDocument())` 包一层传入。
     * @param appIcon 应用自身图标。**必须由调用方传**：它的实现要用
     *   `androidx.compose.ui.graphics.ImageBitmap`，而 `:server` 没有 Compose 依赖，
     *   所以实现在 `:app` 里。
     * @param textExporter 日志导出（M3-3.2d）。同样实现在 `:app`：
     *   要用 `FileProvider` 与 `startActivity` 弹系统分享面板。
     */
    fun forActivity(
        context: Context,
        launcher: (mimeTypes: Array<String>, onResult: (Uri?) -> Unit) -> Unit,
        appIcon: AppIconProvider,
        textExporter: TextFileExporter,
    ): PlatformDependencies {
        val app = context.applicationContext
        return assemble(app, AndroidFilePicker(app, launcher), appIcon, textExporter)
    }

    /**
     * 两个入口的唯一差异就是选择器，其余逐行相同。
     *
     * 若各写一份，将来加一个能力（比如「壁纸取色」）只改了一处，
     * 另一条路径就会在运行时报「未注入」——而两条路径的差别在源码里看不出来。
     *
     * [dashboardStore] 取一次传给两个位置：`AndroidDashboardBackend` 与
     * `PlatformDependencies.dashboardStore` 必须是**同一个实例**，
     * 否则「后端写入默认布局」与「界面读布局」可能落在两个不同的连接上
     * （Store 内部带缓存时尤其明显）。
     */
    private fun assemble(
        app: Context,
        filePicker: FilePicker,
        appIcon: AppIconProvider,
        textExporter: TextFileExporter,
    ): PlatformDependencies {
        val dashboardStore = DatabaseProviders.dashboardStore(app)
        val logStore = DatabaseProviders.logStore(app)
        return PlatformDependencies(
            settings = AndroidSettingsStore(app),
            appInfo = AndroidAppInfo(app),
            filePicker = filePicker,
            notifier = AndroidNotifier(app),
            scheduler = AndroidScheduler(app),
            remind = AndroidRemindBackend(app),
            rules = AndroidRulesBackend(app),
            dashboard = AndroidDashboardBackend(dashboardStore),
            screenTime = AndroidScreenTimeBackend(app),
            sourceHealth = AndroidSourceHealthProbe(app),
            plugins = AndroidPluginHost(app),
            pluginInvoker = AndroidPluginInvoker(app),
            // 详情页的「全部数据」：纯查库，不触发采集
            pluginDetailStore = com.lifeboard.server.plugin.AndroidPluginDetailStore(app),
            panelScope = AndroidPanelScope(app),
            dashboardStore = dashboardStore,
            // 设置页的三块平台能力（M3-3.3b）：刷新周期、读屏偏好、应用图标
            collection = AndroidCollectionBackend(app),
            capture = AndroidCaptureBackend(app),
            appIcon = appIcon,
            textExporter = textExporter,
            // 日志页（M3-3.2d）与 panelScope 共用同一个库，但 Store 实例各自缓存
            // 连接，交给 DatabaseProviders 保证是同一份
            logStore = logStore,
            // 规则编辑页要「选应用」（M4-4.6）：Android 侧查已安装 App 的包名与图标
            appCatalog = com.lifeboard.app.platform.AndroidAppCatalog(app),
        )
    }
}

/**
 * `forProcess` 路径下的图标占位。
 *
 * 返回 null 而不是抛异常：图标画不出来不会影响任何功能，用首字母占位即可。
 * 这和 [UnavailableFilePicker] 的处理方式不同——那边返回 null 会被理解成
 * 「用户取消了」，语义被污染；这里 null 的语义就是「没有图标」，没有歧义。
 */
private object UnavailableAppIcon : AppIconProvider {
    override suspend fun appIconPng(): ByteArray? = null
}

/**
 * 没有 Activity 时的占位选择器。
 *
 * 故意抛异常而不是返回 null：调用方对 null 的理解是「用户取消了」，
 * 那会让「装配漏了 FilePicker」伪装成一次正常的用户操作，
 * 用户反复点「安装插件」却毫无反应，日志里也查不出原因。
 */
private object UnavailableFilePicker : FilePicker {
    override suspend fun pickFile(mimeTypes: List<String>): PickedFile? {
        error(
            "FilePicker 未装配：当前 PlatformDependencies 由 forProcess() 构造，" +
                "不含文件选择器。请在 Activity 的 setContent 里改用 forActivity(...)。",
        )
    }
}
