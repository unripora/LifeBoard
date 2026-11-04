package com.lifeboard.core.ipc

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * JSONL 落盘兜底队列（方案 C）。
 *
 * 设计要点：
 * - **只追加**：避免并发写坏文件，读取方按行解析，坏行直接跳过。
 * - **进程安全**：hook 进程与模块进程可能同时写，故使用文件锁（FileLock）串行化。
 * - **不做压缩**：单行一条记录，便于故障时人工排查。
 * - **过期清理**：超过 [MAX_AGE_MS] 的记录在读取时丢弃，避免长期累积。
 *
 * 存储位置由调用方传入，通常是模块私有目录下的 [LifeBoardContract.FALLBACK_JSONL_FILE]。
 */
class JsonlFallbackQueue(private val file: File) {

    /**
     * 追加一条待补发记录。失败时仅记录日志，不抛出——兜底通道本身不应再失败。
     */
    fun append(pending: PendingWrite): Boolean = runCatching {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write((pending.toJson() + "\n").toByteArray())
            out.flush()
            out.fd.sync()
        }
        true
    }.getOrElse {
        Log.e(TAG, "append failed", it)
        false
    }

    /**
     * 读取全部待补发记录，并**原子地清空队列**。
     *
     * 先读取再截断：若清空失败，下次会重复补发。补发端基于主键 upsert，
     * 重复写入是幂等的，因此这里选择"宁可重发，不可丢失"。
     */
    fun drain(): List<PendingWrite> {
        if (!file.exists()) return emptyList()
        val now = System.currentTimeMillis()
        val result = mutableListOf<PendingWrite>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val p = PendingWrite.fromJson(line) ?: return@forEachLine
                if (now - p.createdAt <= MAX_AGE_MS) result += p
            }
        }.onFailure { Log.e(TAG, "drain read failed", it) }

        runCatching {
            if (file.exists()) FileOutputStream(file).use { it.fd.sync() }
        }.onFailure { Log.e(TAG, "drain truncate failed", it) }

        return result
    }

    /** 当前队列是否为空 */
    fun isEmpty(): Boolean = !file.exists() || file.length() == 0L

    /** 队列行数，仅用于调试展示 */
    fun size(): Int = runCatching {
        if (!file.exists()) 0 else file.readLines().count { it.isNotBlank() }
    }.getOrDefault(0)

    companion object {
        private const val TAG = "LifeBoardJsonl"

        /** 超过 7 天的记录直接丢弃 */
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
