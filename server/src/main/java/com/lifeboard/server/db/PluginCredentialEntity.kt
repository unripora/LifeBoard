package com.lifeboard.server.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 插件凭据表。
 *
 * 为什么要有它：插件自写登录与续期，token 明文必经插件；
 * 但**存储、加密、清除必须由宿主统一负责**，否则每个插件各存一份，
 * 用户既看不到也清不干净，卸载插件后残留的 token 更是永远查不出来。
 *
 * 隔离靠 [pluginId] 命名空间 + 接口层绑定：插件拿到的 `CredentialStore`
 * 在构造时就写死了自己的 pluginId，公开 API 里没有「指定他人 pluginId」的参数，
 * 越权在类型层面就不可表达。
 *
 * **落库的是密文**（见 `CredentialVault`），[cipherText] 是 Keystore 加密后的
 * `IV + GCM 密文`，直接拷走数据库文件解不出明文。
 *
 * @param pluginId 插件全局唯一标识
 * @param slot 插件自定义的凭据槽位名，如 `access_token`；同一插件内唯一
 * @param cipherText 密文，Base64 后存字符串以便直接查看表内容排查问题
 * @param updatedAt 最后写入时间，用于排查「凭据什么时候被改过」
 */
@Entity(
    tableName = "plugin_credential",
    primaryKeys = ["pluginId", "slot"],
    // 一键清除某插件全部凭据、卸载时级联清理都按 pluginId 查
    indices = [Index("pluginId")],
)
data class PluginCredentialEntity(
    val pluginId: String,
    val slot: String,
    val cipherText: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
