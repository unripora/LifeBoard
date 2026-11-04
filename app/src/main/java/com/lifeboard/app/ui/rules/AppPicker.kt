package com.lifeboard.app.ui.rules

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.app.ui.theme.GlassPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 应用列表项：图标、名称、包名三件套 */
data class AppItem(
    val packageName: String,
    val label: String,
    /** 已解码的图标；懒加载，未加载时为 null */
    val icon: ImageBitmap?,
)

/**
 * 已安装应用的查询与图标加载。
 *
 * 拆成"先拿列表、再按需解码图标"两步：`getApplicationIcon` 对每个包都要解析资源，
 * 几百个应用一次性解码会让列表打开时明显卡顿（甚至 ANR）。
 * 列表首屏只解码可见的，滚动到哪解码到哪，配合 [remember] 缓存不会重复解码。
 */
object InstalledApps {

    /** 拉取已安装应用（不含图标），在 IO 线程调用 */
    fun query(context: Context): List<AppItem> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map {
                    AppItem(
                        packageName = it.packageName,
                        label = runCatching { pm.getApplicationLabel(it).toString() }
                            .getOrDefault(it.packageName),
                        icon = null,
                    )
                }
                .sortedBy { it.label }
        }.getOrDefault(emptyList())
    }

    /** 解码单个应用图标，结果按包名缓存 */
    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    fun icon(context: Context, packageName: String): ImageBitmap? = synchronized(iconCache) {
        if (iconCache.containsKey(packageName)) return iconCache[packageName]
        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(packageName).toImageBitmap(96)
        }.getOrNull()
        iconCache[packageName] = bitmap
        bitmap
    }

    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bmp.asImageBitmap()
    }
}

/**
 * 应用行：左侧图标，右侧上「应用名」下「应用包名」。
 *
 * 需求明确要求这个布局——名称和包名区分主次，
 * 因为同一应用的包名往往是 `com.ss.android.lark` 这种无意义串，
 * 但排查规则为什么没生效时，包名才是唯一可靠的身份。
 */
@Composable
fun AppRow(
    item: AppItem,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(item.packageName, item.icon)
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            GlassPill("已选")
        }
        trailing?.invoke()
    }
}

/** 单个图标：未解码前显示官方 Apps 图标占位，解码完成后替换，避免行高跳动 */
@Composable
fun AppIcon(packageName: String, preloaded: ImageBitmap? = null, size: Int = 44) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var icon by remember(packageName, preloaded) { mutableStateOf(preloaded) }

    LaunchedEffect(packageName, preloaded) {
        if (icon == null) {
            icon = withContext(Dispatchers.IO) { InstalledApps.icon(context, packageName) }
        }
    }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else {
            // 官方 material-icons-core 里的 List 图标，用作通用应用占位
            Icon(
                imageVector = Icons.Filled.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size * 0.55f).dp),
            )
        }
    }
}

/** 应用选择列表（内嵌在弹窗里用） */
@Composable
fun AppList(
    apps: List<AppItem>,
    selectedPackage: String,
    onPick: (AppItem) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable (() -> Unit)? = null,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        header?.let { item { it() } }
        items(apps, key = { it.packageName }) { app ->
            AppRow(
                item = app,
                selected = app.packageName == selectedPackage,
                onClick = { onPick(app) },
            )
        }
        if (apps.isEmpty()) {
            item {
                Text(
                    "没有匹配的应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** 已选应用的摘要卡片内容（图标 + 名称 + 包名） */
@Composable
fun SelectedAppSummary(packageName: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = remember(packageName) {
        if (packageName.isBlank()) "" else InstalledApps.label(context, packageName)
    }
    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        if (packageName.isBlank()) {
            Text(
                "还没有选择应用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(packageName)
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
