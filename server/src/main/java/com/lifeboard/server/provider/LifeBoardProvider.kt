package com.lifeboard.server.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.lifeboard.core.ipc.LifeBoardContract
import com.lifeboard.server.db.DailyRecordEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.MetricEntity
import com.lifeboard.server.db.SourceHealthEntity
import com.lifeboard.server.db.StreakEntity
import com.lifeboard.server.db.TaskEntity
import kotlinx.coroutines.runBlocking

/**
 * 模块唯一的数据入口（方案 B）。
 *
 * hook 进程（运行在目标应用内）通过本 Provider 写入采集结果，模块进程与 UI 侧读取。
 * 所有写操作使用 runBlocking 同步完成，因为 ContentProvider 的 insert/update 契约本身就是同步的，
 * 且单条写入极快，不会造成可感知阻塞。
 */
class LifeBoardProvider : ContentProvider() {

    private lateinit var db: LifeBoardDatabase

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        db = LifeBoardDatabase.get(ctx)
        return true
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        CODE_TASK -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_TASK"
        CODE_DAILY_RECORD -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_DAILY_RECORD"
        CODE_METRIC -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_METRIC"
        CODE_STREAK -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_STREAK"
        CODE_NOTIFICATION_LOG -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_NOTIFICATION_LOG"
        CODE_SOURCE_HEALTH -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_SOURCE_HEALTH"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (matcher.match(uri)) {
        CODE_TASK -> queryTasks(projection, selection, selectionArgs)
        CODE_METRIC -> queryMetrics(projection, selection, selectionArgs)
        CODE_DAILY_RECORD -> queryDailyRecords(projection, selection, selectionArgs)
        CODE_STREAK -> queryStreaks(projection)
        CODE_SOURCE_HEALTH -> queryHealth(projection)
        else -> throw IllegalArgumentException("Unsupported query URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) return null
        return when (matcher.match(uri)) {
            CODE_TASK -> {
                val id = runBlocking { db.taskDao().insert(values.toTaskEntity()) }
                notify(TASK_URI)
                ContentUris.withAppendedId(TASK_URI, id)
            }

            CODE_METRIC -> {
                runBlocking { db.metricDao().upsert(values.toMetricEntity()) }
                notify(METRIC_URI)
                METRIC_URI
            }

            CODE_DAILY_RECORD -> {
                runBlocking { db.dailyRecordDao().upsert(values.toDailyRecordEntity()) }
                notify(DAILY_URI)
                DAILY_URI
            }

            CODE_STREAK -> {
                runBlocking { db.streakDao().upsert(values.toStreakEntity()) }
                notify(STREAK_URI)
                STREAK_URI
            }

            CODE_SOURCE_HEALTH -> {
                runBlocking { db.sourceHealthDao().upsert(values.toHealthEntity()) }
                notify(HEALTH_URI)
                HEALTH_URI
            }

            else -> throw IllegalArgumentException("Unsupported insert URI: $uri")
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (values == null) return 0
        // 任务更新按 id 处理，其余表使用 upsert 语义走 insert
        return when (matcher.match(uri)) {
            CODE_TASK -> {
                val id = selectionArgs?.firstOrNull()?.toLongOrNull()
                    ?: parseUriIdOrNull(uri)
                    ?: return 0
                runBlocking { db.taskDao().upsert(values.toTaskEntity(id)) }
                notify(TASK_URI)
                1
            }

            else -> {
                insert(uri, values)
                1
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        when (matcher.match(uri)) {
            CODE_TASK -> {
                val id = selectionArgs?.firstOrNull()?.toLongOrNull()
                    ?: parseUriIdOrNull(uri)
                    ?: return 0
                runBlocking { db.taskDao().deleteById(id) }
                notify(TASK_URI)
                1
            }

            else -> 0
        }

    // ---- 查询实现 ----

    private fun queryTasks(
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Cursor {
        val all = runBlocking { db.taskDao().getEnabled() }
        // 简化实现：仅支持全量查询，面板侧用 Flow 直读 Room，跨进程只用于 hook 侧读取
        val cursor = MatrixCursor(projection ?: TASK_COLUMNS)
        all.forEach { t ->
            cursor.addRow(
                (projection ?: TASK_COLUMNS).map { col ->
                    when (col) {
                        LifeBoardContract.COL_ID -> t.id
                        LifeBoardContract.COL_TITLE -> t.title
                        LifeBoardContract.COL_SOURCE_TYPE -> t.sourceType
                        LifeBoardContract.COL_METRIC_BUCKET -> t.metricBucket
                        LifeBoardContract.COL_THRESHOLD -> t.threshold
                        LifeBoardContract.COL_WEIGHT -> t.weight
                        LifeBoardContract.COL_REMIND_LEVEL -> t.remindLevel
                        LifeBoardContract.COL_WINDOW_START -> t.windowStartMinute
                        LifeBoardContract.COL_WINDOW_END -> t.windowEndMinute
                        LifeBoardContract.COL_ENABLED -> if (t.enabled) 1 else 0
                        else -> null
                    }
                },
            )
        }
        return cursor
    }

    private fun queryMetrics(
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Cursor {
        // selection 约定为 date = ?，selectionArgs[0] 为日期
        val date = selectionArgs?.firstOrNull().orEmpty()
        val list = if (date.isBlank()) emptyList() else runBlocking { db.metricDao().getByDate(date) }
        val cols = projection ?: METRIC_COLUMNS
        return MatrixCursor(cols).apply {
            list.forEach { m ->
                addRow(
                    cols.map { col ->
                        when (col) {
                            LifeBoardContract.COL_SOURCE_TYPE -> m.sourceType
                            LifeBoardContract.COL_DATE -> m.date
                            LifeBoardContract.COL_BUCKET -> m.bucket
                            LifeBoardContract.COL_VALUE -> m.value
                            else -> null
                        }
                    },
                )
            }
        }
    }

    private fun queryDailyRecords(
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Cursor {
        val date = selectionArgs?.firstOrNull().orEmpty()
        val list = if (date.isBlank()) emptyList() else runBlocking { db.dailyRecordDao().getByDate(date) }
        val cols = projection ?: DAILY_COLUMNS
        return MatrixCursor(cols).apply {
            list.forEach { r ->
                addRow(
                    cols.map { col ->
                        when (col) {
                            LifeBoardContract.COL_TASK_ID -> r.taskId
                            LifeBoardContract.COL_DATE -> r.date
                            LifeBoardContract.COL_VALUE -> r.value
                            LifeBoardContract.COL_COMPLETED -> if (r.completed) 1 else 0
                            LifeBoardContract.COL_SCORE -> r.score
                            else -> null
                        }
                    },
                )
            }
        }
    }

    private fun queryStreaks(projection: Array<out String>?): Cursor {
        val list = runBlocking { db.streakDao().getAll() }
        val cols = projection ?: STREAK_COLUMNS
        return MatrixCursor(cols).apply {
            list.forEach { s ->
                addRow(
                    cols.map { col ->
                        when (col) {
                            LifeBoardContract.COL_TASK_ID -> s.taskId
                            LifeBoardContract.COL_CURRENT_STREAK -> s.currentStreak
                            LifeBoardContract.COL_BEST_STREAK -> s.bestStreak
                            LifeBoardContract.COL_LAST_DATE -> s.lastDate
                            else -> null
                        }
                    },
                )
            }
        }
    }

    private fun queryHealth(projection: Array<out String>?): Cursor {
        val list = runBlocking { db.sourceHealthDao().getAll() }
        val cols = projection ?: HEALTH_COLUMNS
        return MatrixCursor(cols).apply {
            list.forEach { h ->
                addRow(
                    cols.map { col ->
                        when (col) {
                            LifeBoardContract.COL_SOURCE_TYPE -> h.sourceType
                            LifeBoardContract.COL_HEALTHY -> if (h.healthy) 1 else 0
                            LifeBoardContract.COL_LAST_SUCCESS_AT -> h.lastSuccessAt
                            LifeBoardContract.COL_MESSAGE -> h.message
                            else -> null
                        }
                    },
                )
            }
        }
    }

    private fun notify(uri: Uri) {
        context?.contentResolver?.notifyChange(uri, null)
    }

    companion object {
        private const val AUTHORITY = LifeBoardContract.AUTHORITY

        private const val CODE_TASK = 1
        private const val CODE_METRIC = 2
        private const val CODE_DAILY_RECORD = 3
        private const val CODE_STREAK = 4
        private const val CODE_NOTIFICATION_LOG = 5
        private const val CODE_SOURCE_HEALTH = 6

        private val PATH_TASK = LifeBoardContract.PATH_TASK
        private val PATH_METRIC = LifeBoardContract.PATH_METRIC
        private val PATH_DAILY_RECORD = LifeBoardContract.PATH_DAILY_RECORD
        private val PATH_STREAK = LifeBoardContract.PATH_STREAK
        private val PATH_NOTIFICATION_LOG = LifeBoardContract.PATH_NOTIFICATION_LOG
        private val PATH_SOURCE_HEALTH = LifeBoardContract.PATH_SOURCE_HEALTH

        private val TASK_URI: Uri = LifeBoardContract.uri(PATH_TASK)
        private val METRIC_URI: Uri = LifeBoardContract.uri(PATH_METRIC)
        private val DAILY_URI: Uri = LifeBoardContract.uri(PATH_DAILY_RECORD)
        private val STREAK_URI: Uri = LifeBoardContract.uri(PATH_STREAK)
        private val HEALTH_URI: Uri = LifeBoardContract.uri(PATH_SOURCE_HEALTH)

        private val TASK_COLUMNS = arrayOf(
            LifeBoardContract.COL_ID,
            LifeBoardContract.COL_TITLE,
            LifeBoardContract.COL_SOURCE_TYPE,
            LifeBoardContract.COL_METRIC_BUCKET,
            LifeBoardContract.COL_THRESHOLD,
            LifeBoardContract.COL_WEIGHT,
            LifeBoardContract.COL_REMIND_LEVEL,
            LifeBoardContract.COL_WINDOW_START,
            LifeBoardContract.COL_WINDOW_END,
            LifeBoardContract.COL_ENABLED,
        )

        private val METRIC_COLUMNS = arrayOf(
            LifeBoardContract.COL_SOURCE_TYPE,
            LifeBoardContract.COL_DATE,
            LifeBoardContract.COL_BUCKET,
            LifeBoardContract.COL_VALUE,
        )

        private val DAILY_COLUMNS = arrayOf(
            LifeBoardContract.COL_TASK_ID,
            LifeBoardContract.COL_DATE,
            LifeBoardContract.COL_VALUE,
            LifeBoardContract.COL_COMPLETED,
            LifeBoardContract.COL_SCORE,
        )

        private val STREAK_COLUMNS = arrayOf(
            LifeBoardContract.COL_TASK_ID,
            LifeBoardContract.COL_CURRENT_STREAK,
            LifeBoardContract.COL_BEST_STREAK,
            LifeBoardContract.COL_LAST_DATE,
        )

        private val HEALTH_COLUMNS = arrayOf(
            LifeBoardContract.COL_SOURCE_TYPE,
            LifeBoardContract.COL_HEALTHY,
            LifeBoardContract.COL_LAST_SUCCESS_AT,
            LifeBoardContract.COL_MESSAGE,
        )

        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_TASK, CODE_TASK)
            addURI(AUTHORITY, PATH_METRIC, CODE_METRIC)
            addURI(AUTHORITY, PATH_DAILY_RECORD, CODE_DAILY_RECORD)
            addURI(AUTHORITY, PATH_STREAK, CODE_STREAK)
            addURI(AUTHORITY, PATH_NOTIFICATION_LOG, CODE_NOTIFICATION_LOG)
            addURI(AUTHORITY, PATH_SOURCE_HEALTH, CODE_SOURCE_HEALTH)
        }
    }
}

// ---- ContentValues <-> Entity 转换 ----

/** 尝试从 URI 解析 id，失败返回 null */
private fun parseUriIdOrNull(uri: Uri): Long? =
    runCatching { ContentUris.parseId(uri) }.getOrNull()

private fun ContentValues.toTaskEntity(id: Long = 0L): TaskEntity = TaskEntity(
    id = id,
    title = getAsString(LifeBoardContract.COL_TITLE).orEmpty(),
    sourceType = getAsString(LifeBoardContract.COL_SOURCE_TYPE).orEmpty(),
    metricBucket = getAsString(LifeBoardContract.COL_METRIC_BUCKET).orEmpty(),
    threshold = getAsDouble(LifeBoardContract.COL_THRESHOLD) ?: 0.0,
    weight = getAsDouble(LifeBoardContract.COL_WEIGHT) ?: 1.0,
    remindLevel = getAsInteger(LifeBoardContract.COL_REMIND_LEVEL) ?: 1,
    windowStartMinute = getAsInteger(LifeBoardContract.COL_WINDOW_START) ?: 0,
    windowEndMinute = getAsInteger(LifeBoardContract.COL_WINDOW_END) ?: (24 * 60 - 1),
    enabled = (getAsInteger(LifeBoardContract.COL_ENABLED) ?: 1) == 1,
)

private fun ContentValues.toMetricEntity(): MetricEntity = MetricEntity(
    sourceType = getAsString(LifeBoardContract.COL_SOURCE_TYPE).orEmpty(),
    date = getAsString(LifeBoardContract.COL_DATE).orEmpty(),
    bucket = getAsString(LifeBoardContract.COL_BUCKET).orEmpty(),
    value = getAsDouble(LifeBoardContract.COL_VALUE) ?: 0.0,
)

private fun ContentValues.toDailyRecordEntity(): DailyRecordEntity = DailyRecordEntity(
    taskId = getAsLong(LifeBoardContract.COL_TASK_ID) ?: 0L,
    date = getAsString(LifeBoardContract.COL_DATE).orEmpty(),
    value = getAsDouble(LifeBoardContract.COL_VALUE) ?: 0.0,
    completed = (getAsInteger(LifeBoardContract.COL_COMPLETED) ?: 0) == 1,
    score = getAsDouble(LifeBoardContract.COL_SCORE) ?: 0.0,
)

private fun ContentValues.toStreakEntity(): StreakEntity = StreakEntity(
    taskId = getAsLong(LifeBoardContract.COL_TASK_ID) ?: 0L,
    currentStreak = getAsInteger(LifeBoardContract.COL_CURRENT_STREAK) ?: 0,
    bestStreak = getAsInteger(LifeBoardContract.COL_BEST_STREAK) ?: 0,
    lastDate = getAsString(LifeBoardContract.COL_LAST_DATE),
)

private fun ContentValues.toHealthEntity(): SourceHealthEntity = SourceHealthEntity(
    sourceType = getAsString(LifeBoardContract.COL_SOURCE_TYPE).orEmpty(),
    healthy = (getAsInteger(LifeBoardContract.COL_HEALTHY) ?: 0) == 1,
    lastSuccessAt = getAsLong(LifeBoardContract.COL_LAST_SUCCESS_AT) ?: 0L,
    message = getAsString(LifeBoardContract.COL_MESSAGE).orEmpty(),
)
