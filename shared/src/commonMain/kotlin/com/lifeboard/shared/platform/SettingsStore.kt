package com.lifeboard.shared.platform

/**
 * 键值配置存储。
 *
 * 对应 Android 的 `SharedPreferences`，桌面端由实现方决定落到哪里
 * （文件 / 注册表 / 内存均可）。**刻意不暴露 `apply()` 与 `commit()` 的区别**：
 * 那是 Android 特有的写盘时机，把它泄漏到契约里，
 * 桌面实现就只能提供一个语义不同的假 `apply`。
 */
interface SettingsStore {

    fun getString(key: String, default: String = ""): String

    fun getInt(key: String, default: Int = 0): Int

    fun getLong(key: String, default: Long = 0L): Long

    fun getBoolean(key: String, default: Boolean = false): Boolean

    fun putString(key: String, value: String)

    fun putInt(key: String, value: Int)

    fun putLong(key: String, value: Long)

    fun putBoolean(key: String, value: Boolean)

    fun remove(key: String)

    /** 是否有这个键，用于区分「没设过」与「设过空值」 */
    fun contains(key: String): Boolean
}
