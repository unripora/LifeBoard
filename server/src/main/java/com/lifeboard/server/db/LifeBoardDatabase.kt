package com.lifeboard.server.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
    ],
    version = 9,
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

        @Volatile
        private var instance: LifeBoardDatabase? = null

        fun get(context: Context): LifeBoardDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeBoardDatabase::class.java,
                    NAME,
                    // 侧载自用，库里只有几天内的采集数据，结构变更时直接重建
                    // 比手写迁移省事；等实机验收通过后再补正式迁移
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
