package com.lifeboard.app.ui.log

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.server.db.AppLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志页。
 *
 * 排查「插件没加载」「采集没出数」这类问题时，`adb logcat` 经常帮不上忙：
 * 日志会被系统日志顶掉，手机不在手边时更是完全看不到。
 * 这里直接读数据库里持久化的日志，能筛、能导出，不需要连电脑。
 *
 * 全屏覆盖层，与插件详情页同一模式：自带顶栏和 [BackHandler]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onClose: () -> Unit,
    vm: LogViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 滑到接近底部时自动取下一页：日志可能上万条，一次查完既慢又费内存
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.logs.size - 5
        }
    }
    LaunchedEffect(shouldLoadMore, state.logs.size) {
        if (shouldLoadMore) vm.loadMore()
    }

    // 导出用 startActivity 而不是直接返回 String：分享面板需要 Uri，
    // 走 FileProvider 才能让接收方读到 application/octet-stream
    val export: () -> Unit = {
        scope.launch {
            val text = vm.export()
            val dir = File(context.cacheDir, "logs").also { it.mkdirs() }
            // 每次导出前清空，避免文件越堆越多
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.CHINA).format(Date())
            val file = File(dir, "LifeBoard_$stamp.log")
            withContext(Dispatchers.IO) { file.writeText(text) }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "导出日志")) }
                .onFailure { snackbar.showSnackbar("没有可用的分享应用") }
        }
        Unit
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("运行日志（${state.total} 条）") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = export) {
                        Icon(Icons.Filled.Share, contentDescription = "导出")
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogLevelFilter.entries.forEach { level ->
                    LevelChip(
                        level = level,
                        selected = state.level == level,
                        onClick = { vm.setLevel(level) },
                    )
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                label = { Text("搜索内容或模块") },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { vm.search() }) { Text("搜索") }
                },
            )

            if (state.logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.loading) "加载中…" else "没有符合条件的日志",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.logs, key = { it.id }) { row -> LogRow(row) }
                    if (state.loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空日志") },
            text = { Text("清掉的日志无法恢复，建议先导出保留一份。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clear()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/** 级别筛选按钮：选中态用主色底，未选中用透明底，避免一排按钮太吵 */
@Composable
private fun LevelChip(
    level: LogLevelFilter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(level.label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/**
 * 单条日志。
 *
 * 用等宽字体：日志里大量出现 `key=value` 与异常栈，等宽对齐后更容易扫读。
 * 级别只用一个色点区分，不整行染色，否则满屏颜色反而看不清内容。
 */
@Composable
private fun LogRow(row: AppLogEntity) {
    val levelColor = when (row.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        "DEBUG" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val time = remember(row.time) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(row.time))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(levelColor)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    row.level,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "$time ${row.tag}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.location.isNotBlank()) {
                Text(
                    " · ${row.location}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            row.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
