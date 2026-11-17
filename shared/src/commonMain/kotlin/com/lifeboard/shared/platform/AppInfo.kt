package com.lifeboard.shared.platform

/**
 * 运行环境的只读信息（M1-1.3）。
 *
 * 日志导出需要一个「用户发文件过来时不用再问一遍」的头部：
 * 版本、系统、机型。这些字段在两端的取值方式完全不同
 * （Android 读 `Build` 与 `PackageManager`，桌面读系统属性与 exe 版本），
 * 因此收成接口注入，而不是让 ViewModel 直接摸 `android.os.Build`。
 */
interface AppInfo {

    /** 包名 / 应用标识 */
    val packageId: String

    /** 展示用版本号 */
    val versionName: String

    /** 操作系统描述，如 `Android 15 (API 35)`、`Windows 11 10.0` */
    val osDescription: String

    /** 设备/机型描述 */
    val deviceDescription: String

    /** 当前时间格式化为 `yyyy-MM-dd HH:mm:ss` */
    fun nowForExport(): String

    // ───────── 以下为 M3-3.2d 新增：关于页展示用 ─────────

    /**
     * 应用显示名，如 `LifeBoard`。
     *
     * 不写死在界面里：用户可能改过桌面快捷方式的名字，
     * 关于页显示的名字应当与他在系统里看到的一致。
     */
    val appLabel: String

    /**
     * 构建号。每次构建自动递增，用来区分同一版本名的不同构建。
     *
     * 排查「你装的是哪一次构建」靠它——版本名相同但行为不同的情况
     * 在开发期非常常见，只看版本名会误判。
     */
    val buildNumber: Long

    /**
     * 最低支持的系统版本，已格式化成用户认得的写法
     * （Android 是 `Android 10`，桌面是 `Windows 10`）。
     *
     * 由实现方负责翻译：界面层不该知道什么是 API 级别。
     */
    val minPlatform: String

    /** 目标/兼容的系统版本，格式同上 */
    val targetPlatform: String

    /** 安装包的最后更新时间，用来判断手上是不是最新包；未知时为「未知」 */
    val updatedAt: String
}
