package com.lifeboard.shared.platform

/**
 * 一个用户选中的文件。
 *
 * [uri] 是不透明的平台标识：Android 是 `content://`，桌面是绝对路径。
 * 调用方只能把它原样交给 [FilePicker] 或 [PluginHost]，
 * **不允许自己去解释它的格式**——两边格式不一样，一解释就锁死在某个平台上。
 */
data class PickedFile(
    val uri: String,
    /** 文件名，用于界面展示与「装的是哪个包」的判断 */
    val displayName: String,
    /** 字节数，0 表示平台没给 */
    val sizeBytes: Long = 0L,
)

/**
 * 文件选择。
 *
 * 桌面端没有 `ActivityResultLauncher`，只能走 `FileDialog`；
 * 把「选一个文件」抽成挂起函数后，调用方不必知道自己跑在哪个平台。
 */
interface FilePicker {

    /**
     * 弹系统文件选择器，用户取消时返回 null。
     *
     * [mimeTypes] 为空表示不限制。传具体类型（如 `application/vnd.android.package-archive`）
     * 可以让 Android 的文件选择器直接把不相干的文件置灰，减少选错。
     */
    suspend fun pickFile(mimeTypes: List<String> = emptyList()): PickedFile?
}
