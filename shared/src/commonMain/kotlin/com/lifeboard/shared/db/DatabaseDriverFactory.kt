package com.lifeboard.shared.db

import app.cash.sqldelight.db.SqlDriver

/**
 * 数据库驱动的平台工厂。
 *
 * 「一套 SQL」的落地方式：`.sq` 定义与查询全部在 commonMain，只有「怎么打开库」
 * 是平台实现——Android 用系统 SQLite，桌面用捆绑的 sqlite-jdbc。
 */
expect class DatabaseDriverFactory {
    /**
     * 创建驱动。
     *
     * @param path 库文件绝对路径；桌面端传 `:memory:` 可用于测试
     */
    fun create(path: String): SqlDriver
}

/** 建库 + 打开的标准流程，两端共用 */
fun openDatabase(factory: DatabaseDriverFactory, path: String): LifeBoardDb {
    val driver = factory.create(path)
    return LifeBoardDb(driver)
}
