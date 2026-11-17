package com.lifeboard.shared.platform

import kotlinx.coroutines.flow.Flow

/**
 * 插件安装结果。
 *
 * 与 `:server` 的 `InstallOutcome` 有意区分：那个类里带着 `File`、`PluginIdentity`
 * 等 Android 侧才能构造的东西。契约层只保留「用户需要看到什么」，
 * 具体实现负责把自己的类型翻译成这几种。
 */
sealed interface PluginInstallResult {

    /** 装好了，但还没信任——用户要先看指纹 */
    data class Installed(val packageName: String, val versionName: String) : PluginInstallResult

    /** 同名插件已存在，等用户确认是否覆盖 */
    data class Conflict(
        val packageName: String,
        val incomingVersionName: String,
        val existingVersionName: String,
    ) : PluginInstallResult

    /** 包坏了、签名缺失、格式不认识 */
    data class Failed(val reason: String) : PluginInstallResult
}

/** 已装插件在界面上要展示的一行 */
data class PluginSummary(
    val pluginId: String,
    val displayName: String,
    val packageName: String,
    val versionName: String,
    val archiveName: String,
    val metricCount: Int,
    val trusted: Boolean,
    val signatureHashes: List<String>,
    /** 有凭据槽位即视为需要登录；空表示插件不需要账号 */
    val needsLogin: Boolean,
)

/** 尚未信任 / 加载失败的包 */
data class PendingPlugin(
    val archiveName: String,
    val packageName: String,
    val versionName: String,
    val unsigned: Boolean,
    val reason: String,
    val signatureHashes: List<String>,
)

/**
 * 一次完整的插件盘面：已装可用、待信任、加载失败。
 *
 * 三者一起给而不是分三个接口，是因为界面上它们是**同一次扫描的三个分区**。
 * 分开取会出现「已装列表已经是新版、待信任列表还是旧版」的中间态，
 * 用户看到的是「同一个包同时出现在两栏里」。
 */
data class PluginList(
    val installed: List<PluginSummary> = emptyList(),
    val pending: List<PendingPlugin> = emptyList(),
    val broken: List<PendingPlugin> = emptyList(),
    /** 至少完成过一次扫描。false 时界面应显示加载态，而不是「一个插件都没有」 */
    val ready: Boolean = false,
)

/**
 * 插件宿主。
 *
 * 把「装了什么、信不信、登没登录」这些**与平台无关的编排**抽出来，
 * 让 ViewModel 不再直接碰 `PluginManager`（它依赖 `PackageManager`、
 * `Application` 上下文与 Android 的文件 API，桌面端一个都用不了）。
 *
 * 注意：契约里不出现 `File` 与 `Uri`。Android 侧需要真实文件才能覆盖安装，
 * 但那属于实现内部的事——实现方自己按 [PickedFile.uri] 找回文件即可。
 */
interface PluginHost {

    /**
     * 本机能否运行 JS 插件。
     *
     * false 表示引擎 native 库加载不出来（典型成因：APK 只编了部分 ABI，
     * 而设备属于未覆盖的架构）。这种情况下：
     *   - 插件页整体降级为「本机不支持」，不列插件、不给导入入口
     *   - 其余功能（待办、屏幕使用、手动打卡）完全不受影响
     *
     * 之所以把它放进契约而不是让实现方自己崩溃：插件是可选的，
     * 引擎缺失不该让整个 App 打不开。实现方**不得**在构造期就因缺库抛出。
     */
    fun engineAvailable(): Boolean

    /**
     * 插件盘面的持续观察。
     *
     * 实现方负责在**插件目录发生变化时自己重扫并推新值**——用户把包拷进目录、
     * 覆盖成新版本、删掉包，界面都应当自动跟上，而不是等用户想起来点刷新。
     *
     * 首值不要求 `ready = true`：首次扫描可能要解压并重建 QuickJS 运行时，
     * 让调用方先拿到一个「加载中」的状态，比让它对着空列表干等更诚实。
     */
    fun snapshots(): Flow<PluginList>

    /** 重新扫描已装插件，并刷新宿主内部的缓存 */
    suspend fun refresh(): List<PluginSummary>

    /** 尚未信任的包 */
    suspend fun pending(): List<PendingPlugin>

    /** 加载失败的包 */
    suspend fun broken(): List<PendingPlugin>

    /** 用户选完文件后的安装动作。安装不等于信任 */
    suspend fun install(file: PickedFile): PluginInstallResult

    /** 确认覆盖已存在的同名插件 */
    suspend fun confirmOverwrite(packageName: String): Boolean

    /** 放弃这次覆盖安装，清掉暂存文件 */
    suspend fun discardStaged()

    /** 信任某个包名 + 签名集合 */
    suspend fun trust(packageName: String, signatureHashes: Set<String>)

    /** 撤销信任：停止调度，保留包与数据 */
    suspend fun revokeTrust(pluginId: String)

    /** 卸载：删包并撤销信任，保留凭据与私有数据 */
    suspend fun uninstall(pluginId: String)

    /** 删除一个还没信任的包（也用于清掉加载失败的包） */
    suspend fun uninstallPending(archiveName: String)

    /** 清除某插件的全部凭据，让它回到未登录 */
    suspend fun clearCredentials(pluginId: String): Int
}

/**
 * 详情页「全部数据」里的一组原始条目。
 *
 * @property displayName 展示名取自插件声明的指标；插件已不再声明该指标时退回 metricKey
 * @property records 按时间升序
 */
data class PluginRawGroup(
    val metricKey: String,
    val displayName: String,
    val unit: String,
    val records: List<com.lifeboard.plugin.api.RawRecord>,
)

/**
 * 一段原始数据的查询结果。
 *
 * 为什么把「查过但没数据」的指标也带上：插件确实请求过这个指标、接口返回了 0 条，
 * 这和「压根没采这个指标」是两件事。合并成一个空列表的话，
 * 用户看到的就是「少了一个指标」，会误判成漏采。
 */
data class PluginRawPage(
    val groups: List<PluginRawGroup> = emptyList(),
    /** 库里已有原始数据的日期范围，`null` 表示这个插件从没上报过 */
    val available: Pair<String, String>? = null,
)

/**
 * 详情页需要的插件数据读取。
 *
 * 与 [PluginInvoker] 分开：那个是「让插件干活」，这里是「读宿主自己存下的东西」。
 * 读原始条目不会触发采集、不发网络请求——详情页会被反复打开、时间段会被反复切换，
 * 每次都去拉一次接口既慢又浪费。
 */
interface PluginDetailStore {

    /**
     * 读某插件在 `[from, to]`（含端点，`yyyy-MM-dd`）内的原始条目。
     *
     * @param from 为 null 表示这个插件没有任何数据，调用方应展示空态
     */
    suspend fun rawRecords(pluginId: String, from: String?, to: String): PluginRawPage
}
