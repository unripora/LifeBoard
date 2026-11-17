package com.lifeboard.shared.rules

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.platform.AppCatalog.AppEntry
import com.lifeboard.shared.platform.LocalPlatform
import com.lifeboard.shared.ui.theme.GlassCard
import com.lifeboard.shared.ui.theme.GlassPill

/**
 * 应用行：左侧图标，右侧上「应用名」下「标识符」。
 *
 * 名称和标识符区分主次：同一个程序的标识符往往是
 * `C:\Program Files\...\Lark.exe` 或 `com.ss.android.lark` 这种无意义串，
 * 但排查规则为什么没生效时，标识符才是唯一可靠的身份。
 */
@Composable
fun AppRow(
    entry: AppEntry,
    icon: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(entry.key, icon)
        Column(Modifier.weight(1f)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.detail.ifBlank { entry.key },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) GlassPill("已选")
    }
}

/**
 * 单个图标：未解码前显示通用占位，解码完成后替换。
 *
 * 图标按需加载（滚动到哪加载到哪）而不是列表一次拉全：
 * Windows 上取图标要读 PE 资源，几百个程序一次性解码会让弹窗明显卡住。
 */
@Composable
fun AppIcon(key: String, preloaded: ImageBitmap? = null, size: Int = 44) {
    val catalog = LocalPlatform.current.appCatalog
    var icon by remember(key, preloaded) { mutableStateOf(preloaded) }

    LaunchedEffect(key, preloaded) {
        if (icon == null && key.isNotBlank()) icon = catalog.icon(key)
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
            // 没有图标时放首字母：比一个通用方块更容易在长列表里辨认
            Text(
                key.substringAfterLast('\\').substringAfterLast('/').take(1).uppercase()
                    .ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 应用选择列表（内嵌在弹窗里用） */
@Composable
fun AppList(
    entries: List<AppEntry>,
    selectedKey: String,
    onPick: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(entries, key = { it.key }) { entry ->
            AppRow(
                entry = entry,
                icon = null,
                selected = entry.key == selectedKey,
                onClick = { onPick(entry) },
            )
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "没有找到应用。可以直接在下方手动填写标识符。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** 已选应用的摘要卡片内容（图标 + 名称 + 标识符） */
@Composable
fun SelectedAppSummary(
    key: String,
    modifier: Modifier = Modifier,
    label: String = "",
) {
    val catalog = LocalPlatform.current.appCatalog
    // 调用方没给名称时向平台问一次：规则记录里只存标识符，
    // 编辑既有规则时没有别的地方能拿到人类可读的应用名
    var resolved by remember(key, label) { mutableStateOf(label) }
    LaunchedEffect(key, label) {
        if (label.isBlank() && key.isNotBlank()) {
            resolved = runCatching { catalog.label(key) }.getOrDefault(key)
        }
    }

    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        if (key.isBlank()) {
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
                AppIcon(key)
                Column(Modifier.weight(1f)) {
                    Text(
                        resolved.ifBlank { key },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 拉一次应用清单并缓存，避免每次打开弹窗都重新扫一遍系统 */
@Composable
fun rememberAppCatalogEntries(): List<AppEntry> {
    val catalog = LocalPlatform.current.appCatalog
    var entries by remember(catalog) { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(catalog) {
        if (entries.isEmpty()) entries = runCatching { catalog.list() }.getOrDefault(emptyList())
    }
    return entries
}
