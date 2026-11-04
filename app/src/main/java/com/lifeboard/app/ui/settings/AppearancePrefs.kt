package com.lifeboard.app.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lifeboard.app.ui.theme.ColorPalette
import com.lifeboard.app.ui.theme.ThemeMode

/**
 * 外观偏好存储。
 *
 * 用 `SharedPreferences` 而不是 Room：这里只有两个字符串键，
 * 走数据库要多一次 IO 与协程往返，且首帧渲染前读不到会闪一下默认色。
 * SharedPreferences 的首次读取有磁盘 IO，用 `apply()` 写入保证不阻塞 UI 线程。
 */
object AppearancePrefs {

    private const val FILE = "lifeboard_appearance"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_PALETTE = "color_palette"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun readMode(context: Context): ThemeMode =
        ThemeMode.fromName(prefs(context).getString(KEY_MODE, null))

    fun readPalette(context: Context): ColorPalette =
        ColorPalette.fromName(prefs(context).getString(KEY_PALETTE, null))

    fun writeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun writePalette(context: Context, palette: ColorPalette) {
        prefs(context).edit().putString(KEY_PALETTE, palette.name).apply()
    }
}

/**
 * 可被 Compose 直接观察的外观状态。
 *
 * 用 `mutableStateOf` 持有当前值：设置页改动后主界面立刻重组换色，
 * 不需要重启 Activity，也不需要把状态从设置页一路传回根节点。
 */
class AppearanceState(context: Context) {

    private val appContext = context.applicationContext

    var mode: ThemeMode by mutableStateOf(AppearancePrefs.readMode(appContext))
        private set

    var palette: ColorPalette by mutableStateOf(AppearancePrefs.readPalette(appContext))
        private set

    /** 命名为 apply* 而不是 set*：`var mode` 已自动生成 setMode，同名会撞 JVM 签名 */
    fun applyMode(value: ThemeMode) {
        mode = value
        AppearancePrefs.writeMode(appContext, value)
    }

    fun applyPalette(value: ColorPalette) {
        palette = value
        AppearancePrefs.writePalette(appContext, value)
    }
}
