package com.lifeboard.server.platform

import android.content.Context
import com.lifeboard.shared.platform.SettingsStore

/**
 * [SettingsStore] 的 Android 实现，落在 `SharedPreferences`。
 *
 * 每次读写都走 `prefs()` 拿一次实例而不缓存：`SharedPreferences` 本身按文件名
 * 做进程级单例，缓存只是多存一个强引用，进程重建后还得判空。
 *
 * 写入一律用 `apply()`：这里没有「必须立刻落盘」的语义需求，
 * 而 `commit()` 会同步写文件，在主线程调用会卡帧。
 */
class AndroidSettingsStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE,
) : SettingsStore {

    private val appContext = context.applicationContext

    private fun prefs() = appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String =
        prefs().getString(key, default) ?: default

    override fun getInt(key: String, default: Int): Int = prefs().getInt(key, default)

    override fun getLong(key: String, default: Long): Long = prefs().getLong(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs().getBoolean(key, default)

    override fun putString(key: String, value: String) {
        prefs().edit().putString(key, value).apply()
    }

    override fun putInt(key: String, value: Int) {
        prefs().edit().putInt(key, value).apply()
    }

    override fun putLong(key: String, value: Long) {
        prefs().edit().putLong(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs().edit().putBoolean(key, value).apply()
    }

    override fun remove(key: String) {
        prefs().edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs().contains(key)

    companion object {
        const val DEFAULT_FILE = "lifeboard_settings"
    }
}
