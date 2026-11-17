package com.lifeboard.shared.navigation

import androidx.compose.runtime.Composable

/**
 * 一个内置页签的页面。
 *
 * ## 为什么是「注册表」而不是 `when (route)`
 *
 * 页签清单在 `shared`（`BuiltinTabs`），具体页面在各自的模块里（Android 的无障碍、
 * 通知源页在 `:app`，桌面的页面将来同样在 `:desktop`）。中间必须有东西把
 * 「清单里的路由名」映射到「这一端真能画出来的 Composable」。
 *
 * 如果写成 `when (route) { "panel" -> PanelScreen(...); ... }`，这个 `when` 只能落在
 * 某一端——那共享的页签壳就永远搬不进 `commonMain`。做成注册表后，共享层只依赖
 * 「有个函数能把 route 变成 Composable」这件事，不依赖任何一个具体页面。
 *
 * ## 缺失路由是正常状态，不是错误
 *
 * 桌面端没有通知监听与无障碍，`notice` / `debug` 两个页面根本注册不出来。
 * 但页签行仍然在库里（从手机同步过来时不该丢页签），所以壳必须能渲染出
 * 「这一页在桌面端不可用」。这就是为什么要显式区分「没注册」与「注册了但页面空」，
 * 见 [PageCatalog.render] 的返回类型。
 */
fun interface PageRoute {
    /**
     * 画出这个页面。
     *
     * 参数全部来自共享层，不出现任何平台类型——各端注册时自己把需要的
     * 平台能力（`LocalPlatform.current`）取好、闭包捕获进去。
     */
    @Composable
    fun Content(host: PageHost)
}

/**
 * 页面能用到的壳能力。
 *
 * 只给「跳转」这一件事。页面需要的数据自己去 `LocalPlatform.current` 取——
 * 把数据也塞进来会让这个接口变成第二个依赖容器，而它存在的唯一理由是
 * 「页面要能触发导航，但导航归壳管」。
 */
interface PageHost {
    /**
     * 当前页签的 id。
     *
     * 页签**不是**路由：同一份面板界面被多个页签共用（内置「面板」+ 用户自建页签），
     * 页面要靠这个 id 去取「本页签有哪些卡片」。只有路由名的话，
     * 用户自建的页签在 `render(route)` 这一步就分辨不出来（它们的 route 都是 null）。
     */
    val tabId: String

    /**
     * 切到另一个内置页签（按路由名）。
     *
     * 用于「数据源状态卡点一下去通知源页」这类跨页跳转。
     * 路由不存在时什么都不做——目标页在这一端可能本来就没注册。
     */
    fun navigateTo(route: String)

    /**
     * 打开任务的编辑界面。
     *
     * 单独一个方法而不是 `navigateTo("task/$id")`：编辑任务是**全屏浮层**
     * 而非页签，两者在壳里的处理完全不同（页签要换选中态，浮层不换）。
     * 硬塞进路由字符串会让壳里出现「这个 route 是页签还是浮层」的判断。
     */
    fun editTask(taskId: Long)

    /** 新建任务，同上 */
    fun createTask()
}

/** 页面注册表的查询结果 */
sealed interface PageSlot {
    /** 这一端能渲染这个路由 */
    data class Resolved(val route: PageRoute) : PageSlot

    /**
     * 这一端没有这个路由的页面。
     *
     * 单独一个类型而不是返回 null：null 在调用点很容易被写成 `?: Unit`
     * 或者 `?: Text("")`，那用户看到的是**一片空白**，无法区分
     * 「这里本该有内容但桌面端不支持」和「页面加载失败」。
     */
    data class Missing(val route: String) : PageSlot
}

/**
 * 页面注册表。
 *
 * 由各端在入口构造一次：Android 在 `MainActivity`，桌面在 `Main`。
 * 存在的意义只有一句：**共享层要画页签，但不能 import 任何一端的具体页面**。
 */
class PageCatalog(private val routes: Map<String, PageRoute>) {

    companion object {
        /**
         * 用「路由名 to 页面」的成对参数构造。
         *
         * 比让调用方手写 `mapOf(...)` 短一些，也让注册点读起来像一份清单
         * 而不是一个数据结构——注册点是最需要一眼看全的地方。
         */
        fun of(vararg entries: Pair<String, PageRoute>): PageCatalog =
            PageCatalog(entries.toMap())
    }

    /**
     * 按路由名找页面。
     *
     * 不抛异常：路由名来自数据库（用户库里的页签行），可能包含
     * 更新版本才有的路由，或从别的端同步过来、这一端并不支持的页签。
     * 崩掉等于让一条历史数据锁死整个启动流程。
     */
    fun render(route: String): PageSlot =
        routes[route]?.let { PageSlot.Resolved(it) } ?: PageSlot.Missing(route)

    /** 这一端注册了哪些路由。设置页用它列出「可用/不可用」清单 */
    fun registeredRoutes(): Set<String> = routes.keys

    /**
     * 追加或覆盖若干路由。
     *
     * 只在测试与「同一个入口先后装配两次」时用得上：
     * `buildPlatformDependencies` 里传进来的映射是最终版，但个别页面
     * 需要 Activity 级能力（如权限请求回调），只能晚一步注册。
     */
    fun plus(extra: Map<String, PageRoute>): PageCatalog = PageCatalog(routes + extra)
}
