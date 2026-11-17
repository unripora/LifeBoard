package com.lifeboard.shared.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 「已安装应用」的查询端口（M4-4.6）。
 *
 * ## 为什么两端都需要这个概念
 *
 * 规则的本质是「监听某个应用里出现的内容」。所以规则编辑页的第一步必然是
 * 「选哪个应用」。手机上是「已安装的 App」，电脑上则是「装了哪些程序」——
 * 概念完全对应，只是查询方式、标识符形态、能从里面拿到什么不一样。
 *
 * ## 标识符的取舍
 *
 * Android 用包名（`com.ss.android.lark`），Windows 用可执行文件路径
 * （`C:\Program Files\...\Lark.exe`）。两者都是「稳定且在注册表/系统里能唯一定位」的东西，
 * 所以 [AppEntry.key] 就是各平台自己的标识符，不强行统一格式。
 *
 * **代价**：同一个应用在两端的 key 不同，规则无法跨设备互通。
 * 这是可接受的——规则里还有时段、关键词等一堆上下文，本来就不能简单搬运；
 * 若将来要做同步，需要另建一张「应用身份」映射表，而不是在 key 上做兼容层。
 *
 * ## 查不到时怎么办
 *
 * [list] 允许只返回一部分甚至空列表：Windows 上没有注册应用清单，
 * 只能扫开始菜单快捷方式与安装目录，漏掉绿色版程序很正常。
 * 因此编排侧（规则编辑页）**必须**同时允许手动输入标识符，
 * 否则「列表里找不到」就变成了功能缺失。
 */
interface AppCatalog {

    /** 列出可识别的应用，按名称排序；失败时返回空列表而不是抛异常 */
    suspend fun list(): List<AppEntry>

    /** 取单个应用的图标；拿不到时返回 null，由界面显示通用占位 */
    suspend fun icon(key: String): ImageBitmap?

    /**
     * 把标识符翻成可读名称。查不到时**应当原样返回 [key]**，
     * 让界面至少能显示点东西，而不是一片空白。
     */
    suspend fun label(key: String): String

    /** 一个可识别的应用 */
    data class AppEntry(
        /** 平台内唯一标识：Android 是包名，Windows 是可执行文件路径 */
        val key: String,
        /** 展示名称 */
        val label: String,
        /** 可选的具体路径，Windows 上用于显示在名称下方；Android 上是包名本身 */
        val detail: String = "",
    )

    companion object {
        /** 什么都查不到的实现，供「本端不采集应用」的场景与测试使用 */
        val Empty: AppCatalog = object : AppCatalog {
            override suspend fun list(): List<AppEntry> = emptyList()
            override suspend fun icon(key: String): ImageBitmap? = null
            override suspend fun label(key: String): String = key
        }
    }
}
