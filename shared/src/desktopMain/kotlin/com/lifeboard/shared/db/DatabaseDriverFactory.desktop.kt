package com.lifeboard.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * 桌面侧驱动实现。
 *
 * 用 sqlite-jdbc（版本固定在 libs.versions.toml），不依赖用户系统装的 SQLite——
 * 否则不同机器上的行为差异会让迁移结果不可复现。
 *
 * ## 版本与建表由本类负责
 *
 * SQLDelight 生成的 `Schema.create` / `Schema.migrate` **不写 `user_version`**，
 * 它假设 Driver 自己维护版本（Android 的 `AndroidSqliteDriver` 就是这样做的）。
 * `JdbcSqliteDriver` 原生不保存版本，所以这里显式读写 `PRAGMA user_version`，
 * 才能与 Android 侧行为对齐——这是 M2 逐版本回放的前提。
 *
 * @param migrate true=按 schema 版本建库/迁移（生产用）；false=不建表（测试自行控制）
 */
actual class DatabaseDriverFactory(
    private val migrate: Boolean = true,
) {

    actual fun create(path: String): SqlDriver {
        // JdbcSqliteDriver 内部维护连接池，逐条连接的 PRAGMA 不足以覆盖全部连接，
        // 因此把 foreign_keys 写进连接串，确保**每个**新连接都开启外键。
        // 不加这个，`ON DELETE CASCADE` 会静默失效——与 Android 侧行为分叉。
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path?foreign_keys=on")
        if (migrate) {
            val current = driver.readUserVersion()
            val target = LifeBoardDb.Schema.version
            when {
                // 空库：建表并写入目标版本
                current == 0L -> {
                    LifeBoardDb.Schema.create(driver).value
                    driver.writeUserVersion(target)
                }
                // 老库：跑迁移链。**迁移完必须写回版本**，
                // 否则下次打开会认为仍是旧版、把整条链再跑一遍。
                // 11.sqm 里有「重建 dashboard_card」的步骤，重复执行虽然有
                // IF NOT EXISTS 兜底不至于崩，但等于每次启动都在搬数据。
                current < target -> {
                    LifeBoardDb.Schema.migrate(driver, current, target).value
                    driver.writeUserVersion(target)
                }
            }
        }
        return driver
    }
}

internal fun SqlDriver.readUserVersion(): Long =
    executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) },
        parameters = 0,
    ).value

internal fun SqlDriver.writeUserVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version", 0)
}
