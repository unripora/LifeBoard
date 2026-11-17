package com.lifeboard.server.plugin

/**
 * 插件包元数据的通用视图。
 *
 * 这套契约原本定义在本模块，M5 期间下沉到了 `:shared` 的 commonMain——
 * 桌面端需要同一套信任判定与身份语义，两处各写一份必然漂移
 * （M4 已经在规则状态类上踩过这个坑）。
 *
 * 这里保留类型别名而不是直接删文件，是为了让既有引用无需逐个改包名；
 * 别名本身不带任何逻辑，后续可以逐步改成直接引用 shared。
 */
typealias PluginPackageInfo = com.lifeboard.shared.plugin.PluginPackageInfo

typealias PluginIdentity = com.lifeboard.shared.plugin.PluginIdentity

typealias TrustState = com.lifeboard.shared.plugin.TrustState

typealias InstalledVersion = com.lifeboard.shared.plugin.InstalledVersion

typealias TrustVerdict = com.lifeboard.shared.plugin.TrustVerdict
