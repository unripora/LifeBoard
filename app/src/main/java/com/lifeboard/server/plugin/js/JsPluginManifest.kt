package com.lifeboard.server.plugin.js

/**
 * JS 插件清单解析转发到共享实现。
 *
 * `manifest.json` 的结构与平台无关，解析与内容哈希都必须两端一致——
 * 尤其是哈希：它同时充当「签名指纹」并写进信任记录。
 * 两端各算一份、字节序或排序规则稍有差异，就会出现「桌面信任过的包在手机上不被信任」。
 */
typealias JsPluginManifest = com.lifeboard.shared.plugin.js.JsPluginManifest
