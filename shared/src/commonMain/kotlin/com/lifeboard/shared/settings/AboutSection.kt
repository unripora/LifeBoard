package com.lifeboard.shared.settings

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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.platform.AppInfo
import com.lifeboard.shared.platform.platform
import com.lifeboard.shared.ui.common.decodePng
import com.lifeboard.shared.ui.theme.GlassCard
import com.lifeboard.shared.ui.theme.GlassPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「关于」面板（M3-3.2d 迁入 commonMain）。
 *
 * 设置页里唯一一块只读内容：应用图标、名称、版本号与运行环境。
 * 放在这里是因为「我装的是哪一版」是用户反馈问题时最先被问到的一句，
 * 而在此之前版本号只出现在启动日志和导出的日志文件里，界面上根本看不到。
 *
 * 信息全部实时取自 [AppInfo]，不写死在界面里——否则改版本号时
 * 又多了一处要记得同步的地方。两端各自翻译自己的系统版本写法
 * （Android 报 API 级别，桌面报 Windows 版本），界面只负责显示。
 */
@Composable
fun AboutSection(modifier: Modifier = Modifier) {
    val deps = platform
    val info = deps.appInfo

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        AppIdentity(info)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        // 桌面版没有构建号（buildNumber 为 0），省略括号而不是显示「（0）」
        AboutRow(
            "版本号",
            if (info.buildNumber > 0) "${info.versionName}（${info.buildNumber}）" else info.versionName,
        )
        AboutRow("包名", info.packageId)
        AboutRow("系统要求", "${info.minPlatform} 及以上")
        AboutRow("目标版本", info.targetPlatform)
        AboutRow("更新时间", info.updatedAt)
        if (info.buildNumber > 0) {
            Text(
                "版本号里的括号是构建号，每次构建自动递增，用来区分同一版本名的不同构建。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 图标 + 名称 + 版本，关于面板的抬头部分 */
@Composable
private fun AppIdentity(info: AppInfo) {
    var icon by remember { mutableStateOf<ImageBitmap?>(null) }
    // `platform` 是 @Composable 访问器，不能出现在 withContext 的 lambda 里
    val iconProvider = platform.appIcon

    LaunchedEffect(info.packageId) {
        // 读图标要跨进程（Android 是 PackageManager），放 IO，
        // 先给 null 顶一帧，避免主线程阻塞在首帧
        icon = withContext(Dispatchers.IO) {
            iconProvider.appIconPng()?.let { decodePng(it) }
        }
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
                    contentDescription = "${info.appLabel} 图标",
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
                )
            } else {
                // 解码完成前（或平台拿不到图标时）用首字母占位，避免尺寸跳动
                Text(
                    info.appLabel.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.appLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
    }
}
