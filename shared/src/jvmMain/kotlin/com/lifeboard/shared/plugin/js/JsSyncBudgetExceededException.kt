package com.lifeboard.shared.plugin.js

/**
 * 单段同步执行超过预算时抛出。
 *
 * 与 [com.lifeboard.jsengine.JsException] 区分开：那是插件自己抛的脚本错误，
 * 而这是**宿主主动打断**——调用方通常应当放弃本次结果并考虑隔离/重建实例，
 * 而不是当成普通的业务失败重试。
 */
class JsSyncBudgetExceededException(message: String) : Exception(message)
