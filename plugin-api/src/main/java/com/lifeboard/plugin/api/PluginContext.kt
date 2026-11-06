package com.lifeboard.plugin.api

import java.io.File

/**
 * 插件运行时拿到的上下文。
 *
 * 由宿主构造，构造时**绑定好本插件的 pluginId**。
 * 插件拿不到「指定他人 pluginId」的入口，跨插件读取在接口层面就不可表达——
 * 这是凭据隔离的落脚点，不是靠运行时检查。
 */
interface PluginContext {

    /** 本插件的全局唯一标识，由宿主注入，插件不能修改 */
    val pluginId: String

    /** 本插件名下的凭据存取口，已绑定 [pluginId] */
    val credentials: CredentialStore

    /** 本插件私有目录（`filesDir/plugins/{id}/data`），已创建 */
    val filesDir: File
}

/**
 * 凭据存取口。
 *
 * 只暴露「本插件自己的槽位」：所有方法都没有 pluginId 参数，
 * 自然无法越权访问他人凭据。
 *
 * 约定：
 * - 槽位名由插件自定义（如 `access_token`），插件内唯一
 * - 值一律按敏感数据对待，**插件不得把值写进日志**
 * - [clear] 清空本插件**全部**槽位
 */
interface CredentialStore {

    /** 读取某槽位；不存在或解密失败返回 null */
    suspend fun get(slot: String): String?

    /** 写入（覆盖）某槽位 */
    suspend fun put(slot: String, value: String)

    /** 删除单个槽位 */
    suspend fun remove(slot: String)

    /** 清空本插件名下全部凭据（登出时调用） */
    suspend fun clear()
}
