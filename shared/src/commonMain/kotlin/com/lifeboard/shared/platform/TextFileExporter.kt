package com.lifeboard.shared.platform

/**
 * 把一段文本交给用户带走（M3-3.2d）。
 *
 * ## 为什么单独立一个接口而不是复用 [FilePicker]
 *
 * 方向相反：`FilePicker` 是**从用户那里拿**文件，这是**给用户**文件。
 * 复用会让实现方在一个方法里靠参数区分两种语义，
 * 而两端的实现方式差得很远：
 *
 * - Android：写进 `cacheDir`，再经 `FileProvider` 转 `content://`，
 *   用 `ACTION_SEND` 弹出系统分享面板——用户自己决定发给谁（微信、邮件、文件管理器）。
 * - 桌面：弹保存对话框，直接写到用户选的路径。没有「分享」这一环。
 *
 * ## 界面只关心成功与否
 *
 * [export] 返回 `true` 表示用户完成了导出，`false` 表示取消。
 * 具体走哪条路是平台的事，界面不该出现「分享」这种 Android 词。
 *
 * [available] 为 false 时界面**不画导出按钮**——不是灰掉。
 * 灰掉会让用户以为差一步就能用，然后去找一个根本不存在的开关。
 */
interface TextFileExporter {

    val available: Boolean

    /**
     * 导出文本。用户取消返回 `false`。
     *
     * @param fileName 建议的文件名，含扩展名，如 `LifeBoard_2026-11-14.log`
     * @param mimeType 给接收方看的类型，如 `text/plain`
     * @param content 纯文本内容
     */
    suspend fun export(
        fileName: String,
        mimeType: String,
        content: String,
    ): Boolean

    companion object {
        /** 不支持导出的平台（含单元测试）用这个 */
        val Unavailable: TextFileExporter = object : TextFileExporter {
            override val available: Boolean = false
            override suspend fun export(fileName: String, mimeType: String, content: String): Boolean = false
        }
    }
}
