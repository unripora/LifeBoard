package com.lifeboard.core.ipc

/**
 * 跨进程通信契约常量。
 *
 * 通路选型（见 I_solutions.md）：ContentProvider 为主（方案 B），JSONL 落盘兜底（方案 C）。
 * hook 进程与模块进程必须共用这里的 Authority / 路径 / 列名，避免两侧拼写漂移。
 */
object LifeBoardContract {
    /** ContentProvider authority */
    const val AUTHORITY = "com.lifeboard.provider"

    /** 访问本 Provider 所需的 signature 级权限 */
    const val PERMISSION = "com.lifeboard.permission.ACCESS_PROVIDER"

    const val CONTENT_URI_PREFIX = "content://$AUTHORITY/"

    /** 表路径 */
    const val PATH_TASK = "task"
    const val PATH_DAILY_RECORD = "daily_record"
    const val PATH_METRIC = "metric"
    const val PATH_STREAK = "streak"
    const val PATH_NOTIFICATION_LOG = "notification_log"
    const val PATH_SOURCE_HEALTH = "source_health"

    /** 调用方用于触发一次采集的 method 名 */
    const val METHOD_COLLECT = "collect"

    /** 兜底落盘文件名（方案 C），位于模块私有目录 */
    const val FALLBACK_JSONL_FILE = "lifeboard_fallback.jsonl"

    // ---- 列名：与 Room 实体字段保持一致 ----
    const val COL_ID = "id"
    const val COL_TITLE = "title"
    const val COL_SOURCE_TYPE = "sourceType"
    const val COL_METRIC_BUCKET = "metricBucket"
    const val COL_THRESHOLD = "threshold"
    const val COL_WEIGHT = "weight"
    const val COL_REMIND_LEVEL = "remindLevel"
    const val COL_WINDOW_START = "windowStartMinute"
    const val COL_WINDOW_END = "windowEndMinute"
    const val COL_ENABLED = "enabled"

    const val COL_TASK_ID = "taskId"
    const val COL_DATE = "date"
    const val COL_VALUE = "value"
    const val COL_COMPLETED = "completed"
    const val COL_SCORE = "score"

    const val COL_BUCKET = "bucket"

    const val COL_CURRENT_STREAK = "currentStreak"
    const val COL_BEST_STREAK = "bestStreak"
    const val COL_LAST_DATE = "lastDate"

    const val COL_HEALTHY = "healthy"
    const val COL_LAST_SUCCESS_AT = "lastSuccessAt"
    const val COL_MESSAGE = "message"

    fun uri(path: String): android.net.Uri = android.net.Uri.parse(CONTENT_URI_PREFIX + path)
}
