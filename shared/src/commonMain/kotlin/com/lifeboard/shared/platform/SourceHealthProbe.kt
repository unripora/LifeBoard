package com.lifeboard.shared.platform

import com.lifeboard.shared.panel.SourceHealthEntry

/**
 * 数据源健康度探测。
 *
 * 「这个源现在能不能出数」在两端含义完全不同：Android 靠通知监听与无障碍
 * 权限是否还开着，桌面端的插件源靠上次采集有没有成功。
 * 抽成接口后，界面只问「健康吗、为什么」，不必各自写一套平台判断。
 *
 * ## 为什么返回 [SourceHealthEntry] 而不是一个布尔
 *
 * 面板上「这个源坏了」要能说清三件事，缺一件用户就不知道该怎么办：
 *
 * 1. **哪个源** —— 显示名（`小米健康` 而不是 `MIHEALTH`）
 * 2. **为什么坏** —— `缺通知读取权限`
 * 3. **怎么修** —— 点一下跳到哪里去开（`actionKey`）
 *
 * 只给 `healthy: Boolean` 的话，这三个问题都要界面层自己再拼一遍，
 * 而拼的过程需要平台知识（哪些源需要哪个权限），等于把平台判断漏回界面。
 * 所以这里直接返回已经拼好的 [SourceHealthEntry]。
 */
interface SourceHealthProbe {

    /**
     * 探测全部数据源，返回所有**需要用户处理**的项。
     *
     * 「需要处理」指缺权限或二期未接入。健康的源不必返回——
     * 面板只在出问题时才提示，正常时返回空列表即可。
     *
     * 顺序按注册表的声明顺序，界面直接展示，不再排序。
     */
    suspend fun probeAll(): List<SourceHealthEntry>

    /**
     * 打开某个源对应的系统设置页。
     *
     * [actionKey] 来自 [SourceHealthEntry.actionKey]，是探测器自己发的凭据，
     * 界面层只负责原样传回，不需要知道它对应哪个系统页面。
     *
     * 返回 false 表示这个键不认识（版本不匹配或已被移除），界面应忽略这次点击。
     */
    fun openSettings(actionKey: String): Boolean
}
