package com.lifeboard.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-2.2 验收：SQLDelight 建出的库结构必须与 Room 导出的 `18.json` 等价。
 *
 * ## 为什么不去比 SQL 文本
 *
 * Room 的 `createSql` 里表名是 `${TABLE_NAME}` 占位、整条语句单行书写；
 * 我们的 `.sq` 是多行、带注释。文本永远对不齐，但**结构**必须一致。
 * 所以这里走运行时：把库建出来，读 `PRAGMA table_info` 与 `PRAGMA index_list`，
 * 与手写在本文件里的「期望结构」（来自 18.json）逐项比对。
 *
 * ## 比什么
 *
 * - 表：17 张（18.json 的 16 张 + 18.sqm 新增的 metric_meta），一个不多一个不少
 * - 列：名字、声明类型、NOT NULL、主键序号——顺序也要一致（v18 的列序是固定的）
 * - 索引：15 个，名字与唯一性
 * - 外键：1 个，指向 dashboard_tab.tabId 且 ON DELETE CASCADE
 *
 * 期望值从 `18.json` 抄来，此后每个新的 .sqm 都要同步追加到这里——
 * 这正是本测试存在的意义：让「结构漂移」无法悄悄发生。
 */
class SchemaMatchesRoomV18Test {

    private val dbFile = File.createTempFile("lifeboard-v18-", ".db").apply { delete() }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    private fun open(): SqlDriver = DatabaseDriverFactory(migrate = true).create(dbFile.absolutePath)

    /** 列签名 = 名称|类型|notnull|pk */
    private data class Col(val name: String, val type: String, val notNull: Boolean, val pk: Int)

    @Test
    fun 表清单与v18完全一致() {
        val tables = open().stringColumn(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        )
        assertEquals(
            listOf(
                "app_log", "app_usage", "capture_rule", "custom_source", "daily_record",
                "dashboard_card", "dashboard_tab", "metric", "metric_meta",
                "notification_log",
                "plugin_credential", "plugin_metric_query", "plugin_raw_record",
                "raw_notification", "source_health", "streak", "task",
            ),
            tables,
            "表清单必须与 18.json 加上 18.sqm 的 metric_meta 一致",
        )
    }

    @Test
    fun 每张表的列定义与v18一致() {
        val driver = open()
        V18_COLUMNS.forEach { (table, expected) ->
            assertEquals(expected, driver.columnsOf(table), "表 $table 的列定义与 18.json 不一致")
        }
    }

    @Test
    fun 索引与v18一致() {
        val driver = open()
        val actual = driver.stringColumn(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL ORDER BY name",
        )
        assertEquals(
            listOf(
                "index_app_log_level",
                "index_app_log_time",
                "index_capture_rule_packageName",
                "index_daily_record_date",
                "index_dashboard_card_tabId",
                "index_dashboard_card_tabId_type",
                "index_metric_date",
                "index_metric_meta_pluginId",
                "index_notification_log_createdAt",
                "index_plugin_credential_pluginId",
                "index_plugin_metric_query_pluginId_date",
                "index_plugin_raw_record_pluginId_date",
                "index_plugin_raw_record_pluginId_date_metricKey",
                "index_raw_notification_packageName",
                "index_raw_notification_postedAt",
            ),
            actual,
            "索引清单必须与 18.json 加上 18.sqm 的 index_metric_meta_pluginId 一致（15 个）",
        )
    }

    @Test
    fun 外键指向与级联行为与v18一致() {
        val driver = open()
        val fks = driver.stringColumn("PRAGMA foreign_key_list(`dashboard_card`)")
        // PRAGMA foreign_key_list 每行 8 列：id,seq,table,from,to,on_update,on_delete,match
        val rows = mutableListOf<List<String>>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA foreign_key_list(`dashboard_card`)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows += (0 until 8).map { cursor.getString(it).orEmpty() }
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        assertEquals(1, rows.size, "dashboard_card 应只有 1 个外键，实际：${fks.size}")
        val fk = rows.single()
        assertEquals("dashboard_tab", fk[2], "外键应指向 dashboard_tab")
        assertEquals("tabId", fk[3], "外键列应为 tabId")
        assertEquals("tabId", fk[4], "被引用列应为 tabId")
        assertEquals("CASCADE", fk[6], "删除行为必须是 CASCADE")
    }

    @Test
    fun 可空列只有v18里那四个() {
        val driver = open()
        val nullable = mutableListOf<String>()
        V18_COLUMNS.keys.forEach { table ->
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA table_info(`$table`)",
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val name = cursor.getString(1).orEmpty()
                        val notNull = (cursor.getLong(3) ?: 0L) == 1L
                        val pk = (cursor.getLong(5) ?: 0L) > 0L
                        if (!notNull && !pk) nullable += "$table.$name"
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            )
        }
        assertEquals(
            listOf(
                "dashboard_card.configJson",
                "dashboard_card.metricKey",
                "dashboard_card.sourceKey",
                "streak.lastDate",
            ),
            nullable.sorted(),
            "可空列必须只有 18.json 里那四个",
        )
    }
}

/** 读某表的列签名（按 cid 顺序） */
private fun SqlDriver.columnsOf(table: String): List<String> {
    val out = mutableListOf<String>()
    executeQuery(
        identifier = null,
        sql = "PRAGMA table_info(`$table`)",
        mapper = { cursor ->
            while (cursor.next().value) {
                val name = cursor.getString(1).orEmpty()
                val type = cursor.getString(2).orEmpty()
                val notNull = (cursor.getLong(3) ?: 0L) == 1L
                val pk = (cursor.getLong(5) ?: 0L).toInt()
                val flags = buildString {
                    append(if (notNull) "NN" else "--")
                    if (pk > 0) append(" PK$pk")
                }
                out += "$name|$type|$flags"
            }
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    return out
}

private fun SqlDriver.stringColumn(sql: String): List<String> {
    val out = mutableListOf<String>()
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            while (cursor.next().value) { cursor.getString(0)?.let { out += it } }
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    return out
}

/**
 * 期望结构，逐项抄自 `18.json`（`identityHash = 47c3d973622a3cfb885f4a53f9ef4d1e`）。
 *
 * 格式：`列名|声明类型|NN 或 --|PK<n>`
 * 列顺序也必须一致——v18 的列序是固定的，中途插入列会让 ALTER 语义对不上。
 */
private val V18_COLUMNS: Map<String, List<String>> = mapOf(
    "task" to listOf(
        "id|INTEGER|NN PK1", "title|TEXT|NN", "sourceType|TEXT|NN", "metricBucket|TEXT|NN",
        "threshold|REAL|NN", "weight|REAL|NN", "remindLevel|INTEGER|NN",
        "windowStartMinute|INTEGER|NN", "windowEndMinute|INTEGER|NN", "enabled|INTEGER|NN",
        "windows|TEXT|NN", "startDate|TEXT|NN", "endDate|TEXT|NN", "repeatType|TEXT|NN",
        "weekdayMask|TEXT|NN", "anchorDay|INTEGER|NN", "anchorMonth|INTEGER|NN",
        "ruleId|INTEGER|NN", "timeSource|TEXT|NN", "doneRemindLevel|INTEGER|NN",
        "doneToastEnabled|INTEGER|NN", "windowOffsetMinutes|INTEGER|NN",
    ),
    "daily_record" to listOf(
        "taskId|INTEGER|NN PK1", "date|TEXT|NN PK2", "value|REAL|NN",
        "completed|INTEGER|NN", "score|REAL|NN", "origin|TEXT|NN",
    ),
    "metric" to listOf(
        "sourceType|TEXT|NN PK1", "date|TEXT|NN PK2", "bucket|TEXT|NN PK3", "value|REAL|NN",
    ),
    "streak" to listOf(
        "taskId|INTEGER|NN PK1", "currentStreak|INTEGER|NN", "bestStreak|INTEGER|NN", "lastDate|TEXT|--",
    ),
    "notification_log" to listOf(
        "id|INTEGER|NN PK1", "taskId|INTEGER|NN", "remindLevel|INTEGER|NN",
        "triggeredAt|INTEGER|NN", "delivered|INTEGER|NN", "note|TEXT|NN", "createdAt|INTEGER|NN",
    ),
    "source_health" to listOf(
        "sourceType|TEXT|NN PK1", "healthy|INTEGER|NN", "lastSuccessAt|INTEGER|NN", "message|TEXT|NN",
    ),
    "raw_notification" to listOf(
        "id|INTEGER|NN PK1", "packageName|TEXT|NN", "title|TEXT|NN", "text|TEXT|NN",
        "matchedSources|TEXT|NN", "postedAt|INTEGER|NN",
    ),
    "capture_rule" to listOf(
        "id|INTEGER|NN PK1", "packageName|TEXT|NN", "sourceName|TEXT|NN", "bucketName|TEXT|NN",
        "includeKeywords|TEXT|NN", "excludeKeywords|TEXT|NN", "timeRegex|TEXT|NN",
        "windowStartMinute|INTEGER|NN", "windowEndMinute|INTEGER|NN", "dailyOnce|INTEGER|NN",
        "enabled|INTEGER|NN", "createdAt|INTEGER|NN", "windows|TEXT|NN", "startDate|TEXT|NN",
        "endDate|TEXT|NN", "repeatType|TEXT|NN", "weekdayMask|TEXT|NN",
        "anchorDay|INTEGER|NN", "anchorMonth|INTEGER|NN",
    ),
    "custom_source" to listOf(
        "id|INTEGER|NN PK1", "displayName|TEXT|NN", "bucket|TEXT|NN", "createdAt|INTEGER|NN",
    ),
    "app_usage" to listOf(
        "date|TEXT|NN PK1", "packageName|TEXT|NN PK2", "minutes|REAL|NN", "label|TEXT|NN",
    ),
    "plugin_credential" to listOf(
        "pluginId|TEXT|NN PK1", "slot|TEXT|NN PK2", "cipherText|TEXT|NN", "updatedAt|INTEGER|NN",
    ),
    "dashboard_tab" to listOf(
        "tabId|TEXT|NN PK1", "name|TEXT|NN", "order|INTEGER|NN", "isBuiltin|INTEGER|NN",
        "enabled|INTEGER|NN",
    ),
    "dashboard_card" to listOf(
        "cardId|TEXT|NN PK1", "type|TEXT|NN", "sourceKey|TEXT|--", "metricKey|TEXT|--",
        "order|INTEGER|NN", "tabId|TEXT|NN", "configJson|TEXT|--", "enabled|INTEGER|NN",
    ),
    "app_log" to listOf(
        "id|INTEGER|NN PK1", "level|TEXT|NN", "tag|TEXT|NN", "location|TEXT|NN",
        "message|TEXT|NN", "time|INTEGER|NN",
    ),
    "plugin_raw_record" to listOf(
        "id|INTEGER|NN PK1", "pluginId|TEXT|NN", "date|TEXT|NN", "metricKey|TEXT|NN",
        "timestamp|INTEGER|NN", "value|REAL|NN", "detail|TEXT|NN", "seq|INTEGER|NN",
    ),
    "plugin_metric_query" to listOf(
        "pluginId|TEXT|NN PK1", "date|TEXT|NN PK2", "metricKey|TEXT|NN PK3",
    ),
    // 18.sqm 新增。全部 NOT NULL：元数据缺任何一项都没法正确展示，
    // 允许为空只会把问题推迟到读取侧，变成又一处「查不到就猜」
    "metric_meta" to listOf(
        "sourceType|TEXT|NN PK1", "pluginId|TEXT|NN", "metricKey|TEXT|NN",
        "displayName|TEXT|NN", "unit|TEXT|NN", "bucket|TEXT|NN", "updatedAt|INTEGER|NN",
    ),
)
