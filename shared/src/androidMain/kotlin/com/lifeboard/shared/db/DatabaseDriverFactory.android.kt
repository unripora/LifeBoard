package com.lifeboard.shared.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android 侧驱动实现。
 *
 * 用框架自带 SQLite，不额外捆绑 native 库——这是 APK 体积敏感项。
 *
 * ## 外键必须挂在 onOpen，不能只 execute 一次
 *
 * `PRAGMA foreign_keys` 是**每条连接独立**的开关，不是库级别的属性。
 * 之前写法是建完 driver 后 `driver.execute("PRAGMA foreign_keys = ON")`，
 * 那只对当时那一条连接生效；框架换一条连接（连接池、并发读写、WAL 下尤其明显）
 * 外键就悄悄关了，`ON DELETE CASCADE` 随之静默失效——
 * 表现是「删了页签，卡片留在库里变成永远不显示的孤儿数据」，且完全不报错。
 *
 * 正确做法是让 `SupportSQLiteOpenHelper.Callback.onOpen` 去做：
 * 它每建立一条新连接都会回调一次，是唯一能覆盖全部连接的位置。
 * 桌面侧对应的是连接串参数 `?foreign_keys=on`，语义一致。
 */
actual class DatabaseDriverFactory(private val context: Context) {

    actual fun create(path: String): SqlDriver {
        // path 在 Android 侧对应数据库文件名（框架会放到 databases 目录）
        return AndroidSqliteDriver(
            schema = LifeBoardDb.Schema,
            context = context,
            name = path,
            callback = SchemaCallback,
        )
    }
}

/**
 * 建表 + 迁移 + 外键，三件事必须在同一个 Callback 里做。
 *
 * ## 为什么不能把 onCreate/onUpgrade 留空
 *
 * 一旦给 `AndroidSqliteDriver` 传了自定义 `callback`，**建表和迁移就完全交给它**，
 * SQLDelight 不再自己调 `Schema.create`。此前本类的 `onCreate` 写成
 * `= Unit`，等于「建库时什么都不做」——老设备上有历史库文件，看不出问题；
 * **全新安装**时库文件不存在，框架走 onCreate 却一张表都没建，
 * 首个查询直接炸 `no such table: app_log`，表现为打开即闪退。
 *
 * 所以这里必须把 `Schema.create` / `Schema.migrate` 重新接回来。
 * 版本号由框架维护（`AndroidSqliteDriver` 会写 `PRAGMA user_version`），
 * 不需要像桌面端那样手动读写。
 */
private object SchemaCallback :
    SupportSQLiteOpenHelper.Callback(LifeBoardDb.Schema.version.toInt()) {

    /** 空库：按当前 schema 全量建表 */
    override fun onCreate(db: SupportSQLiteDatabase) {
        LifeBoardDb.Schema.create(db.toSqlDriver()).value
    }

    /** 老库：按版本差跑迁移链 */
    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        LifeBoardDb.Schema.migrate(db.toSqlDriver(), oldVersion.toLong(), newVersion.toLong()).value
    }

    /**
     * 每建立一条连接就打开外键约束。
     *
     * `PRAGMA foreign_keys` 是**每条连接独立**的开关，只在 onCreate 里执行
     * 覆盖不到后续新建的连接（连接池、并发读写、WAL 下尤其明显），
     * 外键会静默关闭，`ON DELETE CASCADE` 随之失效。
     * onOpen 是唯一每连接都回调的位置。
     */
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
    }
}

/**
 * 把框架的 [SupportSQLiteDatabase] 包成 SQLDelight 的 [SqlDriver]。
 *
 * `Schema.create` / `Schema.migrate` 只用到 `execute`，所以这里不需要完整实现，
 * 其余方法一律抛异常——真被调到说明用法超出了预期，应当暴露而不是悄悄返回空值。
 */
private fun SupportSQLiteDatabase.toSqlDriver(): SqlDriver = object : SqlDriver {
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        // create / migrate 跑的是纯 DDL 与数据搬运，全程无参数。
        // 真带参说明用法超出预期，直接报错暴露，而不是悄悄绑错值
        require(parameters == 0 && binders == null) {
            "建表/迁移阶段不应带参数：$sql"
        }
        execSQL(sql)
        return QueryResult.Value(0L)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = error("建表/迁移阶段不应执行查询：$sql")

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        error("建表/迁移阶段不应开启事务")

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit
}

