package com.lifeboard.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 显示 `data:image/...;base64,...` 形式的图片。
 *
 * 插件给的是数据 URI 而不是文件或 URL：图片往往与某一次会话或某一份数据绑定，
 * 落成文件反而要管清理。直接解码成 Bitmap 最省事。
 *
 * 解码放后台线程：虽然是插件的输入，不该假设它一定小——
 * 大 Base64 在 UI 线程解会卡住整个页面。这也意味着**尺寸由插件决定**，
 * 调用方要先用 [Modifier.size] 之类约束好容器，避免一张大图把布局撑开。
 *
 * @param contentDescription 无障碍描述；这是插件内容，调用方应给出有意义的文案
 * @param errorText 解码失败时的提示，不同场景说法不同（验证码与图表不该共用一句话）
 */
@Composable
fun DataUriImage(
    dataUri: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    errorText: String = "图片加载失败",
) {
    // null 表示还在解码，false 表示解码失败，true 表示解码成功。
    // 只在解码失败时才提示：还在解码时提示会在每次重组时闪一下
    var decoded by remember(dataUri) { mutableStateOf<Boolean?>(null) }

    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, dataUri) {
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val comma = dataUri.indexOf(',')
                if (comma < 0) return@withContext null
                val bytes = android.util.Base64.decode(
                    dataUri.substring(comma + 1),
                    android.util.Base64.DEFAULT,
                )
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        decoded = result != null
        value = result
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.fillMaxWidth().padding(top = 8.dp),
            contentScale = ContentScale.FillWidth,
        )
    } else if (decoded == false) {
        Text(
            errorText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
