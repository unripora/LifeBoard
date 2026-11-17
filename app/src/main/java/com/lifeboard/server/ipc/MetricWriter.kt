package com.lifeboard.server.ipc

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.lifeboard.core.ipc.JsonlFallbackQueue
import com.lifeboard.core.ipc.LifeBoardContract
import com.lifeboard.core.ipc.PendingWrite
import java.io.File

/**
 * 采集数据写入器：先走 ContentProvider，失败则落盘兜底。
 *
 * 这是 hook 进程侧唯一的写入口。所有采集器都应通过它提交数据，
 * 不要直接调用 contentResolver，否则失败时数据会静默丢失
 * （AutoAccounting 的 LocalNetwork 就有这个问题，见 I_solutions.md）。
 */
object MetricWriter {

    private const val TAG = "LifeBoardWriter"

    /** 模块私有目录下的兜底队列文件，hook 进程与模块进程需能访问同一路径 */
    fun queueFile(context: Context): File =
        File(File(context.filesDir, "ipc").apply { mkdirs() }, LifeBoardContract.FALLBACK_JSONL_FILE)

    /**
     * 写入一行数据。
     *
     * @param path 目标表路径，见 [LifeBoardContract.PATH_*]
     * @param values 列值，key 使用 [LifeBoardContract.COL_*]
     * @return true 表示已成功送达 Provider；false 表示已落盘兜底
     */
    fun write(context: Context, path: String, values: ContentValues): Boolean {
        val sent = runCatching {
            val uri = LifeBoardContract.uri(path)
            context.contentResolver.insert(uri, values) != null
        }.getOrElse {
            Log.w(TAG, "provider insert failed, falling back to jsonl", it)
            false
        }
        if (sent) return true

        val payload = buildMap<String, Any?> {
            values.keySet().forEach { key -> put(key, values.get(key)) }
        }
        val queued = JsonlFallbackQueue(queueFile(context)).append(PendingWrite(path = path, payload = payload))
        if (!queued) Log.e(TAG, "jsonl append also failed, data lost for path=$path")
        return false
    }

    /**
     * 把兜底队列中积压的记录回灌到 Provider。
     * 由模块进程在启动或定期调用。
     *
     * @return 成功回灌条数
     */
    fun flush(context: Context): Int {
        val queue = JsonlFallbackQueue(queueFile(context))
        if (queue.isEmpty()) return 0
        val pending = queue.drain()
        if (pending.isEmpty()) return 0

        var ok = 0
        pending.forEach { p ->
            val values = ContentValues().apply {
                p.payload.forEach { (k, v) ->
                    when (v) {
                        is Int -> put(k, v)
                        is Long -> put(k, v)
                        is Double -> put(k, v)
                        is Float -> put(k, v)
                        is Boolean -> put(k, if (v) 1 else 0)
                        is String -> put(k, v)
                        null -> putNull(k)
                        else -> put(k, v.toString())
                    }
                }
            }
            val success = runCatching {
                context.contentResolver.insert(LifeBoardContract.uri(p.path), values) != null
            }.getOrDefault(false)
            if (success) ok++
        }
        Log.i(TAG, "flush: ${ok}/${pending.size} restored")
        return ok
    }
}
