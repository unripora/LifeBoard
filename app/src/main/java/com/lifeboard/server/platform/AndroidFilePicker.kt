package com.lifeboard.server.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lifeboard.shared.platform.FilePicker
import com.lifeboard.shared.platform.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * [FilePicker] 的 Android 实现。
 *
 * Android 的文件选择器是**异步回调**（`ActivityResultLauncher`），
 * 而契约是挂起函数——这里用 `suspendCancellableCoroutine` 把回调桥成一个挂起点。
 * 关键点：`launch` 必须由界面层提供（它持有 Activity），
 * 所以本类接收一个函数而不是自己做 `registerForActivityResult`：
 * 注册动作必须在 Activity 创建时完成，晚了会直接抛
 * `LifecycleOwner is attempting to register while current state is RESUMED`。
 *
 * @param launcher 弹选择器并接受回调。界面层用
 *   `registerForActivityResult(ActivityResultContracts.OpenDocument())` 实现。
 */
class AndroidFilePicker(
    context: Context,
    private val launcher: (mimeTypes: Array<String>, onResult: (Uri?) -> Unit) -> Unit,
) : FilePicker {

    private val appContext = context.applicationContext

    override suspend fun pickFile(mimeTypes: List<String>): PickedFile? {
        // 空列表表示不限类型，OpenDocument 要求传 "*/*" 而不是空数组
        val types = if (mimeTypes.isEmpty()) arrayOf("*/*") else mimeTypes.toTypedArray()

        val uri = suspendCancellableCoroutine<Uri?> { cont ->
            launcher(types) { picked -> cont.resume(picked) }
        } ?: return null

        return withContext(Dispatchers.IO) { describe(uri) }
    }

    /**
     * 读出文件名与大小。
     *
     * 拿不到就退化成「最后一段路径」与 0：宁可显示一个不完整的名字，
     * 也不能因为取不到元数据就把「用户已经选好文件」这件事整体丢掉。
     */
    private fun describe(uri: Uri): PickedFile {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L

        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }

        if (name.isBlank()) name = "unknown"
        return PickedFile(uri = uri.toString(), displayName = name, sizeBytes = size)
    }
}
