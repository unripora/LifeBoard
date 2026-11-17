package com.lifeboard.server.plugin.js

/**
 * JS 插件的包元数据转发到共享实现。
 *
 * 与 `manifest.json` 的哈希规则同理：包元数据既用于展示，也参与信任判定，
 * 两端必须给出同一份结论。
 */
typealias JsPluginPackageInfo = com.lifeboard.shared.plugin.js.JsPluginPackageInfo
