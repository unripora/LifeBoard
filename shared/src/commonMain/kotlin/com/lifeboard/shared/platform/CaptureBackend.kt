package com.lifeboard.shared.platform

/**
 * 读屏采集的偏好设置（M3-3.3b）。
 *
 * ## 这一项为什么单独抽出来
 *
 * 「灭屏时是否暂停读屏」是**只有 Android 有**的开关：它的存在前提是
 * 「有一个无障碍服务在后台持续读屏」。桌面端没有读屏，也就没有这个取舍。
 *
 * 但设置页是共享的。所以做法和二选一不同：
 *
 * - 若把开关写死在共享设置页里，桌面端会出现一个**永远没有意义的选项**，
 *   开关能拨动但什么也不影响——用户会以为它在生效
 * - 若让共享页自己判断平台，就是把 `if (isAndroid)` 撒进界面
 *
 * 用 [available] 让页面据实决定画不画这一块。桌面端返回 false，
 * 整块隐藏，用户看不到不存在的功能。
 *
 * ## 为什么读同步、写异步
 *
 * 读要在首帧渲染前拿到（否则开关会先闪一下默认值）。写要通知正在运行的
 * 采集服务重读配置，那是一次跨进程/跨线程的动作。
 */
interface CaptureBackend {

    /**
     * 这一端是否有读屏采集。
     *
     * 为 false 时设置页整块不画——不是灰掉，是不画。
     * 灰掉的开关会让人以为「差一步就能开」，而这里是真的没有这个能力。
     */
    val available: Boolean

    /** 灭屏时是否暂停读屏。默认开启 */
    fun isPauseOnScreenOff(): Boolean

    /**
     * 写入偏好并**立即**通知正在运行的采集服务重读配置。
     *
     * 调用方不该需要额外再调一次「通知服务刷新」——漏掉那一步就会出现
     * 「开关拨了但行为没变」，而且不报错。
     */
    suspend fun setPauseOnScreenOff(value: Boolean)

    companion object {
        /** 没有读屏采集的宿主用它，`available = false` 让界面整块隐藏 */
        val Unavailable: CaptureBackend = object : CaptureBackend {
            override val available: Boolean = false
            override fun isPauseOnScreenOff(): Boolean = true
            override suspend fun setPauseOnScreenOff(value: Boolean) = Unit
        }
    }
}
