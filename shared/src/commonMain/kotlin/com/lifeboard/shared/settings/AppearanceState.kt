package com.lifeboard.shared.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lifeboard.shared.platform.SettingsStore
import com.lifeboard.shared.ui.theme.ColorPalette
import com.lifeboard.shared.ui.theme.ThemeMode

/**
 * 外观偏好存储（M3-3.2d 迁入 commonMain）。
 *
 * ## 为什么走 [SettingsStore] 而不是 SharedPreferences
 *
 * 原来这里直接拿 `Context.getSharedPreferences`，只能留在 `:app`。
 * 两个字符串键而已，没有理由为了它再抽一个平台接口——
 * 容器里本来就有一个键值存储，键名保持不变，Android 侧的老数据照常读得到。
 *
 * ## 为什么键名是 `theme_mode` / `color_palette`
 *
 * 这是已经写进用户手机里的契约。改名会让所有老用户升级后
 * 回到默认配色——看起来只是「设置丢了」，实际是他们改过的偏好被静默清空。
 */
object AppearancePrefs {

    /** 明暗模式：存 [ThemeMode] 的 `name` */
    const val KEY_MODE = "theme_mode"

    /** 色系：存 [ColorPalette] 的 `name` */
    const val KEY_PALETTE = "color_palette"

    fun readMode(settings: SettingsStore): ThemeMode =
        // 用 null 表示「没设过」，交给 fromName 决定默认值——
        // 传空串会让 fromName 认为是「设过一个不认识的模式」
        ThemeMode.fromName(settings.getString(KEY_MODE, "").takeIf { it.isNotEmpty() })

    fun readPalette(settings: SettingsStore): ColorPalette =
        ColorPalette.fromName(settings.getString(KEY_PALETTE, "").takeIf { it.isNotEmpty() })

    fun writeMode(settings: SettingsStore, mode: ThemeMode) {
        settings.putString(KEY_MODE, mode.name)
    }

    fun writePalette(settings: SettingsStore, palette: ColorPalette) {
        settings.putString(KEY_PALETTE, palette.name)
    }
}

/**
 * 可被 Compose 直接观察的外观状态。
 *
 * 用 `mutableStateOf` 持有当前值：设置页改动后主界面立刻重组换色，
 * 不需要重启界面，也不需要把状态从设置页一路传回根节点。
 */
class AppearanceState(private val settings: SettingsStore) {

    var mode: ThemeMode by mutableStateOf(AppearancePrefs.readMode(settings))
        private set

    var palette: ColorPalette by mutableStateOf(AppearancePrefs.readPalette(settings))
        private set

    /** 命名为 apply* 而不是 set*：`var mode` 已自动生成 setMode，同名会撞 JVM 签名 */
    fun applyMode(value: ThemeMode) {
        mode = value
        AppearancePrefs.writeMode(settings, value)
    }

    fun applyPalette(value: ColorPalette) {
        palette = value
        AppearancePrefs.writePalette(settings, value)
    }
}
