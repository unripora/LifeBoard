package com.lifeboard.app.ui.debug

import android.content.ContentValues
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lifeboard.core.ipc.JsonlFallbackQueue
import com.lifeboard.core.ipc.LifeBoardContract
import com.lifeboard.core.ipc.PendingWrite
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.util.Dates
import com.lifeboard.server.ipc.MetricWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IPC 双通道自检页（M0 产物）。
 *
 * 面板数据不对时先来这里跑一遍：
 * 1. 主通道 ContentProvider：写一条 → 读回来，验证 Provider 通路
 * 2. 兜底通道 JSONL：入队 → 回灌，验证 Provider 不可用时的降级逻辑
 */
@Composable
fun IpcDebugScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(listOf("尚未读取数据")) }

    fun refresh() {
        scope.launch {
            lines = withContext(Dispatchers.IO) { buildStatus(context) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("主通道 · ContentProvider", style = MaterialTheme.typography.titleSmall)
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { writeViaProvider(context) }
                        refresh()
                    }
                }) { Text("写入并读取") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("兜底通道 · JSONL", style = MaterialTheme.typography.titleSmall)
                Text(
                    "模拟 Provider 不可用：先写入兜底队列，再回灌。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            queueFallback(context)
                            MetricWriter.flush(context)
                        }
                        refresh()
                    }
                }) { Text("入队并回灌") }
            }
        }

        Button(onClick = ::refresh, modifier = Modifier.fillMaxWidth()) { Text("刷新状态") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(lines) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 主通道写入：直接经内容提供者插入一条当日阅读时长（用假源避免污染屏幕时长数据） */
private fun writeViaProvider(context: Context) {
    MetricWriter.write(
        context,
        LifeBoardContract.PATH_METRIC,
        ContentValues().apply {
            put(LifeBoardContract.COL_SOURCE_TYPE, SourceType.LEGADO.name)
            put(LifeBoardContract.COL_DATE, Dates.todayString())
            put(LifeBoardContract.COL_BUCKET, MetricBucket.DURATION_MINUTES.name)
            put(LifeBoardContract.COL_VALUE, 1.0)
        },
    )
}

/** 兜底通道：直接往 JSONL 队列追加一条手动打卡记录 */
private fun queueFallback(context: Context) {
    JsonlFallbackQueue(MetricWriter.queueFile(context)).append(
        PendingWrite(
            path = LifeBoardContract.PATH_METRIC,
            payload = mapOf(
                LifeBoardContract.COL_SOURCE_TYPE to SourceType.MANUAL.name,
                LifeBoardContract.COL_DATE to Dates.todayString(),
                LifeBoardContract.COL_BUCKET to MetricBucket.COUNT.name,
                LifeBoardContract.COL_VALUE to 1.0,
            ),
        ),
    )
}

/** 汇总展示：当日数据 + 兜底队列积压行数 */
private fun buildStatus(context: Context): List<String> {
    val today = Dates.todayString()
    val out = mutableListOf<String>()
    out += "日期：$today"
    out += "兜底队列积压：${JsonlFallbackQueue(MetricWriter.queueFile(context)).size()} 行"

    val uri = LifeBoardContract.uri(LifeBoardContract.PATH_METRIC)
    var count = 0
    context.contentResolver.query(uri, null, "date = ?", arrayOf(today), null)?.use { c ->
        while (c.moveToNext()) {
            val source = c.getString(c.getColumnIndexOrThrow(LifeBoardContract.COL_SOURCE_TYPE))
            val bucket = c.getString(c.getColumnIndexOrThrow(LifeBoardContract.COL_BUCKET))
            val value = c.getDouble(c.getColumnIndexOrThrow(LifeBoardContract.COL_VALUE))
            out += "  $source / $bucket = $value"
            count++
        }
    }
    if (count == 0) out += "  （无数据）"
    return out
}
