package com.lifeboard.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.lifeboard.shared.panel.PanelScope
import com.lifeboard.shared.repository.DashboardStore

/**
 * 全部宿主能力的组装容器（M3-3.3）。
 *
 * ## 为什么要这个类
 *
 * `M1` 把平台能力拆成了 10 个接口，但拆完之后**没有人负责把它们装到一起**：
 * 组装动作散在 7 处 `viewModelFactory` 里，每处各自 `new` 一个 Android 实现。
 * 这带来两个后果：
 *
 * 1. 增删一个能力要改 7 个地方，漏掉一处就是运行时崩溃而不是编译错误；
 * 2. 桌面端永远接不上——因为没有任何单一的「装配点」可以替换。
 *
 * 有了这个容器，装配点收敛成一处：Android 端构造一个 [PlatformDependencies]，
 * 桌面端构造另一个，界面层完全不知道自己跑在哪儿。
 *
 * ## 为什么用不可变 data class 而不是可变单例
 *
 * 全局单例的隐患是「测试之间共享了状态」。这个容器是**只读的持有者**——
 * 装进去的实现本身有没有状态是它们自己的事，容器不做二次缓存，
 * 所以为测试构造一份假的依赖不会污染别处。
 *
 * @param settings 键值配置。日志、外观、页签选中态等都在这里
 * @param appInfo 只读的运行环境信息，用于日志导出头部
 * @param filePicker 选文件（插件安装、数据导入）
 * @param notifier 发通知
 * @param scheduler 定时唤醒
 * @param remind 提醒验证页所需的组合能力
 * @param rules 规则页所需的组合能力
 * @param dashboard 仪表盘默认布局的写入
 * @param screenTime 屏幕时长面板的数据来源
 * @param sourceHealth 数据源健康度探测
 * @param plugins 插件宿主
 * @param panelScope 面板业务层的数据与副作用入口
 * @param dashboardStore 仪表盘页签布局的读写
 * @param collection 数据源自动刷新的周期（M3-3.3b）。桌面端暂不支持时用
 *   [CollectionBackend.Unsupported]，设置页据此隐藏整块选项
 * @param capture 读屏采集偏好（M3-3.3b）。桌面端用
 *   [CaptureBackend.Unavailable]，设置页据此隐藏「灭屏暂停」开关
 * @param appIcon 应用自身图标（M3-3.3b）。两端都实现——桌面端取打包资源
 * @param textExporter 日志导出（M3-3.2d）。Android 走分享面板，桌面走保存对话框；
 *   [TextFileExporter.Unavailable] 时日志页不画导出按钮
 * @param logStore 运行日志的读写（M3-3.2d）。日志与任务、指标在同一份 SQLite 里，
 *   页面要分页查、要清空，所以直接给 Store
 * @param appCatalog 已安装应用的查询（M4-4.6）。规则编辑页要「选应用」，
 *   Android 查已安装 App、Windows 查已安装程序；[AppCatalog.Empty] 时列表为空，
 *   但仍可手动填写标识符
 */
data class PlatformDependencies(
    val settings: SettingsStore,
    val appInfo: AppInfo,
    val filePicker: FilePicker,
    val notifier: Notifier,
    val scheduler: Scheduler,
    val remind: RemindBackend,
    val rules: RulesBackend,
    val dashboard: DashboardBackend,
    val screenTime: ScreenTimeBackend,
    val sourceHealth: SourceHealthProbe,
    val plugins: PluginHost,
    /**
     * 插件的业务调用入口。
     *
     * 与 [plugins] 分开的理由见 [PluginInvoker] 的注释：列表页只需要前者，
     * 详情页才需要后者；合成一个会让「只想列个插件名字」的实现被迫提供采集链路。
     */
    val pluginInvoker: PluginInvoker,
    /**
     * 详情页读「宿主自己存的原始条目」的入口。
     *
     * 再单列一个而不是塞进 [pluginInvoker]：那个接口的语义是「让插件去干活」，
     * 而读原始条目完全不碰插件，只是查库。
     */
    val pluginDetailStore: PluginDetailStore,
    val panelScope: PanelScope,
    val dashboardStore: DashboardStore,
    val collection: CollectionBackend,
    val capture: CaptureBackend,
    val appIcon: AppIconProvider,
    val textExporter: TextFileExporter,
    val logStore: com.lifeboard.shared.repository.LogStore,
    val appCatalog: AppCatalog,
)

/**
 * 依赖下发的通道。
 *
 * ## 为什么是 static 而不是 dynamic
 *
 * `staticCompositionLocalOf` 在值变化时**整棵子树重组**，`compositionLocalOf` 只重组
 * 读到它的地方。这里的值在进程生命周期内不会变（装配一次，之后一直是同一个对象），
 * 用 static 版本更省：不需要为它维护订阅记录。
 *
 * 反过来说，**将来若要支持「运行时热替换实现」（比如测试里想中途换掉 FilePicker），
 * 必须改成 `compositionLocalOf`**，否则改了也不生效。
 *
 * ## 没有默认值，是故意的
 *
 * 给一个「看起来能用」的默认实现，会让「桌面端忘了装配」表现成
 * 「通知永远发不出去但不报错」这种极难排查的故障。
 * 这里直接抛异常，问题在第一次调用时就暴露，且堆栈直接指向缺装配的页面。
 */
val LocalPlatform = staticCompositionLocalOf<PlatformDependencies> {
    error(
        "PlatformDependencies 未注入：请在入口用 PlatformProvider { ... } 包裹整棵界面树。" +
            "Android 端见 MainActivity，桌面端见 desktop 的 Main。",
    )
}

/**
 * 把 [deps] 下发到子树。
 *
 * 入口只该有一处调用（Android 是 `setContent` 里的第一层，
 * 桌面是 `application { Window { ... } }` 里的第一层）。
 */
@Composable
fun PlatformProvider(
    deps: PlatformDependencies,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalPlatform provides deps, content = content)
}

/** 取当前依赖。等价于 `LocalPlatform.current`，提供这个别名是为了让调用处读起来更短 */
val platform: PlatformDependencies
    @Composable get() = LocalPlatform.current
