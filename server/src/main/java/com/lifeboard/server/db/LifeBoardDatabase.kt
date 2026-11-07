package com.lifeboard.server.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        DailyRecordEntity::class,
        MetricEntity::class,
        StreakEntity::class,
        NotificationLogEntity::class,
        SourceHealthEntity::class,
        RawNotificationEntity::class,
        CaptureRuleEntity::class,
        CustomSourceEntity::class,
        AppUsageEntity::class,
        PluginCredentialEntity::class,
        DashboardTabEntity::class,
        DashboardCardEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
abstract class LifeBoardDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun metricDao(): MetricDao
    abstract fun streakDao(): StreakDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun sourceHealthDao(): SourceHealthDao
    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun captureRuleDao(): CaptureRuleDao
    abstract fun customSourceDao(): CustomSourceDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun pluginCredentialDao(): PluginCredentialDao
    abstract fun dashboardTabDao(): DashboardTabDao
    abstract fun dashboardCardDao(): DashboardCardDao

    companion object {
        const val NAME = "lifeboard.db"

        /**
         * v5 → v6：任务表新增完成提醒两个字段。
         *
         * 不能靠 fallbackToDestructiveMigration 重建——任务和规则是用户手工配的，
         * 清空等于让人重新配一遍。ALTER 加列带默认值，老任务行为保持不变
         * （完成不提醒、浮窗开）。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE task ADD COLUMN doneRemindLevel INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE task ADD COLUMN doneToastEnabled INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        /**
         * v6 → v7：任务表新增未达成提醒的时段偏移。
         *
         * 老任务默认 +5，语义为「时段结束后 5 分钟判定」——
         * 比原来的「结束前 5 分钟」晚，给读屏窗口留出缓冲。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE task ADD COLUMN windowOffsetMinutes INTEGER NOT NULL DEFAULT 5",
                )
            }
        }

        /**
         * v7 → v8：任务表新增「时间来源」。
         *
         * 老任务全部按 FOLLOW_RULE 处理，行为与升级前完全一致；
         * 想自己定时间的新任务才改成 INDEPENDENT。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE task ADD COLUMN timeSource TEXT NOT NULL DEFAULT 'FOLLOW_RULE'",
                )
            }
        }

        /**
         * v8 → v9：新增分应用屏幕用量表。
         *
         * 只是加一张新表，不动已有结构，老数据完全不受影响。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_usage` (
                        `date` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `minutes` REAL NOT NULL,
                        `label` TEXT NOT NULL,
                        PRIMARY KEY(`date`, `packageName`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v9 → v10：新增插件凭据表。
         *
         * 只是加一张新表，不动已有结构。**必须显式迁移**：
         * 凭据是有状态的数据，落到 destructive fallback 会静默清空，
         * 表现为「用户昨天刚登录，升级后又要重登」且毫无报错。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `plugin_credential` (
                        `pluginId` TEXT NOT NULL,
                        `slot` TEXT NOT NULL,
                        `cipherText` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`pluginId`, `slot`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plugin_credential_pluginId` " +
                        "ON `plugin_credential` (`pluginId`)",
                )
            }
        }

        /**
         * v10 → v11：新增仪表盘布局两张表。
         *
         * 只是加表，不动已有结构。**必须显式迁移**：
         * 落到 destructive fallback 会把任务、规则、凭据全部清空，
         * 对用户来说是「升级后所有配置都没了」。
         *
         * 表建好之后是空的，内置页签与默认卡片由
         * [com.lifeboard.server.dashboard.DashboardLayout] 在首次读取时写入，
         * 不在迁移里插数据——迁移只改结构，不碰业务内容。
         *
         * 用 internal 而非 private：迁移测试要引用同一个实例，
         * 复制一份到测试里就会「测的不是线上那段代码」。
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dashboard_tab` (
                        `tabId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `order` INTEGER NOT NULL,
                        `isBuiltin` INTEGER NOT NULL,
                        PRIMARY KEY(`tabId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dashboard_card` (
                        `cardId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `sourceKey` TEXT,
                        `metricKey` TEXT,
                        `order` INTEGER NOT NULL,
                        `tabId` TEXT NOT NULL,
                        PRIMARY KEY(`cardId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dashboard_card_tabId` " +
                        "ON `dashboard_card` (`tabId`)",
                )
            }
        }

        /**
         * v11 → v12：给 dashboard_card 加「页签外键」与「同页签同类型唯一」两条约束。
         *
         * ## 为什么是新版而不是改 MIGRATION_10_11
         *
         * v11 已经发布（M6b1 随版本出门），它的建表语句不能再改：
         * 已经升到 11 的用户不会再跑一次 10→11。约束只能靠新迁移补。
         *
         * ## SQLite 不能 ALTER 加约束，只能重建表
         *
         * 走标准的「建新表 → 搬数据 → 删旧表 → 改名 → 重建索引」。
         * 搬数据时用 `INSERT OR IGNORE`、并且加了 `WHERE tabId IN (SELECT tabId FROM dashboard_tab)`
         * 过滤：老库里如果已经存在孤儿卡（页签被删而卡片没清干净），
         * 直接搬会被外键拒绝，整个迁移失败、应用起不来。宁可丢掉那条脏数据——
         * 它本来也永远不会被显示出来。
         *
         * 唯一索引同理：万一老库里已有重复行，`OR IGNORE` 只保留先读到的那条。
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dashboard_card_new` (
                        `cardId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `sourceKey` TEXT,
                        `metricKey` TEXT,
                        `order` INTEGER NOT NULL,
                        `tabId` TEXT NOT NULL,
                        PRIMARY KEY(`cardId`),
                        FOREIGN KEY(`tabId`) REFERENCES `dashboard_tab`(`tabId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `dashboard_card_new`
                        (`cardId`, `type`, `sourceKey`, `metricKey`, `order`, `tabId`)
                    SELECT `cardId`, `type`, `sourceKey`, `metricKey`, `order`, `tabId`
                    FROM `dashboard_card`
                    WHERE `tabId` IN (SELECT `tabId` FROM `dashboard_tab`)
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `dashboard_card`")
                db.execSQL("ALTER TABLE `dashboard_card_new` RENAME TO `dashboard_card`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dashboard_card_tabId` " +
                        "ON `dashboard_card` (`tabId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_dashboard_card_tabId_type` " +
                        "ON `dashboard_card` (`tabId`, `type`)",
                )
            }
        }

        @Volatile
        private var instance: LifeBoardDatabase? = null

        fun get(context: Context): LifeBoardDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeBoardDatabase::class.java,
                    NAME,
                ).addMigrations(
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                )
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
