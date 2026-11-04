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
    ],
    version = 7,
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
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
