package com.lifeboard.core.ipc

import org.json.JSONObject

/**
 * 一条待补发的采集数据（方案 C 落盘兜底用）。
 *
 * 当 hook 进程无法通过 ContentProvider 写入（目标进程权限受限、Provider 尚未初始化、
 * 或跨进程调用异常）时，先把数据序列化为 JSON 落盘，等模块进程下次启动时回灌。
 *
 * @property path 目标表路径，取值见 [LifeBoardContract.PATH_*]
 * @property payload 该行数据，key 使用 [LifeBoardContract.COL_*] 列名
 * @property createdAt 入队时间，用于排序与过期清理
 */
data class PendingWrite(
    val path: String,
    val payload: Map<String, Any?>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject().apply {
        put(KEY_PATH, path)
        put(KEY_CREATED_AT, createdAt)
        put(KEY_PAYLOAD, JSONObject(payload))
    }.toString()

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_CREATED_AT = "createdAt"

        /** 解析失败返回 null，调用方应跳过该行而不是整体失败 */
        fun fromJson(line: String): PendingWrite? = runCatching {
            val obj = JSONObject(line)
            val payloadObj = obj.getJSONObject(KEY_PAYLOAD)
            val payload = buildMap<String, Any?> {
                payloadObj.keys().forEach { k -> put(k, payloadObj.get(k)) }
            }
            PendingWrite(
                path = obj.getString(KEY_PATH),
                payload = payload,
                createdAt = obj.optLong(KEY_CREATED_AT, 0L),
            )
        }.getOrNull()
    }
}
