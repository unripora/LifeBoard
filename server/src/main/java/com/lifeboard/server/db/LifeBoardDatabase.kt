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
    version = 11,
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
                )
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
