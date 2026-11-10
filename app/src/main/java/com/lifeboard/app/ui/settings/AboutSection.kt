package com.lifeboard.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.app.ui.rules.InstalledApps
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.app.ui.theme.GlassPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「关于」面板。
 *
 * 设置页里唯一一块只读内容：应用图标、名称、版本号与运行环境。
 * 放在这里是因为「我装的是哪一版」是用户反馈问题时最先被问到的一句，
 * 而在此之前版本号只出现在启动日志和导出的日志文件里，界面上根本看不到。
 *
 * 信息全部实时取自 PackageManager，不写死在代码里——否则改版本号时
 * 又多了一处要记得同步的地方。
 */
@Composable
fun AboutSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 读包信息要跨进程，放 IO；先给 null 顶一帧，避免主线程阻塞在首帧
    var info by remember { mutableStateOf<AppInfo?>(null) }

    LaunchedEffect(Unit) {
        info = withContext(Dispatchers.IO) { loadAppInfo(context) }
    }

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        info?.let {
            AppIdentity(it)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            AboutRow("版本号", "${it.versionName}（${it.versionCode}）")
            AboutRow("包名", it.packageName)
            AboutRow("系统要求", "Android ${apiToVersion(it.minSdk)} 及以上")
            AboutRow("目标版本", "Android ${apiToVersion(it.targetSdk)}")
            AboutRow("更新时间", it.updatedAt)
            Text(
                "版本号里的括号是构建号，每次构建自动递增，用来区分同一版本名的不同构建。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } ?: Text(
            "正在读取应用信息…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 图标 + 名称 + 版本，关于面板的抬头部分 */
@Composable
private fun AppIdentity(info: AppInfo) {
    val context = LocalContext.current
    var icon by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(info.packageName) {
        icon = withContext(Dispatchers.IO) { InstalledApps.icon(context, info.packageName) }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = icon
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "${info.label} 图标",
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
                )
            } else {
                // 解码完成前用官方 List 图标占位，避免尺寸跳动
                Icon(
                    imageVector = Icons.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "记录生活数据，自己看得懂",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            GlassPill("v${info.versionName}")
        }
    }
}

/** 一行「标签 - 值」，值右对齐；长文本（包名）自动换行不撑破卡片 */
@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** API 级别转成用户认得的 Android 版本号，只覆盖项目实际会遇到的区间 */
private fun apiToVersion(api: Int): String = when (api) {
    29 -> "10"
    30 -> "11"
    31 -> "12"
    32 -> "12L"
    33 -> "13"
    34 -> "14"
    35 -> "15"
    36 -> "16"
    else -> if (api <= 0) "未知" else "API $api"
}
